"""Model-based reconstruction (MBR) of the HP2 16-cell RAW. See bench/notes/sensor_model.md.

Find the image x (full resolution, white-balanced RGB) that, pushed through a model of
the sensor, reproduces the raw data:

    raw_bal[p] = gain[p] * x[colour(p), p] + noise,  noise variance = a*signal + b

- colour(p): the pixel's 4x4 colour block (G B / R G blocks, 8 px period).
- gain[p]: sensitivity of the pixel's position in the 8x8 cell (mostly its position
  under its 2x2 microlens), solved jointly and allowed to vary slowly over the frame
  (one set per GAIN_TILE x GAIN_TILE tile, normalised to mean 1 within each colour
  block so it cannot absorb real colour).
- a, b: estimated from the frame.

Priors (how real images behave):
- brightness Y = mean(x): edge-preserving (Charbonnier total variation), LAM_Y;
- colour c = x / Y: piecewise smooth (Charbonnier TV on log c), LAM_C. Colour may
  change, but every change costs, so fake colour (aliasing, fringes) loses to the
  pure-brightness explanation whenever the data allow it, and real colour edges stay
  where the data put them.

One objective, no switches between methods. Solved with Adam on the GPU.
"""
import numpy as np
import torch

try:
    from bench.algos.dir_core import masks
except ModuleNotFoundError:
    from dir_core import masks

LAM_Y = 0.004       # brightness smoothness (relative to noise-weighted data term)
LAM_C = 0.02        # colour smoothness
EPS_Y = 0.002       # Charbonnier corner for brightness gradients (balanced units)
EPS_C = 0.01        # Charbonnier corner for log-colour gradients
LAM_HF = 0.05       # cost of colour that oscillates on a few-px scale (CFA-period aliasing)
HF_SIGMA = 3.0      # px
GAIN_TILE = 128     # px; gains vary smoothly at this scale
LAM_GAIN = 50.0     # smoothness of gains across tiles
ITERS = 1500
LR = 0.003
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
VERBOSE = False


def noise_params(b, mm):
    """Fit variance = a*signal + b. Pairs of same-position pixels 8 px apart (so cell
    gains cancel) in locally flat areas only (scene texture is not noise)."""
    from scipy.ndimage import uniform_filter
    sm = uniform_filter(b, 16)
    gy, gx = np.gradient(sm)
    flat = np.hypot(gx, gy)
    d = (b[:, 8:]-b[:, :-8]).ravel()
    s = ((b[:, 8:]+b[:, :-8])/2).ravel()
    f = np.maximum(flat[:, 8:], flat[:, :-8]).ravel()
    keep = f < np.quantile(f, .3)
    d, s = d[keep], s[keep]
    edges = np.quantile(s, np.linspace(.02, .98, 9))
    xs, vs = [], []
    for lo, hi in zip(edges[:-1], edges[1:]):
        sel = (s >= lo) & (s < hi)
        if sel.sum() < 500:
            continue
        mad = np.median(np.abs(d[sel]-np.median(d[sel])))
        xs.append(np.median(s[sel]))
        vs.append((1.4826*mad)**2/2)
    xs, vs = np.array(xs), np.array(vs)
    if len(xs) < 2:
        return float(vs.mean()/max(xs.mean(), 1e-3)), 1e-6
    a, b0 = np.linalg.lstsq(np.stack([xs, np.ones_like(xs)], 1), vs, rcond=None)[0]
    return max(float(a), 1e-6), max(float(b0), 1e-7)


def charbonnier(dx, dy, eps):
    return torch.sqrt(dx*dx+dy*dy+eps*eps)-eps


def grads(t):
    dx = torch.zeros_like(t)
    dy = torch.zeros_like(t)
    dx[..., :, :-1] = t[..., :, 1:]-t[..., :, :-1]
    dy[..., :-1, :] = t[..., 1:, :]-t[..., :-1, :]
    return dx, dy


