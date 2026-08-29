package com.oasisautoplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.content.SharedPreferences;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OasisAccessibilityService extends AccessibilityService {

    private static final String TAG = "OasisAutoplay";
    private static final String OASIS_PACKAGE = "com.iwf.oasis";

    public static final String PREFS = "oasis_autoplay";
    public static final String PREF_AUTOPLAY_ENABLED = "autoplay_enabled";
    public static final String PREF_SERVICE_CONNECTED = "service_connected";
    public static final String PREF_LAST_ANALYSIS = "last_analysis_ms";
    public static final String PREF_LAST_ACTION = "last_action_ms";

    private static final long ANALYSIS_INTERVAL_MS = 850;
    private static final long MENU_COOLDOWN_MS = 1200;
    private static final long MAGIC_COOLDOWN_MS = 850;
    private static final long ATTACK_COOLDOWN_MS = 1450;
    private static final long VICTORY_COOLDOWN_MS = 900;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();

    private String lastPackage = "";
    private volatile boolean screenshotBusy = false;
    private volatile boolean ocrBusy = false;
    private volatile boolean destroyed = false;

    private long nextAllowedAnalysisMs = 0;
    private long lastActionMs = 0;

    private boolean inCombat = false;
    private boolean attackBuffUsed = false;
    private boolean openingSplashDone = false;
    private boolean waitingForScreenChange = false;

    private TextRecognizer recognizer = null;
    private final Map<Integer, StackTrack> tracks = new HashMap<>();

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (destroyed) return;

            long now = System.currentTimeMillis();
            if (isAutoplayEnabled()
                    && OASIS_PACKAGE.equals(lastPackage)
                    && now >= nextAllowedAnalysisMs
                    && !screenshotBusy
                    && !ocrBusy) {
                analyzeScreen();
            }

            main.postDelayed(this, ANALYSIS_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        destroyed = false;
        screenshotBusy = false;
        ocrBusy = false;
        waitingForScreenChange = false;
        nextAllowedAnalysisMs = System.currentTimeMillis() + 1000;

        main.removeCallbacks(loop);
        main.postDelayed(loop, 1000);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SERVICE_CONNECTED, true)
                .apply();

        Log.i(TAG, "Oasis Autoplay: serviço conectado");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        lastPackage = event.getPackageName().toString();
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Serviço interrompido pelo sistema");
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(loop);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SERVICE_CONNECTED, false)
                .apply();

        try {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        } catch (Throwable ignored) {}

        try {
            screenshotExecutor.shutdownNow();
        } catch (Throwable ignored) {}

        super.onDestroy();
    }

    private void analyzeScreen() {
        if (destroyed) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (screenshotBusy || ocrBusy) return;

        screenshotBusy = true;

        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            Bitmap soft = null;
                            HardwareBuffer buffer = null;

                            try {
                                if (destroyed) return;

                                buffer = result.getHardwareBuffer();
                                if (buffer == null) return;

                                ColorSpace cs = result.getColorSpace();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, cs);
                                if (hw == null) return;

                                soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                                if (soft != null && !destroyed) {
                                    getSharedPreferences(PREFS, MODE_PRIVATE)
                                            .edit()
                                            .putLong(PREF_LAST_ANALYSIS, System.currentTimeMillis())
                                            .apply();
                                    detectStateAndAct(soft);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "Falha segura ao analisar screenshot", t);
                                nextAllowedAnalysisMs =
                                        System.currentTimeMillis() + 1500;
                            } finally {
                                try {
                                    if (soft != null && !soft.isRecycled()) soft.recycle();
                                } catch (Throwable ignored) {}

                                try {
                                    if (buffer != null) buffer.close();
                                } catch (Throwable ignored) {}

                                screenshotBusy = false;
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            screenshotBusy = false;
                            nextAllowedAnalysisMs =
                                    System.currentTimeMillis() + 1500;
                            Log.w(TAG, "Screenshot indisponível: " + errorCode);
                        }
                    }
            );
        } catch (Throwable t) {
            screenshotBusy = false;
            nextAllowedAnalysisMs = System.currentTimeMillis() + 2000;
            Log.e(TAG, "takeScreenshot não pôde iniciar", t);
        }
    }

    private void detectStateAndAct(Bitmap b) {
        if (destroyed) return;

        long now = System.currentTimeMillis();
        if (now < nextAllowedAnalysisMs) return;

        int w = b.getWidth();
        int h = b.getHeight();

        double tan = ratio(b, 0.08, 0.16, 0.92, 0.78, 24, PixelKind.TAN);
        if (tan > 0.48) {
            resetCombat();
            performAction("VICTORY_CLOSE",
                    0.25f * w, 0.91f * h, VICTORY_COOLDOWN_MS);
            return;
        }

        double purple =
                ratio(b, 0.05, 0.14, 0.95, 0.83, 26, PixelKind.PURPLE);

        if (purple > 0.30) {
            inCombat = true;

            if (!attackBuffUsed) {
                float attackIconX = 0.085f * w;
                float attackIconY = 0.275f * h;

                if (tap(attackIconX, attackIconY)) {
                    attackBuffUsed = true;
                    waitingForScreenChange = true;
                    lastActionMs = now;
                    nextAllowedAnalysisMs = now + MAGIC_COOLDOWN_MS;

                    main.postDelayed(() -> {
                        if (destroyed) return;

                        tap(0.25f * w, 0.91f * h);
                        nextAllowedAnalysisMs =
                                System.currentTimeMillis() + MAGIC_COOLDOWN_MS;
                        waitingForScreenChange = false;
                        Log.i(TAG, "MAGIA: Attack aplicada");
                    }, 600);
                }
            }
            return;
        }

        // v0.3.2: detector mais robusto do menu de alvo.
        // Procura um bloco magenta/roxo no centro inferior e ataca pelo centro
        // aproximado do primeiro botão ("Atacar").
        if (!inCombat && detectTargetMenu(b)) {
            float attackX = 0.25f * w;
            float attackY = 0.735f * h;

            performAction(
                    "MAP_TARGET_MENU_ATTACK",
                    attackX,
                    attackY,
                    MENU_COOLDOWN_MS
            );
            return;
        }

        if (waitingForScreenChange) return;

        List<StackCandidate> candidates = findStackCandidates(b);

        if (candidates.size() >= 2) {
            inCombat = true;
            updateTracks(candidates);

            if (shouldCompareFourAndFive()) {
                readFourFiveCountsAndAttack(b, w, h, candidates);
            } else {
                chooseStrategicTargetAndTap(w, h);
            }
        }
    }

    private boolean detectTargetMenu(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        // Janela ampla, baseada no vídeo real do aparelho:
        // centro/esquerda da metade inferior da tela.
        int x0 = (int)(0.08f * w);
        int x1 = (int)(0.62f * w);
        int y0 = (int)(0.57f * h);
        int y1 = (int)(0.84f * h);

        int magentaHits = 0;
        int purpleHits = 0;
        int darkHits = 0;
        int total = 0;

        int step = 12;

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = b.getPixel(x, y);
                total++;

                int r = Color.red(c);
                int g = Color.green(c);
                int bl = Color.blue(c);

                if (r > 105 && bl > 70 && g < 105 && r > g + 25) {
                    magentaHits++;
                }

                if (bl > g + 15 && r > g + 10 && r > 45 && bl > 50) {
                    purpleHits++;
                }

                if (r < 80 && g < 80 && bl < 80) {
                    darkHits++;
                }
            }
        }

        if (total == 0) return false;

        double magentaRatio = (double)magentaHits / total;
        double purpleRatio = (double)purpleHits / total;
        double darkRatio = (double)darkHits / total;

        // Menu real tem mistura de painel roxo/magenta + fundo escuro.
        boolean detected =
                (magentaRatio > 0.015 && purpleRatio > 0.045)
                || (purpleRatio > 0.075 && darkRatio > 0.10);

        if (detected) {
            Log.i(TAG,
                    "MENU detectado magenta=" + magentaRatio
                    + " purple=" + purpleRatio
                    + " dark=" + darkRatio);
        }

        return detected;
    }

    private void resetCombat() {
        inCombat = false;
        attackBuffUsed = false;
        openingSplashDone = false;
        waitingForScreenChange = false;
        ocrBusy = false;
        tracks.clear();
    }

    private boolean shouldCompareFourAndFive() {
        StackTrack t4 = tracks.get(4);
        StackTrack t5 = tracks.get(5);

        boolean a4 = t4 != null && t4.alive;
        boolean a5 = t5 != null && t5.alive;

        boolean frontAlive = false;
        for (int id = 1; id <= 3; id++) {
            StackTrack t = tracks.get(id);
            if (t != null && t.alive) {
                frontAlive = true;
                break;
            }
        }

        return !frontAlive && a4 && a5;
    }

    private void readFourFiveCountsAndAttack(
            Bitmap screen, int w, int h, List<StackCandidate> candidates) {

        if (ocrBusy || destroyed) return;

        StackTrack t4 = tracks.get(4);
        StackTrack t5 = tracks.get(5);
        if (t4 == null || t5 == null) return;

        StackCandidate c4 = nearestCandidate(t4, candidates);
        StackCandidate c5 = nearestCandidate(t5, candidates);

        if (c4 == null || c5 == null) {
            chooseStrategicTargetAndTap(w, h);
            return;
        }

        final Bitmap crop4 = cropLabel(screen, c4.label);
        final Bitmap crop5 = cropLabel(screen, c5.label);

        if (crop4 == null || crop5 == null) {
            if (crop4 != null) crop4.recycle();
            if (crop5 != null) crop5.recycle();
            chooseStrategicTargetAndTap(w, h);
            return;
        }

        ocrBusy = true;
        nextAllowedAnalysisMs = System.currentTimeMillis() + 1200;

        try {
            if (recognizer == null) {
                recognizer =
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }

            recognizer.process(InputImage.fromBitmap(crop4, 0))
                    .addOnSuccessListener(text4 -> {
                        long n4 = parseBestInteger(text4);

                        recognizer.process(InputImage.fromBitmap(crop5, 0))
                                .addOnSuccessListener(text5 -> {
                                    long n5 = parseBestInteger(text5);

                                    if (n4 >= 0) t4.count = n4;
                                    if (n5 >= 0) t5.count = n5;

                                    StackTrack target;

                                    if (n4 >= 0 && n5 >= 0) {
                                        target = n4 >= n5 ? t4 : t5;
                                    } else {
                                        target = t4.y >= t5.y ? t4 : t5;
                                    }

                                    attackTrack(target, w, h);
                                })
                                .addOnFailureListener(e -> {
                                    StackTrack target =
                                            t4.y >= t5.y ? t4 : t5;
                                    attackTrack(target, w, h);
                                })
                                .addOnCompleteListener(task -> {
                                    try {
                                        if (!crop5.isRecycled()) crop5.recycle();
                                    } catch (Throwable ignored) {}

                                    ocrBusy = false;
                                });
                    })
                    .addOnFailureListener(e -> {
                        StackTrack target = t4.y >= t5.y ? t4 : t5;
                        attackTrack(target, w, h);
                        ocrBusy = false;
                    })
                    .addOnCompleteListener(task -> {
                        try {
                            if (!crop4.isRecycled()) crop4.recycle();
                        } catch (Throwable ignored) {}
                    });

        } catch (Throwable t) {
            try { crop4.recycle(); } catch (Throwable ignored) {}
            try { crop5.recycle(); } catch (Throwable ignored) {}

            ocrBusy = false;
            nextAllowedAnalysisMs = System.currentTimeMillis() + 1500;

            StackTrack target = t4.y >= t5.y ? t4 : t5;
            attackTrack(target, w, h);
        }
    }

    private Bitmap cropLabel(Bitmap b, Rect original) {
        try {
            Rect r = grow(original, b.getWidth(), b.getHeight(), 6, 4);
            if (r.width() <= 2 || r.height() <= 2) return null;
            return Bitmap.createBitmap(
                    b, r.left, r.top, r.width(), r.height());
        } catch (Throwable t) {
            return null;
        }
    }

    private long parseBestInteger(Text text) {
        if (text == null || text.getText() == null) return -1;

        long best = -1;
        String[] pieces = text.getText().split("[^0-9]+");

        for (String p : pieces) {
            if (p == null || p.isEmpty()) continue;
            try {
                long value = Long.parseLong(p);
                if (value > best) best = value;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private StackCandidate nearestCandidate(
            StackTrack track, List<StackCandidate> candidates) {

        StackCandidate best = null;
        double bestDistance = Double.MAX_VALUE;

        for (StackCandidate c : candidates) {
            double d = Math.hypot(c.x - track.x, c.y - track.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }

        return bestDistance <= 100 ? best : null;
    }

    private List<StackCandidate> findStackCandidates(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        int x0 = (int) (0.04 * w);
        int x1 = (int) (0.96 * w);
        int y0 = (int) (0.12 * h);
        int y1 = (int) (0.78 * h);
        int step = 4;

        int rows = Math.max(1, (y1 - y0) / step + 2);
        int cols = Math.max(1, (x1 - x0) / step + 2);

        boolean[][] visited = new boolean[rows][cols];
        List<StackCandidate> out = new ArrayList<>();

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {

                int gy = (y - y0) / step;
                int gx = (x - x0) / step;

                if (gy < 0 || gx < 0 || gy >= rows || gx >= cols) continue;
                if (visited[gy][gx]) continue;
                if (!isLabelDark(b.getPixel(x, y))) continue;

                ArrayList<int[]> q = new ArrayList<>();
                q.add(new int[]{gx, gy});
                visited[gy][gx] = true;

                int qi = 0;
                int minGX = gx, maxGX = gx;
                int minGY = gy, maxGY = gy;
                int n = 0;

                while (qi < q.size() && n < 2500) {
                    int[] p = q.get(qi++);
                    n++;

                    minGX = Math.min(minGX, p[0]);
                    maxGX = Math.max(maxGX, p[0]);
                    minGY = Math.min(minGY, p[1]);
                    maxGY = Math.max(maxGY, p[1]);

                    int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
                    for (int[] d : dirs) {
                        int nx = p[0] + d[0];
                        int ny = p[1] + d[1];

                        if (ny < 0 || nx < 0 || ny >= rows || nx >= cols) continue;
                        if (visited[ny][nx]) continue;

                        int sx = x0 + nx * step;
                        int sy = y0 + ny * step;

                        if (sx >= x1 || sy >= y1) continue;
                        if (!isLabelDark(b.getPixel(sx, sy))) continue;

                        visited[ny][nx] = true;
                        q.add(new int[]{nx, ny});
                    }
                }

                int left = x0 + minGX * step;
                int right = x0 + (maxGX + 1) * step;
                int top = y0 + minGY * step;
                int bottom = y0 + (maxGY + 1) * step;

                int bw = right - left;
                int bh = bottom - top;

                if (bw >= 24 && bw <= 150 && bh >= 10 && bh <= 52) {
                    Rect label = new Rect(left, top, right, bottom);

                    float sx = (left + right) / 2f;
                    float sy = Math.max(
                            0, top - Math.max(28f, bh * 1.55f));

                    out.add(new StackCandidate(sx, sy, label));
                }
            }
        }

        Collections.sort(out, Comparator.comparingDouble(a -> a.x));

        List<StackCandidate> dedup = new ArrayList<>();
        for (StackCandidate c : out) {
            boolean near = false;

            for (StackCandidate d : dedup) {
                if (Math.hypot(c.x - d.x, c.y - d.y) < 38) {
                    near = true;
                    break;
                }
            }

            if (!near) dedup.add(c);
        }

        return dedup;
    }

    private void updateTracks(List<StackCandidate> cands) {
        if (tracks.isEmpty()) {
            if (cands.size() < 5) return;

            Collections.sort(cands, Comparator.comparingDouble(a -> a.x));

            for (int i = 0; i < 5; i++) {
                StackCandidate c = cands.get(i);
                tracks.put(i + 1,
                        new StackTrack(i + 1, c.x, c.y));
            }

            return;
        }

        List<StackCandidate> remaining = new ArrayList<>(cands);

        for (int id = 1; id <= 5; id++) {
            StackTrack t = tracks.get(id);
            if (t == null || !t.alive) continue;

            StackCandidate best = null;
            double bestDistance = Double.MAX_VALUE;

            for (StackCandidate c : remaining) {
                double d = Math.hypot(c.x - t.x, c.y - t.y);

                if (d < bestDistance) {
                    bestDistance = d;
                    best = c;
                }
            }

            if (best != null && bestDistance < 230) {
                t.x = best.x;
                t.y = best.y;
                t.missed = 0;
                remaining.remove(best);
            } else {
                t.missed++;

                if (t.missed >= 3) {
                    t.alive = false;
                }
            }
        }
    }

    private void chooseStrategicTargetAndTap(int w, int h) {
        List<StackTrack> alive = aliveTracks();
        if (alive.isEmpty()) return;

        StackTrack target = null;

        StackTrack t2 = tracks.get(2);
        if (!openingSplashDone && t2 != null && t2.alive) {
            target = t2;
            openingSplashDone = true;
        }

        if (target == null) {
            StackTrack urgent = null;

            for (int id = 1; id <= 3; id++) {
                StackTrack t = tracks.get(id);
                if (t == null || !t.alive) continue;

                if (urgent == null || t.y > urgent.y) {
                    urgent = t;
                }
            }

            if (urgent != null) {
                target = urgent;
            }
        }

        if (target == null) {
            StackTrack t4 = tracks.get(4);
            StackTrack t5 = tracks.get(5);

            boolean a4 = t4 != null && t4.alive;
            boolean a5 = t5 != null && t5.alive;

            if (a4 && !a5) target = t4;
            else if (a5 && !a4) target = t5;
        }

        if (target == null) {
            for (StackTrack t : alive) {
                if (target == null || t.y > target.y) target = t;
            }
        }

        if (target != null) attackTrack(target, w, h);
    }

    private void attackTrack(StackTrack target, int w, int h) {
        if (target == null || destroyed) return;

        float tx = clamp(target.x, 20, w - 20);
        float ty = clamp(target.y, 60, h - 160);

        performAction(
                "ATTACK_TROLL_" + target.id,
                tx,
                ty,
                ATTACK_COOLDOWN_MS
        );
    }

    private List<StackTrack> aliveTracks() {
        List<StackTrack> out = new ArrayList<>();

        for (int id = 1; id <= 5; id++) {
            StackTrack t = tracks.get(id);
            if (t != null && t.alive) out.add(t);
        }

        return out;
    }

    private boolean performAction(
            String name, float x, float y, long cooldownMs) {

        long now = System.currentTimeMillis();

        if (destroyed) return false;
        if (now < nextAllowedAnalysisMs) return false;
        if (now - lastActionMs < 300) return false;

        boolean ok = tap(x, y);

        if (ok) {
            lastActionMs = now;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putLong(PREF_LAST_ACTION, now)
                    .apply();

            nextAllowedAnalysisMs = now + cooldownMs;
            waitingForScreenChange = true;

            main.postDelayed(() -> {
                waitingForScreenChange = false;
            }, Math.max(350, cooldownMs - 150));

            Log.i(TAG, name + " -> " + x + "," + y
                    + " cooldown=" + cooldownMs);
        }

        return ok;
    }

    private boolean isAutoplayEnabled() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_AUTOPLAY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean tap(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 80);

            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(stroke)
                            .build();

            return dispatchGesture(gesture, null, null);

        } catch (Throwable t) {
            Log.e(TAG, "Falha no gesto", t);
            return false;
        }
    }

    private Rect grow(
            Rect r, int w, int h, int gx, int gy) {

        return new Rect(
                Math.max(0, r.left - gx),
                Math.max(0, r.top - gy),
                Math.min(w, r.right + gx),
                Math.min(h, r.bottom + gy)
        );
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private boolean isLabelDark(int c) {
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);

        return r < 55 && g < 55 && b < 55;
    }

    private enum PixelKind {
        TAN, PURPLE
    }

    private boolean matches(int c, PixelKind kind) {
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);

        switch (kind) {
            case TAN:
                return r > 175 && g > 135 && g < 220
                        && b > 90 && b < 190 && r > g;

            case PURPLE:
                return r > 35 && r < 155
                        && b > 50 && b < 180
                        && b > g && r > g;

            default:
                return false;
        }
    }

    private double ratio(
            Bitmap bitmap,
            double x1, double y1,
            double x2, double y2,
            int step,
            PixelKind kind) {

        int sx = Math.max(0, (int) (x1 * bitmap.getWidth()));
        int sy = Math.max(0, (int) (y1 * bitmap.getHeight()));
        int ex = Math.min(
                bitmap.getWidth() - 1,
                (int) (x2 * bitmap.getWidth()));
        int ey = Math.min(
                bitmap.getHeight() - 1,
                (int) (y2 * bitmap.getHeight()));

        int hit = 0;
        int total = 0;

        for (int y = sy; y <= ey; y += step) {
            for (int x = sx; x <= ex; x += step) {
                total++;
                if (matches(bitmap.getPixel(x, y), kind)) hit++;
            }
        }

        return total == 0 ? 0 : ((double) hit / total);
    }

    private static class StackCandidate {
        final float x;
        final float y;
        final Rect label;

        StackCandidate(float x, float y, Rect label) {
            this.x = x;
            this.y = y;
            this.label = label;
        }
    }

    private static class StackTrack {
        final int id;
        float x;
        float y;
        long count = -1;
        boolean alive = true;
        int missed = 0;

        StackTrack(int id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }
}
