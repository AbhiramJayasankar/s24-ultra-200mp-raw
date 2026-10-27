package dev.s24cam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every DNG this app has saved: how much space they take, which are still waiting
 * for background compression, and a way to open or delete them.
 */
public class GalleryActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private final LruCache<Long, Bitmap> cache = new LruCache<>(48);
    private final List<PhotoLibrary.Photo> photos = new ArrayList<>();
    private final Set<Uri> selected = new LinkedHashSet<>();
    private final Runnable statusChanged = () -> ui.post(this::onStatus);
    private final SimpleDateFormat when = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());

    private TextView total, totalNote, selectButton, statusTitle, statusLine, statusNote, compressNow, empty;
    private ProgressLine progress;
    private TextView deleteButton;
    private Adapter adapter;
    private boolean selecting;
    private String lastStatusKey = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BLACK);
        getWindow().setNavigationBarColor(Ui.BLACK);
        setContentView(buildLayout());
    }

    @Override
    protected void onStart() {
        super.onStart();
        CompactStatus.addListener(statusChanged);
        onStatus();
        reload();
    }

    @Override
    protected void onStop() {
        CompactStatus.removeListener(statusChanged);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (selecting) setSelecting(false);
        else super.onBackPressed();
    }

    private int dp(float v) { return (int) Ui.dp(this, v); }

    private TextView text(float sp, int color) {
        TextView v = new TextView(this);
        v.setTypeface(Ui.TEXT);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(0, i.top, 0, i.bottom);
            return WindowInsets.CONSUMED;
        });
        int pad = dp(16);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(22, Ui.BONE);
        back.setText("‹");
        back.setPadding(pad, dp(8), pad, dp(8));
        back.setContentDescription("Back to the camera");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = text(18, Ui.BONE);
        title.setText("Photos");
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        selectButton = text(15, Ui.SLATE);
        selectButton.setPadding(pad, pad, pad, pad);
        selectButton.setOnClickListener(v -> setSelecting(!selecting));
        top.addView(selectButton);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(pad, dp(4), pad, dp(8));
        total = text(44, Ui.BONE);
        total.setTypeface(Ui.FIGURES);
        total.setIncludeFontPadding(false);
        header.addView(total);
        totalNote = text(14, Ui.SLATE);
        header.addView(totalNote);

        View rule = new View(this);
        rule.setBackgroundColor(0xFF1E2124);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.topMargin = dp(20);
        rp.bottomMargin = dp(16);
        header.addView(rule, rp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusTitle = text(16, Ui.BONE);
        statusTitle.setText("Background compression");
        statusRow.addView(statusTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        compressNow = text(15, Ui.GOLD);
        compressNow.setText("Compress now");
        compressNow.setPadding(dp(12), dp(10), 0, dp(10));
        compressNow.setOnClickListener(v -> CompactStatus.runNow(getApplicationContext()));
        statusRow.addView(compressNow);
        header.addView(statusRow);
        statusLine = text(14, Ui.SLATE);
        header.addView(statusLine);
        progress = new ProgressLine(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        pp.topMargin = dp(10);
        header.addView(progress, pp);
        statusNote = text(13, Ui.SLATE);
        statusNote.setPadding(0, dp(8), 0, 0);
        header.addView(statusNote);

        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.addHeaderView(header, null, false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            PhotoLibrary.Photo p = adapter.getItem(position - list.getHeaderViewsCount());
            if (selecting) toggle(p);
            else open(p);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!selecting) setSelecting(true);
            toggle(adapter.getItem(position - list.getHeaderViewsCount()));
            return true;
        });
        body.addView(list);
        empty = text(15, Ui.SLATE);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), 0, dp(32), 0);
        empty.setText("No photos yet. Photos you take with this app show up here.");
        empty.setVisibility(View.GONE);
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        ep.bottomMargin = dp(120);
        body.addView(empty, ep);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        deleteButton = text(16, Ui.BLACK);
        deleteButton.setGravity(Gravity.CENTER);
        deleteButton.setBackgroundColor(Ui.ERROR);
        deleteButton.setOnClickListener(v -> confirmDelete());
        deleteButton.setVisibility(View.GONE);
        root.addView(deleteButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setSelecting(false);
        return root;
    }

    private void reload() {
        io.execute(() -> {
            List<PhotoLibrary.Photo> found = PhotoLibrary.list(getApplicationContext());
            long free = PhotoLibrary.freeBytes();
            ui.post(() -> show(found, free));
        });
    }

    private void show(List<PhotoLibrary.Photo> found, long free) {
        photos.clear();
        photos.addAll(found);
        Set<Uri> still = new LinkedHashSet<>();
        long bytes = 0;
        for (PhotoLibrary.Photo p : photos) {
            bytes += p.bytes;
            if (selected.contains(p.uri)) still.add(p.uri);
        }
        selected.retainAll(still);
        total.setText(size(bytes));
        totalNote.setText(String.format(Locale.US, "%s, %s free on the phone.",
                photos.size() == 1 ? "1 photo" : photos.size() + " photos", size(free)));
        empty.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
        if (photos.isEmpty() && selecting) setSelecting(false);
        adapter.notifyDataSetChanged();
        updateDelete();
        onStatus();
    }

    private void onStatus() {
        CompactStatus.Snapshot s = CompactStatus.get(getApplicationContext());
        String line;
        boolean running = s.phase == CompactStatus.Phase.RUNNING;
        int left = photos.isEmpty() ? s.filesLeft : waitingCount();
        switch (s.phase) {
            case RUNNING:
                line = s.currentName == null ? "Compressing." : "Compressing " + s.currentName + ".";
                break;
            case WAITING_FOR_CHARGER:
                line = left > 0 ? waiting(left) + " Starts when the phone is charging." : "All photos are compressed.";
                break;
            case PAUSED_WARM:
                line = "Paused because the phone is warm. It picks up again when it cools down.";
                break;
            case PAUSED_STOPPED:
                line = "Paused. It continues on the next charge.";
                break;
            case ERROR:
                line = "Stopped after an error. Your photos are untouched.";
                break;
            case DONE:
                line = left > 0 ? waiting(left) : "All photos are compressed.";
                break;
            default:
                line = left > 0 ? waiting(left) + " Starts when the phone is charging."
                        : photos.isEmpty() ? "Nothing to compress yet." : "All photos are compressed.";
        }
        statusLine.setText(line);
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) progress.set(s.tilesDone, s.tilesTotal);
        StringBuilder note = new StringBuilder();
        if (running && s.tilesTotal > 0)
            note.append(String.format(Locale.US, "%,d of %,d tiles checked. ", s.tilesDone, s.tilesTotal));
        if (s.compressedTotal > 0)
            note.append(String.format(Locale.US, "Saved %s so far on %s.", size(s.savedBytesTotal),
                    s.compressedTotal == 1 ? "1 photo" : s.compressedTotal + " photos"));
        else if (s.lastMessage != null && !running) note.append(s.lastMessage);
        statusNote.setText(note.toString().trim());
        statusNote.setVisibility(note.length() == 0 ? View.GONE : View.VISIBLE);
        compressNow.setVisibility(running ? View.GONE : View.VISIBLE);
        // A finished file changes sizes and states in the list.
        String key = s.phase + "|" + s.currentName + "|" + s.compressedTotal + "|" + s.lastMessage;
        if (!key.equals(lastStatusKey)) {
            boolean first = lastStatusKey.isEmpty();
            lastStatusKey = key;
            if (!first) reload();
        }
    }

    private int waitingCount() {
        int n = 0;
        for (PhotoLibrary.Photo p : photos) if (p.state == PhotoLibrary.State.WAITING) n++;
        return n;
    }

    private static String waiting(int n) {
        return n == 1 ? "1 photo is waiting to be compressed." : n + " photos are waiting to be compressed.";
    }

    private void setSelecting(boolean on) {
        selecting = on;
        if (!on) selected.clear();
        selectButton.setText(on ? "Done" : "Select");
        selectButton.setTextColor(on ? Ui.GOLD : Ui.SLATE);
        adapter.notifyDataSetChanged();
        updateDelete();
    }

    private void toggle(PhotoLibrary.Photo p) {
        if (!selected.remove(p.uri)) selected.add(p.uri);
        adapter.notifyDataSetChanged();
        updateDelete();
    }

    private long selectedBytes() {
        long bytes = 0;
        for (PhotoLibrary.Photo p : photos) if (selected.contains(p.uri)) bytes += p.bytes;
        return bytes;
    }

    private void updateDelete() {
        boolean show = selecting && !selected.isEmpty();
        deleteButton.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) deleteButton.setText(String.format(Locale.US, "Delete %s, %s",
                selected.size() == 1 ? "1 photo" : selected.size() + " photos", size(selectedBytes())));
    }

    private void confirmDelete() {
        int n = selected.size();
        String what = n == 1 ? "this photo" : "these " + n + " photos";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(n == 1 ? "Delete 1 photo?" : "Delete " + n + " photos?")
                .setMessage("This removes " + what + " from your phone and frees " + size(selectedBytes())
                        + ". It can't be undone.")
                .setPositiveButton("Delete", (d, w) -> delete())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete() {
        List<Uri> uris = new ArrayList<>(selected);
        io.execute(() -> {
            PhotoLibrary.DeleteResult r = PhotoLibrary.delete(getApplicationContext(), uris);
            ui.post(() -> {
                setSelecting(false);
                String msg = String.format(Locale.US, "Deleted %s, freed %s.",
                        r.deleted == 1 ? "1 photo" : r.deleted + " photos", size(r.freedBytes));
                if (!r.skipped.isEmpty()) msg += "\n\nKept:\n" + String.join("\n", r.skipped);
                if (!r.skipped.isEmpty())
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setMessage(msg).setPositiveButton("OK", null).show();
                else android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    private void open(PhotoLibrary.Photo p) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(p.uri, "image/x-adobe-dng")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, "Open " + p.name));
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "No app on this phone opens DNG files.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static String size(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.2f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format(Locale.US, "%.0f MB", bytes / 1e6);
        return String.format(Locale.US, "%.0f KB", bytes / 1e3);
    }

    private static String state(PhotoLibrary.State s) {
        switch (s) {
            case SAVING: return "Saving";
            case COMPRESSING: return "Compressing";
            case COMPRESSED: return "Compressed";
            case WAITING: return "Waiting to compress";
            default: return "Original";
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public PhotoLibrary.Photo getItem(int i) { return photos.get(i); }
        @Override public long getItemId(int i) { return photos.get(i).id; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            Row row = convert instanceof Row ? (Row) convert : new Row();
            row.bind(getItem(i));
            return row;
        }
    }

    /** Thumbnail, date and mode on the left; size and compression state on the right. */
    private final class Row extends LinearLayout {
        final ImageView thumb = new ImageView(GalleryActivity.this);
        final CheckMark check = new CheckMark(GalleryActivity.this);
        final TextView date = text(16, Ui.BONE), detail = text(13, Ui.SLATE), bytes = text(16, Ui.BONE), state = text(13, Ui.SLATE);
        long boundId = -1;

        Row() {
            super(GalleryActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            FrameLayout frame = new FrameLayout(GalleryActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF15171A);
            thumb.setClipToOutline(true);
            thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(6));
                }
            });
            frame.addView(thumb, new FrameLayout.LayoutParams(dp(56), dp(56)));
            frame.addView(check, new FrameLayout.LayoutParams(dp(56), dp(56)));
            addView(frame);
            LinearLayout left = new LinearLayout(GalleryActivity.this);
            left.setOrientation(VERTICAL);
            left.setPadding(dp(14), 0, dp(8), 0);
            left.addView(date);
            left.addView(detail);
            addView(left, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout right = new LinearLayout(GalleryActivity.this);
            right.setOrientation(VERTICAL);
            right.setGravity(Gravity.END);
            bytes.setTypeface(Ui.FIGURES);
            right.addView(bytes);
            right.addView(state);
            addView(right);
        }

        void bind(PhotoLibrary.Photo p) {
            date.setText(when.format(new Date(PhotoLibrary.captureMs(p))));
            detail.setText(p.mode + "  " + p.name);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            bytes.setText(size(p.bytes));
            state.setText(state(p.state));
            state.setTextColor(p.state == PhotoLibrary.State.WAITING || p.state == PhotoLibrary.State.COMPRESSING
                    || p.state == PhotoLibrary.State.SAVING ? Ui.GOLD : Ui.SLATE);
            check.setVisibility(selecting ? VISIBLE : GONE);
            check.setChecked(selected.contains(p.uri));
            setContentDescription(when.format(new Date(PhotoLibrary.captureMs(p))) + ", " + p.mode + ", " + size(p.bytes) + ", " + state(p.state));
            if (boundId == p.id) return;
            boundId = p.id;
            Bitmap cached = cache.get(p.id);
            thumb.setImageBitmap(cached);
            if (cached != null) return;
            int px = dp(56);
            thumbs.execute(() -> {
                Bitmap b = PhotoLibrary.thumbnail(getApplicationContext(), p, px);
                if (b == null) return;
                ui.post(() -> {
                    cache.put(p.id, b);
                    if (boundId == p.id) thumb.setImageBitmap(b);
                });
            });
        }
    }

    /** Selection mark over a thumbnail: dimmed with a gold ring when picked. */
    private static final class CheckMark extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        CheckMark(android.content.Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 2));
        }

        void setChecked(boolean on) { checked = on; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float r = Ui.dp(getContext(), 10), cx = getWidth() - r - Ui.dp(getContext(), 4), cy = r + Ui.dp(getContext(), 4);
            if (checked) {
                fill.setColor(0x66000000);
                c.drawRect(0, 0, getWidth(), getHeight(), fill);
                fill.setColor(Ui.GOLD);
                c.drawCircle(cx, cy, r, fill);
                ring.setColor(Ui.BLACK);
                Paint tick = new Paint(ring);
                tick.setStrokeCap(Paint.Cap.ROUND);
                float u = r / 10f;
                c.drawLine(cx - 5 * u, cy, cx - 1.5f * u, cy + 3.5f * u, tick);
                c.drawLine(cx - 1.5f * u, cy + 3.5f * u, cx + 5 * u, cy - 4 * u, tick);
            } else {
                fill.setColor(0x33000000);
                c.drawCircle(cx, cy, r, fill);
                ring.setColor(Ui.BONE);
                c.drawCircle(cx, cy, r, ring);
            }
        }
    }

    /** Thin gold progress line on a dark track. */
    private static final class ProgressLine extends View {
        private final Paint track = new Paint(), bar = new Paint();
        private final RectF box = new RectF();
        private float fraction;

        ProgressLine(android.content.Context c) {
            super(c);
            track.setColor(0xFF1E2124);
            bar.setColor(Ui.GOLD);
        }

        void set(int done, int total) {
            fraction = total <= 0 ? 0 : Math.min(1f, done / (float) total);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            box.set(0, 0, getWidth(), getHeight());
            c.drawRect(box, track);
            box.right = getWidth() * fraction;
            c.drawRect(box, bar);
        }
    }
}