def blur(t, sigma):
    r = int(3*sigma)
    k = torch.exp(-.5*(torch.arange(-r, r+1, device=t.device, dtype=t.dtype)/sigma)**2)
    k = k/k.sum()
    c = t.shape[0]
    t = torch.nn.functional.conv2d(torch.nn.functional.pad(t[None], (r, r, 0, 0), mode='reflect'),
                                   k.view(1, 1, 1, -1).repeat(c, 1, 1, 1), groups=c)
    t = torch.nn.functional.conv2d(torch.nn.functional.pad(t, (0, 0, r, r), mode='reflect'),
                                   k.view(1, 1, -1, 1).repeat(c, 1, 1, 1), groups=c)
    return t[0]


def camera_rgb(raw, meta, init=None):
    raw = np.asarray(raw, np.float32)
    h, w = raw.shape
    neutral = np.asarray(meta['as_shot_neutral'], np.float32)
    mm = [np.broadcast_to(m, raw.shape) for m in masks(raw.shape)]
    colour = np.zeros((h, w), np.int64)
    colour[mm[0]] = 0
    colour[mm[1]] = 1
    colour[mm[2]] = 2
    b = raw/neutral[colour]
    a_n, b_n = noise_params(b, mm)
    if VERBOSE:
        print(f"noise: var = {a_n:.2e}*s + {b_n:.2e}")

    dev = torch.device(DEVICE)
    B = torch.from_numpy(b).to(dev)
    col = torch.from_numpy(colour).to(dev)
    onehot = torch.nn.functional.one_hot(col, 3).permute(2, 0, 1).float()   # 3,H,W

    # Initial image: given, or the raw value spread within each colour (simple start).
    if init is None:
        from scipy.ndimage import uniform_filter
        x0 = np.stack([uniform_filter(b*m, 9)/np.maximum(uniform_filter(m.astype(np.float32), 9), 1e-6)
                       for m in mm], 0)
    else:
        x0 = np.moveaxis(np.asarray(init, np.float32)/neutral, -1, 0)
    X = torch.from_numpy(np.ascontiguousarray(x0)).to(dev).requires_grad_()

    th, tw = -(-h//GAIN_TILE), -(-w//GAIN_TILE)
    loggain = torch.zeros((th, tw, 8, 8), device=dev, requires_grad=True)
    yy = torch.arange(h, device=dev)
    xx = torch.arange(w, device=dev)
    tile_y, tile_x = (yy//GAIN_TILE)[:, None], (xx//GAIN_TILE)[None, :]
    pos_y, pos_x = (yy % 8)[:, None], (xx % 8)[None, :]

    opt = torch.optim.Adam([X, loggain], lr=LR)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, ITERS)
    npix = float(h*w)
    for it in range(ITERS):
        opt.zero_grad()
        # Gains: mean-free (in log) within each 4x4 colour block of each tile.
        lg = loggain.view(th, tw, 2, 4, 2, 4)
        lg = lg-lg.mean(dim=(3, 5), keepdim=True)
        lg = lg.view(th, tw, 8, 8)
        gain = torch.exp(lg[tile_y, tile_x, pos_y, pos_x])
        pred = gain*(X*onehot).sum(0)
        var = a_n*torch.clamp(pred.detach(), min=0)+b_n
        data = ((pred-B)**2/var).sum()/2
        Y = X.mean(0)
        prior_y = charbonnier(*grads(Y), EPS_Y).sum()/a_n
        # Colour: each channel's departure from brightness, in the same absolute units as
        # brightness, so outline-only colour edges compete fairly with brightness edges.
        u = X-Y[None]
        prior_c = charbonnier(*grads(u), EPS_C).sum()/a_n
        prior_hf = ((u-blur(u, HF_SIGMA))**2).sum()/a_n
        gdx, gdy = grads(lg.permute(2, 3, 0, 1))
        prior_g = (gdx**2+gdy**2).sum()
        loss = (data+LAM_Y*prior_y+LAM_C*prior_c+LAM_HF*prior_hf+LAM_GAIN*prior_g)/npix
        loss.backward()
        opt.step()
        sched.step()
        if VERBOSE and (it % 200 == 0 or it == ITERS-1):
            print(it, f"data {data.item()/npix:.4f} Y {prior_y.item()/npix:.4f} C {prior_c.item()/npix:.4f}")
    with torch.no_grad():
        x = X.permute(1, 2, 0).cpu().numpy()
    return np.maximum(x*neutral, 0).astype(np.float32)


def reconstruct(raw, meta, init=None):
    return camera_rgb(raw, meta, init)
