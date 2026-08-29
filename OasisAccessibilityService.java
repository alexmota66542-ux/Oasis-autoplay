package com.oasisautoplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
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
import java.util.Iterator;
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
    public static final String PREF_LAST_CAPTURE_ATTEMPT = "last_capture_attempt_ms";
    public static final String PREF_LAST_CAPTURE_ERROR = "last_capture_error";
    public static final String PREF_ENGINE_STATE = "engine_state";
    public static final String PREF_CAPTURE_COUNT = "capture_count";
    public static final String PREF_TARGET_COUNT = "target_count";
    public static final String PREF_ATTACK_COUNT = "attack_count";
    public static final String PREF_LAST_ERROR = "last_error";
    public static final String PREF_SERVICE_HEARTBEAT = "service_heartbeat_ms";
    public static final String PREF_LAST_CLICK = "last_click";
    public static final String PREF_LAST_TARGET = "last_target";
    public static final String PREF_LAST_CANDIDATE_SCORE = "last_candidate_score";

    private static final long ANALYSIS_INTERVAL_MS = 850;
    private static final long MAP_TAP_COOLDOWN_MS = 1100;
    private static final long MENU_COOLDOWN_MS = 1200;
    private static final long MAGIC_COOLDOWN_MS = 900;
    private static final long ATTACK_COOLDOWN_MS = 1500;
    private static final long VICTORY_COOLDOWN_MS = 1000;
    private static final long UNKNOWN_TIMEOUT_MS = 9000;
    private static final long SEARCH_BLACKLIST_MS = 25000;
    private static final long PACKAGE_EVENT_STALE_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean screenshotBusy = false;
    private volatile boolean ocrBusy = false;
    private volatile boolean destroyed = false;

    private String lastPackage = "";
    private long lastPackageSeenMs = 0;

    private long nextAllowedAnalysisMs = 0;
    private long lastActionMs = 0;
    private long stateSinceMs = 0;
    private long lastProgressMs = 0;

    private TextRecognizer recognizer = null;

    private EngineState state = EngineState.SEARCH_MAP;
    private boolean attackBuffUsed = false;
    private boolean openingSplashDone = false;
    private boolean waitingForScreenChange = false;

    private final Map<Integer, StackTrack> tracks = new HashMap<>();
    private final List<BlacklistPoint> blacklist = new ArrayList<>();

    private enum EngineState {
        SEARCH_MAP,
        TARGET_PENDING,
        TARGET_MENU,
        ENTERING_COMBAT,
        TURN_MENU,
        CAST_ATTACK_BUFF,
        BATTLEFIELD,
        COMPARE_4_5,
        VICTORY,
        REWARD,
        RECOVER
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;

            long now = System.currentTimeMillis();

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putLong(PREF_SERVICE_HEARTBEAT, now)
                    .apply();

            if (isAutoplayEnabled()) {
                if (isForegroundSafeForAutomation(now)) {
                    if (now >= nextAllowedAnalysisMs
                            && !screenshotBusy
                            && !ocrBusy) {
                        analyzeScreen();
                    }
                } else {
                    setVisibleStateOnly("Pausado fora do Oasis");
                }
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
        nextAllowedAnalysisMs = System.currentTimeMillis() + 800;
        lastProgressMs = System.currentTimeMillis();

        setState(EngineState.SEARCH_MAP, "Procurando alvo");

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SERVICE_CONNECTED, true)
                .putLong(PREF_SERVICE_HEARTBEAT, System.currentTimeMillis())
                .apply();

        main.removeCallbacks(loop);
        main.postDelayed(loop, 800);

        Log.i(TAG, "Oasis Autoplay lote mapa+combate iniciado");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        lastPackage = event.getPackageName().toString();
        lastPackageSeenMs = System.currentTimeMillis();
    }

    @Override
    public void onInterrupt() {
        setLastError("Serviço interrompido pelo Android");
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
        if (destroyed || screenshotBusy || ocrBusy) return;

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit()
                .putLong(PREF_LAST_CAPTURE_ATTEMPT, System.currentTimeMillis())
                .apply();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            p.edit()
                    .putString(PREF_LAST_CAPTURE_ERROR, "Android abaixo do 11")
                    .apply();
            return;
        }

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
                                if (buffer == null) {
                                    setCaptureError("HardwareBuffer vazio");
                                    return;
                                }

                                ColorSpace cs = result.getColorSpace();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, cs);
                                if (hw == null) {
                                    setCaptureError("Bitmap de captura indisponível");
                                    return;
                                }

                                soft = hw.copy(Bitmap.Config.ARGB_8888, false);

                                if (soft != null && !destroyed) {
                                    markCaptureSuccess();
                                    detectAndAct(soft);
                                }
                            } catch (Throwable t) {
                                setCaptureError("Erro análise: " + shortError(t));
                                setLastError("Falha ao processar captura");
                                nextAllowedAnalysisMs = System.currentTimeMillis() + 1500;
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
                            setCaptureError("Screenshot falhou: código " + errorCode);
                            nextAllowedAnalysisMs = System.currentTimeMillis() + 1500;
                        }
                    }
            );
        } catch (Throwable t) {
            screenshotBusy = false;
            setCaptureError("Falha ao iniciar captura: " + shortError(t));
            nextAllowedAnalysisMs = System.currentTimeMillis() + 1800;
        }
    }

    private void detectAndAct(Bitmap b) {
        if (destroyed) return;

        long now = System.currentTimeMillis();
        if (now < nextAllowedAnalysisMs) return;

        cleanupBlacklist(now);

        // Estados globais que podem aparecer após uma luta.
        if (detectVictory(b)) {
            setState(EngineState.VICTORY, "Vitória detectada");
            resetBattleTracking();

            if (performAction(
                    "FECHAR_VITORIA",
                    0.25f * b.getWidth(),
                    0.91f * b.getHeight(),
                    VICTORY_COOLDOWN_MS)) {
                return;
            }
        }

        if (detectReward(b)) {
            setState(EngineState.REWARD, "Fechando recompensa");

            if (performAction(
                    "FECHAR_RECOMPENSA",
                    0.50f * b.getWidth(),
                    0.88f * b.getHeight(),
                    VICTORY_COOLDOWN_MS)) {
                return;
            }
        }

        boolean combatContext = isCombatState(state);

        // O menu de alvo só é válido durante busca/validação de alvo.
        if (!combatContext && detectTargetMenu(b)) {
            setState(EngineState.TARGET_MENU, "Alvo confirmado");

            if (performAction(
                    "ATACAR_ALVO",
                    0.25f * b.getWidth(),
                    0.735f * b.getHeight(),
                    MENU_COOLDOWN_MS)) {

                increment(PREF_TARGET_COUNT);
                setState(EngineState.ENTERING_COMBAT, "Entrando em combate");
            }
            return;
        }

        // Durante combate, priorizamos menu de turno e magia antes das tropas.
        if (combatContext && detectMagicScreen(b)) {
            setState(
                    EngineState.CAST_ATTACK_BUFF,
                    attackBuffUsed ? "Buff Attack já aplicado" : "Aplicando Attack"
            );

            if (!attackBuffUsed) {
                castAttackBuff(b);
            } else {
                // Não dispara outra magia. Aguarda a tela mudar.
                nextAllowedAnalysisMs = now + 700;
            }
            return;
        }

        if (combatContext && !attackBuffUsed && detectTurnMenu(b)) {
            setState(EngineState.TURN_MENU, "Abrindo magia");

            performAction(
                    "ABRIR_MAGIA",
                    0.14f * b.getWidth(),
                    0.58f * b.getHeight(),
                    MAGIC_COOLDOWN_MS
            );
            return;
        }

        // Detecta tropas. >=4 também serve para recuperar caso o serviço
        // seja ativado já dentro de uma batalha.
        List<StackCandidate> stacks = findStackCandidates(b);
        boolean strongBattlefield = stacks.size() >= 4;

        if (combatContext || strongBattlefield) {
            if (stacks.size() >= 2) {
                if (!combatContext) {
                    setState(EngineState.BATTLEFIELD, "Batalha detectada");
                    combatContext = true;
                }

                updateTracks(stacks);

                // Nunca inicia os ataques estratégicos antes do único buff.
                if (!attackBuffUsed) {
                    setVisibleStateOnly("Aguardando menu para aplicar Attack");

                    if (now - lastProgressMs > UNKNOWN_TIMEOUT_MS) {
                        setLastError("Buff Attack ainda não aplicado");
                        setState(EngineState.RECOVER, "Recuperando combate");
                    }
                    return;
                }

                setState(EngineState.BATTLEFIELD, "Campo de batalha");

                if (shouldCompareFourAndFive()) {
                    setState(EngineState.COMPARE_4_5, "Comparando Trolls 4 e 5");
                    readFourFiveCountsAndAttack(b, stacks);
                } else {
                    chooseStrategicTargetAndTap(b.getWidth(), b.getHeight());
                }
                return;
            }
        }

        // Validação de um candidato falhou: volta à busca, mantendo a blacklist.
        if (state == EngineState.TARGET_PENDING) {
            setState(EngineState.SEARCH_MAP, "Alvo rejeitado; procurando outro");
            searchMapAndTapCandidate(b);
            return;
        }

        // Telas pós-luta retornam à busca assim que não são mais detectadas.
        if (state == EngineState.VICTORY || state == EngineState.REWARD) {
            setState(EngineState.SEARCH_MAP, "Retornando à busca");
            searchMapAndTapCandidate(b);
            return;
        }

        if (state == EngineState.SEARCH_MAP || state == EngineState.RECOVER) {
            setState(EngineState.SEARCH_MAP, "Procurando Troll no mapa");
            searchMapAndTapCandidate(b);
            return;
        }

        // Timeout para qualquer estado intermediário sem progresso.
        if (now - lastProgressMs > UNKNOWN_TIMEOUT_MS) {
            setLastError("Timeout sem progresso em " + state.name());
            waitingForScreenChange = false;
            tracks.clear();
            setState(EngineState.RECOVER, "Recuperando");
            nextAllowedAnalysisMs = now + 1000;
        }
    }

    private void castAttackBuff(Bitmap b) {
        long now = System.currentTimeMillis();
        final int screenW = b.getWidth();
        final int screenH = b.getHeight();

        float attackIconX = 0.085f * screenW;
        float attackIconY = 0.275f * screenH;

        if (tap(attackIconX, attackIconY)) {
            // Só consideramos o buff concluído depois do toque de confirmação.
            lastActionMs = now;
            markAction(now);
            waitingForScreenChange = true;
            nextAllowedAnalysisMs = now + MAGIC_COOLDOWN_MS;

            main.postDelayed(() -> {
                if (destroyed || !isAutoplayEnabled()) return;

                if (!isForegroundSafeForAutomation(System.currentTimeMillis())) {
                    waitingForScreenChange = false;
                    return;
                }

                boolean confirmed = tap(0.25f * screenW, 0.91f * screenH);

                if (confirmed) {
                    attackBuffUsed = true;
                    markAction(System.currentTimeMillis());
                    setState(EngineState.BATTLEFIELD, "Attack aplicado");
                } else {
                    setLastError("Não foi possível confirmar a magia Attack");
                }

                waitingForScreenChange = false;
                nextAllowedAnalysisMs =
                        System.currentTimeMillis() + MAGIC_COOLDOWN_MS;
            }, 650);
        }
    }

    private void searchMapAndTapCandidate(Bitmap b) {
        if (waitingForScreenChange) return;

        List<MapCandidate> candidates = findMapCandidates(b);

        if (candidates.isEmpty()) {
            setState(EngineState.SEARCH_MAP, "Nenhum candidato visível");
            nextAllowedAnalysisMs = System.currentTimeMillis() + 1200;
            return;
        }

        Collections.sort(candidates, (a, c) ->
                Double.compare(c.score, a.score));

        long now = System.currentTimeMillis();

        for (MapCandidate c : candidates) {
            if (isBlacklisted(c.x, c.y, now)) continue;

            blacklist.add(new BlacklistPoint(c.x, c.y, now + SEARCH_BLACKLIST_MS));

            setState(EngineState.TARGET_PENDING,
                    "Testando alvo em " + Math.round(c.x) + "," + Math.round(c.y));

            saveLastTarget(
                    "Mapa " + Math.round(c.x) + "," + Math.round(c.y),
                    c.score
            );

            performAction("TESTAR_ALVO",
                    c.x, c.y, MAP_TAP_COOLDOWN_MS);
            return;
        }

        setState(EngineState.SEARCH_MAP, "Candidatos já testados");
        nextAllowedAnalysisMs = now + 1200;
    }

    private List<MapCandidate> findMapCandidates(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        // Ignora topo e interface inferior.
        int x0 = (int)(0.12f * w);
        int x1 = (int)(0.88f * w);
        int y0 = (int)(0.20f * h);
        int y1 = (int)(0.64f * h);

        int cell = Math.max(24, w / 22);
        List<MapCandidate> out = new ArrayList<>();

        for (int cy = y0; cy < y1; cy += cell) {
            for (int cx = x0; cx < x1; cx += cell) {
                int rx0 = Math.max(0, cx - cell);
                int rx1 = Math.min(w - 1, cx + cell);
                int ry0 = Math.max(0, cy - cell);
                int ry1 = Math.min(h - 1, cy + cell);

                double dark = localRatio(b, rx0, ry0, rx1, ry1, 5, LocalKind.DARK);
                double warm = localRatio(b, rx0, ry0, rx1, ry1, 5, LocalKind.WARM);
                double green = localRatio(b, rx0, ry0, rx1, ry1, 5, LocalKind.GREEN);
                double edge = localEdgeScore(b, rx0, ry0, rx1, ry1, 6);

                // Candidatos de sprite: contraste/contorno, alguma massa escura/quente,
                // e não uma área quase toda verde (vegetação/chão).
                double centerX = w * 0.50;
                double centerY = h * 0.42;
                double normDist = Math.hypot(cx - centerX, cy - centerY)
                        / Math.hypot(w * 0.50, h * 0.50);
                double centralBonus = Math.max(0.0, 0.18 - normDist * 0.12);

                double score =
                        edge * 2.4
                        + dark * 1.15
                        + warm * 0.85
                        - green * 1.05
                        + centralBonus;

                if (edge > 0.11
                        && dark > 0.030
                        && green < 0.66
                        && score > 0.16) {
                    out.add(new MapCandidate(cx, cy, score));
                }
            }
        }

        // Dedup por proximidade.
        List<MapCandidate> dedup = new ArrayList<>();
        Collections.sort(out, (a, c) -> Double.compare(c.score, a.score));

        for (MapCandidate c : out) {
            boolean near = false;
            for (MapCandidate d : dedup) {
                if (Math.hypot(c.x - d.x, c.y - d.y) < cell * 1.2) {
                    near = true;
                    break;
                }
            }
            if (!near) dedup.add(c);
            if (dedup.size() >= 5) break;
        }

        return dedup;
    }

    private boolean detectVictory(Bitmap b) {
        double tan = ratio(b, 0.08, 0.16, 0.92, 0.80, 24, PixelKind.TAN);
        return tan > 0.46;
    }

    private boolean detectReward(Bitmap b) {
        // Tela de recompensa/baú: painel central claro + moldura escura.
        double centerTan = ratio(b, 0.18, 0.20, 0.82, 0.82, 24, PixelKind.TAN);
        double centerDark = localRatio(
                b,
                (int)(0.12*b.getWidth()),
                (int)(0.16*b.getHeight()),
                (int)(0.88*b.getWidth()),
                (int)(0.88*b.getHeight()),
                20,
                LocalKind.DARK
        );
        return centerTan > 0.24 && centerDark > 0.08 && !detectVictory(b);
    }

    private boolean detectMagicScreen(Bitmap b) {
        double purple =
                ratio(b, 0.05, 0.14, 0.95, 0.83, 26, PixelKind.PURPLE);
        return purple > 0.30;
    }

    private boolean detectTurnMenu(Bitmap b) {
        // Menu lateral/turno: concentração escura/magenta na faixa esquerda,
        // sem o roxo dominante da tela de magias.
        double leftDark = localRatio(
                b, 0, (int)(0.30*b.getHeight()),
                (int)(0.38*b.getWidth()), (int)(0.90*b.getHeight()),
                14, LocalKind.DARK);

        double leftMagenta = localRatio(
                b, 0, (int)(0.30*b.getHeight()),
                (int)(0.42*b.getWidth()), (int)(0.90*b.getHeight()),
                14, LocalKind.MAGENTA);

        return leftDark > 0.18 && leftMagenta > 0.018 && !detectMagicScreen(b);
    }

    private boolean detectTargetMenu(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        int x0 = (int)(0.08f * w);
        int x1 = (int)(0.62f * w);
        int y0 = (int)(0.57f * h);
        int y1 = (int)(0.84f * h);

        double magenta = localRatio(b, x0, y0, x1, y1, 12, LocalKind.MAGENTA);
        double purple = localRatio(b, x0, y0, x1, y1, 12, LocalKind.PURPLE);
        double dark = localRatio(b, x0, y0, x1, y1, 12, LocalKind.DARK);

        return (magenta > 0.015 && purple > 0.045)
                || (purple > 0.075 && dark > 0.10);
    }

    private void resetBattleTracking() {
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
            Bitmap screen, List<StackCandidate> candidates) {

        if (ocrBusy || destroyed) return;

        StackTrack t4 = tracks.get(4);
        StackTrack t5 = tracks.get(5);
        if (t4 == null || t5 == null) return;

        StackCandidate c4 = nearestCandidate(t4, candidates);
        StackCandidate c5 = nearestCandidate(t5, candidates);

        if (c4 == null || c5 == null) {
            chooseStrategicTargetAndTap(screenW, screenH);
            return;
        }

        final int screenW = screen.getWidth();
        final int screenH = screen.getHeight();

        final Bitmap crop4 = cropLabel(screen, c4.label);
        final Bitmap crop5 = cropLabel(screen, c5.label);

        if (crop4 == null || crop5 == null) {
            if (crop4 != null) crop4.recycle();
            if (crop5 != null) crop5.recycle();
            chooseStrategicTargetAndTap(screenW, screenH);
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
                                        setState(EngineState.COMPARE_4_5,
                                                "4=" + n4 + " 5=" + n5 +
                                                " → Troll " + target.id);
                                    } else {
                                        target = t4.y >= t5.y ? t4 : t5;
                                    }

                                    attackTrack(target,
                                            screenW, screenH);
                                })
                                .addOnFailureListener(e -> {
                                    StackTrack target =
                                            t4.y >= t5.y ? t4 : t5;
                                    attackTrack(target,
                                            screenW, screenH);
                                })
                                .addOnCompleteListener(task -> {
                                    try {
                                        if (!crop5.isRecycled()) crop5.recycle();
                                    } catch (Throwable ignored) {}
                                    ocrBusy = false;
                                });
                    })
                    .addOnFailureListener(e -> {
                        try {
                            if (!crop5.isRecycled()) crop5.recycle();
                        } catch (Throwable ignored) {}

                        StackTrack target = t4.y >= t5.y ? t4 : t5;
                        attackTrack(target, screenW, screenH);
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
            setLastError("OCR indisponível: " + shortError(t));

            StackTrack target = t4.y >= t5.y ? t4 : t5;
            attackTrack(target, screenW, screenH);
        }
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
                    float sy = Math.max(0, top - Math.max(28f, bh * 1.55f));

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
                tracks.put(i + 1, new StackTrack(i + 1, c.x, c.y));
            }

            setState(EngineState.BATTLEFIELD, "Trolls 1–5 identificados");
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
                if (t.missed >= 3) t.alive = false;
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
            setState(EngineState.BATTLEFIELD, "Atacando Troll 2");
        }

        if (target == null) {
            StackTrack urgent = null;

            for (int id = 1; id <= 3; id++) {
                StackTrack t = tracks.get(id);
                if (t == null || !t.alive) continue;

                if (urgent == null || t.y > urgent.y) urgent = t;
            }

            if (urgent != null) {
                target = urgent;
                setState(EngineState.BATTLEFIELD,
                        "Prioridade frente: Troll " + target.id);
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

        saveLastTarget(
                "Troll " + target.id + " @ " +
                Math.round(tx) + "," + Math.round(ty),
                target.count >= 0 ? target.count : -1
        );

        if (performAction(
                "ATACAR_TROLL_" + target.id,
                tx, ty, ATTACK_COOLDOWN_MS)) {
            increment(PREF_ATTACK_COUNT);
        }
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

        if (destroyed || !isAutoplayEnabled()) return false;
        if (now < nextAllowedAnalysisMs) return false;
        if (now - lastActionMs < 300) return false;

        boolean ok = tap(x, y);

        if (ok) {
            lastActionMs = now;
            saveLastClick(name, x, y);
            markAction(now);
            nextAllowedAnalysisMs = now + cooldownMs;
            waitingForScreenChange = true;
            lastProgressMs = now;

            main.postDelayed(() -> waitingForScreenChange = false,
                    Math.max(350, cooldownMs - 150));

            Log.i(TAG, name + " -> " + x + "," + y);
        }

        return ok;
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
            setLastError("Falha gesto: " + shortError(t));
            return false;
        }
    }

    private boolean isCombatState(EngineState s) {
        return s == EngineState.ENTERING_COMBAT
                || s == EngineState.TURN_MENU
                || s == EngineState.CAST_ATTACK_BUFF
                || s == EngineState.BATTLEFIELD
                || s == EngineState.COMPARE_4_5;
    }

    private boolean isForegroundSafeForAutomation(long now) {
        if (lastPackage == null || lastPackage.isEmpty()) return true;

        // Eventos antigos não bloqueiam o motor, pois o Oasis pode não emitir
        // eventos de acessibilidade de forma constante.
        if (now - lastPackageSeenMs > PACKAGE_EVENT_STALE_MS) return true;

        if (OASIS_PACKAGE.equals(lastPackage)) return true;

        // Qualquer outro app visto recentemente bloqueia cliques.
        return false;
    }

    private void setVisibleStateOnly(String visible) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_ENGINE_STATE, visible)
                .apply();
    }

    private boolean isAutoplayEnabled() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_AUTOPLAY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private void setState(EngineState newState, String visible) {
        long now = System.currentTimeMillis();

        if (state != newState) {
            state = newState;
            stateSinceMs = now;
            lastProgressMs = now;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_ENGINE_STATE, visible)
                .apply();
    }

    private void saveLastClick(String name, float x, float y) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(
                        PREF_LAST_CLICK,
                        name + " @ " + Math.round(x) + "," + Math.round(y)
                )
                .apply();
    }

    private void saveLastTarget(String label, double score) {
        SharedPreferences.Editor e =
                getSharedPreferences(PREFS, MODE_PRIVATE).edit();

        e.putString(PREF_LAST_TARGET, label);

        if (score >= 0) {
            e.putString(
                    PREF_LAST_CANDIDATE_SCORE,
                    String.format(java.util.Locale.US, "%.3f", score)
            );
        } else {
            e.putString(PREF_LAST_CANDIDATE_SCORE, "-");
        }

        e.apply();
    }

    private void markCaptureSuccess() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long c = p.getLong(PREF_CAPTURE_COUNT, 0);

        p.edit()
                .putLong(PREF_LAST_ANALYSIS, System.currentTimeMillis())
                .putLong(PREF_CAPTURE_COUNT, c + 1)
                .putString(PREF_LAST_CAPTURE_ERROR, "")
                .apply();
    }

    private void markAction(long now) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putLong(PREF_LAST_ACTION, now)
                .apply();
    }

    private void setCaptureError(String error) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_CAPTURE_ERROR, error)
                .apply();
    }

    private void setLastError(String error) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_ERROR, error)
                .apply();
    }

    private void increment(String key) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long n = p.getLong(key, 0);
        p.edit().putLong(key, n + 1).apply();
    }

    private String shortError(Throwable t) {
        if (t == null) return "erro";
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private void cleanupBlacklist(long now) {
        Iterator<BlacklistPoint> it = blacklist.iterator();
        while (it.hasNext()) {
            if (it.next().untilMs <= now) it.remove();
        }
    }

    private boolean isBlacklisted(float x, float y, long now) {
        for (BlacklistPoint p : blacklist) {
            if (p.untilMs > now && Math.hypot(p.x - x, p.y - y) < 90) {
                return true;
            }
        }
        return false;
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

    private Rect grow(Rect r, int w, int h, int gx, int gy) {
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
        int bl = Color.blue(c);
        return r < 55 && g < 55 && bl < 55;
    }

    private enum PixelKind { TAN, PURPLE }
    private enum LocalKind { DARK, WARM, GREEN, MAGENTA, PURPLE }

    private boolean matches(int c, PixelKind kind) {
        int r = Color.red(c);
        int g = Color.green(c);
        int bl = Color.blue(c);

        if (kind == PixelKind.TAN) {
            return r > 175 && g > 135 && g < 220
                    && bl > 90 && bl < 190 && r > g;
        }

        return r > 35 && r < 155
                && bl > 50 && bl < 180
                && bl > g && r > g;
    }

    private boolean localMatches(int c, LocalKind kind) {
        int r = Color.red(c);
        int g = Color.green(c);
        int bl = Color.blue(c);

        switch (kind) {
            case DARK:
                return r < 80 && g < 80 && bl < 80;
            case WARM:
                return r > 105 && g > 55 && g < 150 && bl < 105;
            case GREEN:
                return g > r * 1.05 && g > bl * 1.05 && g > 65;
            case MAGENTA:
                return r > 105 && bl > 70 && g < 105 && r > g + 25;
            case PURPLE:
                return bl > g + 15 && r > g + 10 && r > 45 && bl > 50;
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
        int ex = Math.min(bitmap.getWidth() - 1,
                (int) (x2 * bitmap.getWidth()));
        int ey = Math.min(bitmap.getHeight() - 1,
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

    private double localRatio(
            Bitmap b, int x0, int y0, int x1, int y1,
            int step, LocalKind kind) {

        int hit = 0;
        int total = 0;

        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(b.getWidth() - 1, x1);
        y1 = Math.min(b.getHeight() - 1, y1);

        for (int y = y0; y <= y1; y += step) {
            for (int x = x0; x <= x1; x += step) {
                total++;
                if (localMatches(b.getPixel(x, y), kind)) hit++;
            }
        }

        return total == 0 ? 0 : (double)hit / total;
    }

    private double localEdgeScore(
            Bitmap b, int x0, int y0, int x1, int y1, int step) {

        int strong = 0;
        int total = 0;

        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(b.getWidth() - 2, x1);
        y1 = Math.min(b.getHeight() - 2, y1);

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = b.getPixel(x, y);
                int cx = b.getPixel(x + 1, y);
                int cy = b.getPixel(x, y + 1);

                int d =
                        Math.abs(Color.red(c) - Color.red(cx))
                        + Math.abs(Color.green(c) - Color.green(cx))
                        + Math.abs(Color.blue(c) - Color.blue(cx))
                        + Math.abs(Color.red(c) - Color.red(cy))
                        + Math.abs(Color.green(c) - Color.green(cy))
                        + Math.abs(Color.blue(c) - Color.blue(cy));

                total++;
                if (d > 150) strong++;
            }
        }

        return total == 0 ? 0 : (double)strong / total;
    }

    private static class MapCandidate {
        final float x;
        final float y;
        final double score;

        MapCandidate(float x, float y, double score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    private static class BlacklistPoint {
        final float x;
        final float y;
        final long untilMs;

        BlacklistPoint(float x, float y, long untilMs) {
            this.x = x;
            this.y = y;
            this.untilMs = untilMs;
        }
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
