# ISOCELL HP2 (S24 Ultra main camera): sensor model for 200MP reconstruction

Measured on our own captures (book scene, focus brackets, 2026-10-27) plus public sources.

## Structure

- 200 MP, 0.6 µm pixels (16320 x 12240).
- Colour filter: 4x4 blocks of one colour, blocks arranged as a Bayer pattern
  (G B / R G). Colour repeats every 8 px; a normal Bayer sensor repeats every 2 px.
  Per 8x8 cell: 32 G, 16 R, 16 B, each colour clustered.
- Microlenses: one lens over each 2x2 pixels ("Super QPD"), so each colour block
  holds 4 microlenses. The 4 pixels under a lens see different halves of the main
  lens aperture (that is how phase-detect autofocus works on every pixel).
- 50 MP mode = 2x2 sums under each microlens (1.2 µm); 12.5 MP = 4x4 sums (2.4 µm).

## Measured

- Sub-image disparity (left vs right, top vs bottom pixels under each microlens),
  black-on-white region, in microlens widths:
  focus 1.48 -> +0.10/+0.14, 1.80 -> +0.04, 1.91 -> 0, 2.14 -> -0.10/-0.12.
  Zero disparity (where phase-detect AF lands, ~1.91-1.97) is NOT the sharpest
  focus (1.50 on this scene, all of 12/50/200 MP). At best focus the disparity is
  ~0.05 px: not the cause of in-focus artifacts, but it is the cause of zipper /
  "pixelation" in out-of-focus areas (the earlier misfocused shots).
- Per-position sensitivity: the 64-position pattern is almost entirely *within each
  microlens*: microlens-average gains are 1.000 +- 0.006, while the 4 positions
  under a lens differ by up to +-3% (right column and top row brighter here; the
  blue block shows the strongest split). It depends on position in the frame
  (main-lens ray angle), on colour, and on focus.
- Colour aliasing: black/white stripes with a period near 8 px (barcode) land on
  red columns and blue columns unequally and look like colour. Real colour differs
  from both green blocks of a cell; stripe aliasing matches the green block that
  shares its columns/rows.
- Coloured edges (red ink on white): red-filter pixels read the same on ink and
  paper, so only G and B pixels carry the edge. Inside a 4x4 red block the edge
  position must come from neighbouring blocks 1-4 px away.
- Optics: f/1.7, Airy disk ~2.3 µm (~4 pixels). The lens resolves less than
  0.6 µm pixels can record, so 200 MP gains are real but modest, and noise per
  pixel is 4x (variance) that of 50 MP for the same light.

## Why artifacts appear

All reconstruction artifacts so far come from treating pixels as independent point
samples with a colour filter: block-level colour sampling (false colour, soft coloured
edges), per-microlens-position gains (mottling, grid), sub-aperture parallax (zipper
when defocused), and ad-hoc switches between methods (stepping where they hand over).

## General method

Model-based reconstruction: find the full-resolution image that, pushed through a
model of this sensor, reproduces the raw data, with priors that encode how real
images behave.

raw[p] = gain[p] * (CFA[p] . (PSF[p] * scene))[p] + noise[p]

- scene: luminance at full resolution + chroma (colour) that is smooth except where
  luminance also has an edge.
- CFA[p]: the pixel's colour (4x4 blocks).
- gain[p]: per microlens position, slowly varying over the frame (estimated from the
  data itself).
- PSF[p]: the sub-aperture blur/shift for the pixel's position under its microlens
  (zero shift in focus; estimated from disparity for defocused areas).
- noise[p]: shot + read noise (variance ~ a*signal + b, estimated from the data).

Solve: minimise noise-weighted data misfit + luminance prior (edge-preserving) +
chroma prior (smooth; chroma edges only where luminance edges are). One objective
covers neutral detail (chroma flat -> every pixel informs luminance, the black-and-
white case), coloured edges (chroma may jump where luminance jumps), stripe aliasing
(fake colour costs more than real luminance detail), and gain patterns (modelled,
not filtered).
