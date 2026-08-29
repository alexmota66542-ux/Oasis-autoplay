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
    public static final String PREF_LEVELUP_COUNT = "levelup_count";
    public static final String PREF_LEVELUP_STEP = "levelup_step";
    public static final String PREF_SPAWN_COUNT = "spawn_count";
    public static final String PREF_PATROL_MODE = "patrol_mode";
    public static final String PREF_ROUTE_INDEX = "route_index";
    public static final String PREF_ROUTE_STEP = "route_step";
    public static final String PREF_HUNT_ZONE = "hunt_zone";
    public static final String PREF_HUNT_CYCLE = "hunt_cycle";
    public static final String PREF_HUNT_ZONE_COOLDOWNS = "hunt_zone_cooldowns";
    public static final String PREF_MOVE_COUNT = "move_count";
    public static final String PREF_COMBAT_STEP = "combat_step";
    public static final String PREF_TROLL_MATCH = "troll_match";
    public static final String PREF_VISUAL_SCENE = "visual_scene";
    public static final String PREF_VISUAL_CONFIDENCE = "visual_confidence";
    public static final String PREF_FLOW_EXPECTED = "flow_expected";
    public static final String PREF_FLOW_FAILURES = "flow_failures";
    public static final String PREF_MAGIC_SCAN = "magic_scan";
    public static final String PREF_MAGIC_OCR = "magic_ocr";

    private static final long ANALYSIS_INTERVAL_MS = 850;
    private static final long MAP_TAP_COOLDOWN_MS = 1100;
    private static final long MENU_COOLDOWN_MS = 1000;
    private static final long MAGIC_COOLDOWN_MS = 1000;
    private static final long ATTACK_COOLDOWN_MS = 1000;
    private static final long VICTORY_COOLDOWN_MS = 1000;
    private static final long UNKNOWN_TIMEOUT_MS = 9000;
    private static final long SEARCH_BLACKLIST_MS = 25000;
    private static final long PACKAGE_EVENT_STALE_MS = 5000;
    private static final long SPAWN_MIN_RESPAWN_MS = 18000;
    private static final long SPAWN_RECHECK_MS = 1800;
    private static final float SPAWN_RADIUS_NORM = 0.10f;
    private static final int MAX_SPAWN_MEMORY = 8;

    // A área de caça fornecida pelo usuário contém 6 Trolls.
    private static final int HUNT_ZONE_COUNT = 6;
    private static final long HUNT_ZONE_MIN_RESPAWN_MS = 18000L;

    // Posições lógicas no mapa completo de referência.
    // NÃO são coordenadas de toque na tela do jogo.
    private static final float[][] HUNT_ZONE_MAP_ANCHORS = new float[][]{
            {0.412446f, 0.233487f},
            {0.418234f, 0.412442f},
            {0.447178f, 0.514593f},
            {0.179450f, 0.578341f},
            {0.371925f, 0.698925f},
            {0.836469f, 0.600614f}
    };

    // Ciclo espacial baseado no mapa: topo -> centro -> floresta central
    // -> esquerda inferior -> sul -> direita inferior -> topo.
    private static final int[] HUNT_ROUTE_ORDER = new int[]{
            0, 1, 2, 3, 4, 5
    };
    private static final long MOVE_COOLDOWN_MS = 1400;
    private static final long MOVE_VERIFY_MS = 900;
    private static final int MAX_MOVE_RETRIES = 3;
    private static final double TROLL_MATCH_MIN = 0.52;
    private static final int TROLL_SCAN_STEP_DIV = 38;

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
    private boolean magicSpellSelected = false;
    private boolean attackTargetTapped = false;
    private long magicPhaseSinceMs = 0;

    // A posição da magia muda conforme novos drops entram na lista.
    // Portanto, procuramos slot por slot e confirmamos pelo texto da descrição.
    private final List<Float> magicSlotYs = new ArrayList<>();
    private int magicScanIndex = 0;
    private boolean magicCandidateTapped = false;
    private boolean magicDescriptionOcrPending = false;
    private long magicCandidateTappedMs = 0L;
    private static final int MAGIC_MAX_SCAN_SLOTS = 14;
    private boolean openingSplashDone = false;
    private boolean waitingForScreenChange = false;

    private int agilityPointsAdded = 0;
    private long levelPhaseSinceMs = 0;
    private boolean levelAcceptTapped = false;

    private final List<SpawnPoint> spawnMemory = new ArrayList<>();
    private int preferredSpawnIndex = -1;
    private int lastConfirmedSpawnIndex = -1;
    private float pendingTargetNormX = -1f;
    private float pendingTargetNormY = -1f;
    private long lastVictoryMs = 0;

    // Planejador da rota de 6 Trolls.
    private int currentHuntZone = 0;
    private int huntCycle = 0;
    private final long[] huntZoneLastDefeatedMs =
            new long[HUNT_ZONE_COUNT];

    private int routeIndex = 0;
    private int routeStep = 0;
    private int moveRetries = 0;
    private long movePhaseSinceMs = 0;
    private float lastMoveTapX = -1f;
    private float lastMoveTapY = -1f;
    private double lastMoveSceneSignature = -1.0;
    private Bitmap trollReference = null;
    private static final class SceneReference {
        final VisualScene scene;
        final Bitmap bitmap;
        final String source;

        SceneReference(
                VisualScene scene,
                Bitmap bitmap,
                String source) {
            this.scene = scene;
            this.bitmap = bitmap;
            this.source = source;
        }
    }

    // Mini-IA visual local baseada em múltiplos exemplos reais.
    // Não depende de internet nem de posição fixa do terreno.
    private final List<SceneReference> sceneReferences =
            new ArrayList<>();
    private VisualScene lastVisualScene = VisualScene.UNKNOWN;
    private double lastVisualConfidence = 0.0;
    private VisualScene expectedVisualScene = VisualScene.UNKNOWN;
    private int flowFailureCount = 0;
    private long flowStepSinceMs = 0L;
    private static final int FLOW_MAX_RETRIES = 3;
    private static final long FLOW_STEP_WAIT_MS = 1000L;

    private final Map<Integer, StackTrack> tracks = new HashMap<>();
    private final List<BlacklistPoint> blacklist = new ArrayList<>();

    // Rota circular conservadora baseada nos vídeos de caça.
    // São 12 pequenos movimentos agrupados em 6 segmentos (2 por zona).
    // O mapa completo serve apenas para TOPOLOGIA; os toques continuam
    // normalizados na viewport e só são permitidos quando MAP é confirmado.
    private static final float[][] ROUTE_TAPS = new float[][]{
            {0.72f, 0.43f},
            {0.77f, 0.39f},
            {0.73f, 0.34f},
            {0.63f, 0.31f},
            {0.52f, 0.30f},
            {0.41f, 0.33f},
            {0.32f, 0.39f},
            {0.29f, 0.46f},
            {0.34f, 0.53f},
            {0.45f, 0.58f},
            {0.57f, 0.59f},
            {0.67f, 0.54f}
    };

    private enum VisualScene {
        MAP,
        TARGET_MENU,
        BATTLEFIELD,
        TURN_MENU,
        MAGIC,
        VICTORY,
        BATTLEFIELD_AFTER_ACTION,
        UNKNOWN
    }

    private enum EngineState {
        SEARCH_MAP,
        TARGET_PENDING,
        TARGET_MENU,
        ENTERING_COMBAT,
        TURN_MENU,
        CAST_ATTACK_BUFF,
        APPLY_ATTACK_TARGET,
        BATTLEFIELD,
        COMPARE_4_5,
        VICTORY,
        REWARD,
        LEVEL_UP_DETECTED,
        SELECT_AGILITY,
        WAIT_AGILITY_CONTROLS,
        ADD_AGILITY,
        VERIFY_LEVEL_POINTS,
        ACCEPT_LEVEL_UP,
        CONFIRM_LEVEL_UP,
        WAIT_LEVEL_RETURN,
        ROUTE_MOVE,
        ROUTE_VERIFY,
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

        loadTrollReference();
        loadSceneReferences();
        loadSpawnMemory();
        routeIndex = getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).getInt(PREF_ROUTE_INDEX, 0);

        if (routeIndex < 0 || routeIndex >= ROUTE_TAPS.length) {
            routeIndex = 0;
        }

        loadHuntZoneState();

        setPatrolMode("Rota 6 zonas ativa; navegação ainda por passos relativos");

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

        // IA visual básica: classifica a tela antes de qualquer clique.
        VisualScene visualScene = classifyVisualScene(b);
        saveVisualScene(visualScene, lastVisualConfidence);

        // Deve existir antes do gate de fluxo estrito.
        boolean combatContext = isCombatState(state);

        // A tela realmente observada tem prioridade sobre um estado esperado antigo.
        // UNKNOWN ainda recebe uma pequena janela de espera para evitar cliques precipitados,
        // mas o fluxo nunca pode ficar permanentemente preso em uma expectativa obsoleta.
        if (combatContext
                && expectedVisualScene != VisualScene.UNKNOWN
                && !isCompatibleFlowScene(expectedVisualScene, visualScene)) {

            long elapsed = now - flowStepSinceMs;
            boolean observedKnownScene = visualScene != VisualScene.UNKNOWN;

            if (!observedKnownScene && elapsed < FLOW_STEP_WAIT_MS) {
                nextAllowedAnalysisMs = now + 250;
                return;
            }

            if (elapsed >= FLOW_STEP_WAIT_MS) {
                flowFailureCount++;
                saveFlowFailures(flowFailureCount);
                flowStepSinceMs = now;
            }

            if (observedKnownScene || flowFailureCount >= FLOW_MAX_RETRIES) {
                VisualScene previousExpected = expectedVisualScene;
                expectedVisualScene = VisualScene.UNKNOWN;
                flowFailureCount = 0;
                saveFlowExpected(expectedVisualScene);
                saveFlowFailures(0);

                setVisibleStateOnly(
                        "Ressincronizando: esperava " +
                        previousExpected.name() +
                        " / viu " + visualScene.name()
                );
                // Não retorna: a captura atual continua decidindo a próxima ação.
            } else {
                nextAllowedAnalysisMs = now + 300;
                return;
            }
        }

        // Vitória tem prioridade absoluta sobre outras decisões de combate.
        if (visualScene == VisualScene.VICTORY) {
            expectedVisualScene = VisualScene.UNKNOWN;
            flowFailureCount = 0;
            resetMagicScan();
            saveFlowExpected(expectedVisualScene);
            saveFlowFailures(0);

            setState(
                    EngineState.VICTORY,
                    "Vitória reconhecida pela IA visual"
            );

            lastVictoryMs = now;
            markLastConfirmedSpawnDefeated(now);
            markCurrentHuntZoneDefeated(now);
            resetBattleTracking();

            // Confirmado nos vídeos: "Fechar" ocupa a metade esquerda inferior.
            if (performAction(
                    "FECHAR_VITORIA_VISUAL",
                    0.25f * b.getWidth(),
                    0.91f * b.getHeight(),
                    VICTORY_COOLDOWN_MS
            )) {
                routeStep = 0;
                moveRetries = 0;
                movePhaseSinceMs = now;
                setRouteStep("Saindo da vitória visual");
                setState(
                        EngineState.ROUTE_MOVE,
                        "Vitória: seguindo para próximo spawn"
                );
            }
            return;
        }

        // Level-up tem prioridade absoluta sobre caça/combate.
        if (detectLevelUpScreen(b)) {
            handleLevelUp(b, now);
            return;
        }

        // Se já estávamos no fluxo de level-up e a tela principal sumiu,
        // procuramos a confirmação final antes de voltar ao motor normal.
        if (isLevelUpState(state)) {
            if (state == EngineState.CONFIRM_LEVEL_UP
                    || state == EngineState.WAIT_LEVEL_RETURN
                    || levelAcceptTapped) {

                if (detectLevelConfirmDialog(b)) {
                    setState(EngineState.CONFIRM_LEVEL_UP,
                            "Confirmando +3 Agilidade");

                    performAction(
                            "CONFIRMAR_LEVEL_UP",
                            0.50f * b.getWidth(),
                            0.62f * b.getHeight(),
                            1200
                    );

                    setState(EngineState.WAIT_LEVEL_RETURN,
                            "Aguardando retorno do level-up");
                    return;
                }

                if (state == EngineState.WAIT_LEVEL_RETURN
                        && now - levelPhaseSinceMs > 1200) {
                    finishLevelUp();
                    setState(EngineState.SEARCH_MAP,
                            "Level-up concluído; retomando caça");
                    nextAllowedAnalysisMs = now + 900;
                    return;
                }
            }
        }

        // Estados globais que podem aparecer após uma luta.
        if (detectVictory(b)) {
            setState(EngineState.VICTORY, "Vitória detectada");
            lastVictoryMs = now;
            markLastConfirmedSpawnDefeated(now);
            markCurrentHuntZoneDefeated(now);
            resetBattleTracking();

            if (performAction(
                    "FECHAR_VITORIA",
                    0.25f * b.getWidth(),
                    0.91f * b.getHeight(),
                    VICTORY_COOLDOWN_MS)) {

                routeStep = 0;
                moveRetries = 0;
                movePhaseSinceMs = now;
                setRouteStep("Saindo da vitória");
                setState(
                        EngineState.ROUTE_MOVE,
                        "Vitória: seguindo para próximo spawn"
                );
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

        // Recalcula após telas globais que podem ter alterado o estado.
        combatContext = isCombatState(state);

        // A tela observada pode recuperar o fluxo mesmo se um estado antigo ainda
        // estiver marcado como combate. A detecção heurística continua restrita fora
        // do combate para evitar falsos positivos.
        if (visualScene == VisualScene.TARGET_MENU
                || (!combatContext && detectTargetMenu(b))) {
            setState(EngineState.TARGET_MENU, "Alvo confirmado");
            confirmPendingSpawn(b.getWidth(), b.getHeight());

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

        // Fluxo rígido da única magia do combate:
        // menu de turno -> Usar magia -> Arma de Fogo -> Selecionar
        // -> tocar na própria tropa -> confirmar retorno ao campo.
        if (combatContext && !attackBuffUsed
                && (visualScene == VisualScene.MAGIC
                    || detectMagicScreen(b))) {
            setState(
                    EngineState.CAST_ATTACK_BUFF,
                    magicSpellSelected ? "Attack selecionado; aguardando campo"
                            : "Selecionando Attack"
            );

            // A mesma função executa exatamente UM passo por captura:
            // primeiro Arma de Fogo; na captura seguinte, Selecionar.
            castAttackBuff(b);
            return;
        }

        // Depois de Selecionar, o Oasis volta ao campo em modo de alvo da magia.
        // Nesta etapa precisamos tocar na tropa do jogador, não em um Troll.
        if (combatContext && !attackBuffUsed && magicSpellSelected
                && detectSpellTargetMode(b)) {

            setState(EngineState.APPLY_ATTACK_TARGET,
                    attackTargetTapped ? "Attack aplicado; aguardando resolução"
                            : "Aplicando Attack na própria tropa");

            if (!attackTargetTapped) {
                float[] own = findOwnUnitTarget(b);

                if (own != null) {
                    if (performAction(
                            "4_APLICAR_NA_PROPRIA_TROPA",
                            own[0], own[1], 1000)) {
                        attackTargetTapped = true;
                        magicPhaseSinceMs = now;
                    }
                } else {
                    // Coordenada de segurança observada nos vídeos reais.
                    if (performAction(
                            "4_APLICAR_NA_PROPRIA_TROPA_FALLBACK",
                            0.32f * b.getWidth(),
                            0.55f * b.getHeight(),
                            1000)) {
                        attackTargetTapped = true;
                        magicPhaseSinceMs = now;
                    }
                }
            } else {
                nextAllowedAnalysisMs = now + 500;
            }
            return;
        }

        // O modo de alvo desapareceu após tocarmos na própria tropa:
        // só agora consideramos o buff realmente usado.
        if (combatContext && !attackBuffUsed
                && magicSpellSelected
                && attackTargetTapped
                && !detectMagicScreen(b)
                && !detectSpellTargetMode(b)) {

            attackBuffUsed = true;
            resetMagicScan();
            magicSpellSelected = false;
            attackTargetTapped = false;
            magicPhaseSinceMs = 0;
            expectedVisualScene = VisualScene.UNKNOWN;
            flowFailureCount = 0;
            saveFlowExpected(expectedVisualScene);
            saveFlowFailures(0);

            setState(
                    EngineState.BATTLEFIELD,
                    "Buff Attack confirmado; iniciar ataques"
            );
            nextAllowedAnalysisMs = now + 700;
            return;
        }

        if (combatContext && !attackBuffUsed
                && !magicSpellSelected
                && (visualScene == VisualScene.TURN_MENU
                    || detectTurnMenu(b))) {

            setState(EngineState.TURN_MENU, "Abrindo Usar magia");

            // Passo 1: botão "Usar magia" no menu lateral direito.
            // Depois deste toque o motor NÃO tenta nenhuma outra ação
            // até uma nova captura confirmar a tela roxa de magias.
            if (performAction(
                    "1_ABRIR_USAR_MAGIA",
                    0.74f * b.getWidth(),
                    0.60f * b.getHeight(),
                    1000
            )) {
                resetMagicScan();
                expectFlowScene(
                        VisualScene.MAGIC,
                        now
                );
            }
            return;
        }

        // Se a batalha começou mas o menu lateral ainda está fechado,
        // a primeira ação obrigatória é abrir "Ação".
        // Sem isso o bot jamais chega ao botão "Usar magia".
        if (combatContext
                && !attackBuffUsed
                && !magicSpellSelected
                && visualScene == VisualScene.BATTLEFIELD) {

            setVisibleStateOnly(
                    "Batalha reconhecida; abrindo menu Ação"
            );

            if (performAction(
                    "0_ABRIR_MENU_ACAO",
                    0.25f * b.getWidth(),
                    0.91f * b.getHeight(),
                    1000
            )) {
                expectFlowScene(
                        VisualScene.TURN_MENU,
                        now
                );
            }
            return;
        }

        // O clique na tropa NÃO confirma o buff.
        // Ele só é aceito quando a captura seguinte volta ao campo,
        // fora do modo de seleção da magia.
        if (state == EngineState.APPLY_ATTACK_TARGET
                && magicSpellSelected
                && !detectSpellTargetMode(b)
                && (visualScene == VisualScene.BATTLEFIELD
                    || visualScene == VisualScene.BATTLEFIELD_AFTER_ACTION)) {

            attackBuffUsed = true;
            resetMagicScan();
            magicSpellSelected = false;
            expectedVisualScene = VisualScene.UNKNOWN;
            flowFailureCount = 0;
            saveFlowExpected(expectedVisualScene);
            saveFlowFailures(0);

            setState(
                    EngineState.BATTLEFIELD,
                    "Buff confirmado; iniciar ataques"
            );
            nextAllowedAnalysisMs = now + 1000;
            return;
        }

        // Detecta tropas. >=4 também serve para recuperar caso o serviço
        // seja ativado já dentro de uma batalha.
        List<StackCandidate> stacks = findStackCandidates(b);
        boolean strongBattlefield =
                stacks.size() >= 4
                || visualScene == VisualScene.BATTLEFIELD
                || visualScene == VisualScene.TURN_MENU
                || visualScene == VisualScene.MAGIC;

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

        if (state == EngineState.ROUTE_MOVE
                || state == EngineState.ROUTE_VERIFY) {

            // A navegação nunca pode assumir controle se ainda houver
            // qualquer evidência de combate/menu/magia.
            if (visualScene == VisualScene.BATTLEFIELD
                    || visualScene == VisualScene.TURN_MENU
                    || visualScene == VisualScene.MAGIC
                    || detectTurnMenu(b)
                    || detectMagicScreen(b)
                    || detectSpellTargetMode(b)) {
                setState(EngineState.ENTERING_COMBAT,
                        "Combate pendente; rota bloqueada");
                nextAllowedAnalysisMs = now + 1000;
                return;
            }

            handleRouteMovement(b, now);
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

    private void handleLevelUp(Bitmap b, long now) {
        int w = b.getWidth();
        int h = b.getHeight();

        if (!isLevelUpState(state)) {
            agilityPointsAdded = 0;
            levelAcceptTapped = false;
            levelPhaseSinceMs = now;
            setLevelStep("Detectado");
            setState(EngineState.LEVEL_UP_DETECTED,
                    "Level-up: 3 pontos disponíveis");
        }

        // 1) Seleciona Agilidade. Nos vídeos ela fica na terceira linha
        // da lista principal de atributos.
        if (state == EngineState.LEVEL_UP_DETECTED
                || state == EngineState.SELECT_AGILITY) {

            setState(EngineState.SELECT_AGILITY,
                    "Selecionando Agilidade");

            if (performAction(
                    "SELECIONAR_AGILIDADE",
                    0.31f * w,
                    0.48f * h,
                    900)) {
                setLevelStep("Agilidade selecionada");
                setState(EngineState.WAIT_AGILITY_CONTROLS,
                        "Aguardando + / - da Agilidade");
                levelPhaseSinceMs = now;
            }
            return;
        }

        // 2) Só começa a distribuir depois que a região dos controles
        // apresenta o padrão visual esperado.
        if (state == EngineState.WAIT_AGILITY_CONTROLS) {
            if (!detectAgilityControls(b)) {
                if (now - levelPhaseSinceMs > 3500) {
                    setState(EngineState.SELECT_AGILITY,
                            "Repetindo seleção de Agilidade");
                } else {
                    nextAllowedAnalysisMs = now + 450;
                }
                return;
            }

            setState(EngineState.ADD_AGILITY,
                    "Agilidade: 0/3");
            levelPhaseSinceMs = now;
        }

        // 3) Exatamente três cliques, um por captura/cooldown.
        if (state == EngineState.ADD_AGILITY) {
            if (agilityPointsAdded < 3) {
                if (performAction(
                        "AGILIDADE_MAIS_" + (agilityPointsAdded + 1),
                        0.79f * w,
                        0.48f * h,
                        850)) {

                    agilityPointsAdded++;
                    setLevelStep("Agilidade " + agilityPointsAdded + "/3");
                    setVisibleStateOnly(
                            "Level-up: Agilidade " + agilityPointsAdded + "/3");
                }
                return;
            }

            setState(EngineState.VERIFY_LEVEL_POINTS,
                    "Verificando 3/3 Agilidade");
            levelPhaseSinceMs = now;
        }

        // 4) Confirmação visual conservadora. Se o OCR visual não for
        // conclusivo, esperamos; não adicionamos um quarto ponto.
        if (state == EngineState.VERIFY_LEVEL_POINTS) {
            if (!detectNoFreeLevelPoints(b)) {
                if (now - levelPhaseSinceMs < 3000) {
                    nextAllowedAnalysisMs = now + 500;
                    return;
                }
                // O contador interno garante que nunca haverá quarto clique.
                setLastError("Level-up: confirmação visual dos pontos inconclusiva");
            }

            setState(EngineState.ACCEPT_LEVEL_UP,
                    "Aceitando +3 Agilidade");
        }

        // 5) Aceitar uma única vez.
        if (state == EngineState.ACCEPT_LEVEL_UP && !levelAcceptTapped) {
            if (performAction(
                    "ACEITAR_LEVEL_UP",
                    0.50f * w,
                    0.84f * h,
                    1200)) {

                levelAcceptTapped = true;
                levelPhaseSinceMs = now;
                setLevelStep("Aceitar pressionado");
                setState(EngineState.CONFIRM_LEVEL_UP,
                        "Aguardando confirmação do level-up");
            }
            return;
        }

        // Nunca repete os + enquanto espera a confirmação.
        nextAllowedAnalysisMs = now + 500;
    }

    private boolean detectLevelUpScreen(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        // Tela de atributos observada no vídeo: painel predominantemente
        // verde, com linhas claras e controles na metade direita.
        double centerGreen = localRatio(
                b,
                (int)(0.08f*w), (int)(0.12f*h),
                (int)(0.92f*w), (int)(0.88f*h),
                16, LocalKind.GREEN);

        double centerDark = localRatio(
                b,
                (int)(0.08f*w), (int)(0.12f*h),
                (int)(0.92f*w), (int)(0.88f*h),
                16, LocalKind.DARK);

        double purple = localRatio(
                b,
                (int)(0.08f*w), (int)(0.12f*h),
                (int)(0.92f*w), (int)(0.88f*h),
                16, LocalKind.PURPLE);

        return centerGreen > 0.28
                && centerDark > 0.12
                && purple < 0.20
                && detectFreePointsRegion(b);
    }

    private boolean detectFreePointsRegion(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        // A faixa superior/central onde aparece "Exp livre disponível".
        double dark = localRatio(
                b,
                (int)(0.15f*w), (int)(0.18f*h),
                (int)(0.85f*w), (int)(0.34f*h),
                8, LocalKind.DARK);

        double green = localRatio(
                b,
                (int)(0.15f*w), (int)(0.18f*h),
                (int)(0.85f*w), (int)(0.34f*h),
                8, LocalKind.GREEN);

        return green > 0.22 && dark > 0.08;
    }

    private boolean detectAgilityControls(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        double dark = localRatio(
                b,
                (int)(0.68f*w), (int)(0.42f*h),
                (int)(0.90f*w), (int)(0.55f*h),
                5, LocalKind.DARK);

        double green = localRatio(
                b,
                (int)(0.68f*w), (int)(0.42f*h),
                (int)(0.90f*w), (int)(0.55f*h),
                5, LocalKind.GREEN);

        return dark > 0.10 && green > 0.18;
    }

    private boolean detectNoFreeLevelPoints(Bitmap b) {
        // Conservador: depois de três cliques, exige que a tela continue
        // sendo a mesma e os controles ainda estejam presentes.
        // A confirmação semântica forte ficará para OCR em um lote posterior.
        return agilityPointsAdded == 3
                && detectAgilityControls(b)
                && detectFreePointsRegion(b);
    }

    private boolean detectLevelConfirmDialog(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        double centerDark = localRatio(
                b,
                (int)(0.15f*w), (int)(0.30f*h),
                (int)(0.85f*w), (int)(0.72f*h),
                8, LocalKind.DARK);

        double centerGreen = localRatio(
                b,
                (int)(0.15f*w), (int)(0.30f*h),
                (int)(0.85f*w), (int)(0.72f*h),
                8, LocalKind.GREEN);

        return centerDark > 0.20 && centerGreen > 0.16;
    }

    private boolean isLevelUpState(EngineState s) {
        return s == EngineState.LEVEL_UP_DETECTED
                || s == EngineState.SELECT_AGILITY
                || s == EngineState.WAIT_AGILITY_CONTROLS
                || s == EngineState.ADD_AGILITY
                || s == EngineState.VERIFY_LEVEL_POINTS
                || s == EngineState.ACCEPT_LEVEL_UP
                || s == EngineState.CONFIRM_LEVEL_UP
                || s == EngineState.WAIT_LEVEL_RETURN;
    }

    private void setLevelStep(String step) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_LEVELUP_STEP, step)
                .apply();
    }

    private void finishLevelUp() {
        increment(PREF_LEVELUP_COUNT);
        agilityPointsAdded = 0;
        levelAcceptTapped = false;
        levelPhaseSinceMs = 0;
        setLevelStep("Concluído");
    }

    private void castAttackBuff(Bitmap b) {
        long now = System.currentTimeMillis();
        final int w = b.getWidth();
        final int h = b.getHeight();

        // A magia de ataque pode mudar de posição após novos drops.
        // Nunca usamos um slot fixo. Descobrimos os slots preenchidos,
        // selecionamos um por vez e confirmamos pela descrição via OCR.
        if (magicSpellSelected) {
            if (performAction(
                    "3_CONFIRMAR_SELECIONAR",
                    0.25f * w,
                    0.88f * h,
                    1000)) {

                magicPhaseSinceMs = now;
                // Após "Selecionar" a próxima captura pode ser classificada como
                // BATTLEFIELD, BATTLEFIELD_AFTER_ACTION ou modo de alvo. Não imponha
                // uma única cena esperada antes de procurar a própria tropa.
                expectedVisualScene = VisualScene.UNKNOWN;
                flowFailureCount = 0;
                saveFlowExpected(expectedVisualScene);
                saveFlowFailures(0);
                setState(
                        EngineState.APPLY_ATTACK_TARGET,
                        "Magia de ataque confirmada; aguardando alvo"
                );
            }
            return;
        }

        if (magicSlotYs.isEmpty()) {
            magicSlotYs.addAll(findFilledMagicSlots(b));
            magicScanIndex = 0;
            magicCandidateTapped = false;
            magicDescriptionOcrPending = false;

            saveMagicScan(
                    "slots=" + magicSlotYs.size() +
                    " procurando efeito de Attack"
            );

            if (magicSlotYs.isEmpty()) {
                setLastError(
                        "Magia: nenhum slot preenchido reconhecido"
                );
                nextAllowedAnalysisMs = now + 1000;
                return;
            }
        }

        if (magicScanIndex >= magicSlotYs.size()
                || magicScanIndex >= MAGIC_MAX_SCAN_SLOTS) {

            setLastError(
                    "Magia de ataque não encontrada na lista atual"
            );
            saveMagicScan("não encontrada");
            expectedVisualScene = VisualScene.UNKNOWN;
            saveFlowExpected(expectedVisualScene);
            nextAllowedAnalysisMs = now + 1200;
            return;
        }

        // Primeiro toque: seleciona o candidato atual.
        if (!magicCandidateTapped) {
            float yNorm = magicSlotYs.get(magicScanIndex);

            if (performAction(
                    "2_TESTAR_MAGIA_SLOT_" + (magicScanIndex + 1),
                    0.085f * w,
                    yNorm * h,
                    1000)) {

                magicCandidateTapped = true;
                magicDescriptionOcrPending = false;
                magicCandidateTappedMs = now;

                saveMagicScan(
                        "testando slot " + (magicScanIndex + 1) +
                        "/" + magicSlotYs.size()
                );
            }
            return;
        }

        // Dá tempo para o painel de descrição atualizar antes do OCR.
        if (now - magicCandidateTappedMs < 700) {
            nextAllowedAnalysisMs = now + 250;
            return;
        }

        if (!magicDescriptionOcrPending && !ocrBusy) {
            magicDescriptionOcrPending = true;
            readSelectedMagicDescription(
                    b,
                    magicScanIndex
            );
        }

        nextAllowedAnalysisMs = now + 300;
    }

    private List<Float> findFilledMagicSlots(Bitmap b) {
        List<Float> result = new ArrayList<>();

        int w = b.getWidth();
        int h = b.getHeight();

        // Coluna observada da lista de magias.
        int x0 = (int)(0.030f * w);
        int x1 = (int)(0.145f * w);

        // Centros dos slots são regulares, mas a magia desejada não.
        float first = 0.178f;
        float spacing = 0.0475f;

        for (int i = 0; i < MAGIC_MAX_SCAN_SLOTS; i++) {
            float cy = first + i * spacing;
            if (cy > 0.80f) break;

            int y0 = (int)((cy - 0.020f) * h);
            int y1 = (int)((cy + 0.020f) * h);

            double purple = localRatio(
                    b, x0, y0, x1, y1,
                    4, LocalKind.PURPLE
            );

            double dark = localRatio(
                    b, x0, y0, x1, y1,
                    4, LocalKind.DARK
            );

            double bright = localBrightRatio(
                    b, x0, y0, x1, y1, 4
            );

            // Slot vazio = quase todo roxo/escuro.
            // Ícone real tem cores claras, vermelhas, amarelas, verdes etc.
            if (bright > 0.055
                    || (purple < 0.72 && dark < 0.80)) {
                result.add(cy);
            }
        }

        return result;
    }

    private double localBrightRatio(
            Bitmap b,
            int x0, int y0,
            int x1, int y1,
            int step) {

        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(b.getWidth(), x1);
        y1 = Math.min(b.getHeight(), y1);

        int total = 0;
        int bright = 0;

        for (int y = y0; y < y1; y += Math.max(1, step)) {
            for (int x = x0; x < x1; x += Math.max(1, step)) {
                int c = b.getPixel(x, y);
                int r = Color.red(c);
                int g = Color.green(c);
                int bl = Color.blue(c);

                int lum = (r * 3 + g * 5 + bl * 2) / 10;
                if (lum > 115
                        || r > 150
                        || g > 150
                        || bl > 165) {
                    bright++;
                }
                total++;
            }
        }

        return total <= 0 ? 0.0 : (double)bright / total;
    }

    private void readSelectedMagicDescription(
            Bitmap screen,
            final int testedIndex) {

        if (ocrBusy || destroyed) {
            magicDescriptionOcrPending = false;
            return;
        }

        final int sw = screen.getWidth();
        final int sh = screen.getHeight();

        // Apenas a área textual da magia selecionada; não OCR da lista inteira.
        int x = Math.max(0, (int)(0.145f * sw));
        int y = Math.max(0, (int)(0.135f * sh));
        int cw = Math.min(sw - x, (int)(0.82f * sw));
        int ch = Math.min(sh - y, (int)(0.19f * sh));

        if (cw <= 10 || ch <= 10) {
            magicDescriptionOcrPending = false;
            advanceMagicCandidate(
                    testedIndex,
                    "crop inválido"
            );
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(
                    screen, x, y, cw, ch
            );
        } catch (Throwable t) {
            magicDescriptionOcrPending = false;
            advanceMagicCandidate(
                    testedIndex,
                    "falha no crop"
            );
            return;
        }

        ocrBusy = true;

        try {
            if (recognizer == null) {
                recognizer =
                        TextRecognition.getClient(
                                TextRecognizerOptions.DEFAULT_OPTIONS
                        );
            }

            recognizer.process(
                    InputImage.fromBitmap(crop, 0)
            )
            .addOnSuccessListener(text -> {
                String raw = text == null
                        ? ""
                        : text.getText();

                String normalized =
                        normalizeMagicOcr(raw);

                saveMagicOcr(normalized);

                if (isAttackBuffDescription(normalized)) {
                    magicSpellSelected = true;
                    magicCandidateTapped = false;
                    magicDescriptionOcrPending = false;
                    flowFailureCount = 0;

                    setVisibleStateOnly(
                            "Magia de ataque encontrada no slot " +
                            (testedIndex + 1)
                    );

                    saveMagicScan(
                            "Attack encontrado no slot " +
                            (testedIndex + 1)
                    );

                    // Próxima captura apertará "Selecionar".
                    nextAllowedAnalysisMs =
                            System.currentTimeMillis() + 900;
                } else {
                    advanceMagicCandidate(
                            testedIndex,
                            "efeito não é Attack"
                    );
                }
            })
            .addOnFailureListener(e -> {
                saveMagicOcr(
                        "falha OCR: " + shortError(e)
                );
                advanceMagicCandidate(
                        testedIndex,
                        "OCR falhou"
                );
            })
            .addOnCompleteListener(task -> {
                try {
                    if (!crop.isRecycled()) {
                        crop.recycle();
                    }
                } catch (Throwable ignored) {}

                ocrBusy = false;
                magicDescriptionOcrPending = false;
            });

        } catch (Throwable t) {
            try {
                if (!crop.isRecycled()) crop.recycle();
            } catch (Throwable ignored) {}

            ocrBusy = false;
            magicDescriptionOcrPending = false;
            saveMagicOcr(
                    "erro OCR: " + shortError(t)
            );
            advanceMagicCandidate(
                    testedIndex,
                    "OCR indisponível"
            );
        }
    }

    private void advanceMagicCandidate(
            int testedIndex,
            String reason) {

        // Ignora callback antigo se o estado já avançou.
        if (magicSpellSelected
                || testedIndex != magicScanIndex) {
            return;
        }

        magicScanIndex++;
        magicCandidateTapped = false;
        magicDescriptionOcrPending = false;
        magicCandidateTappedMs = 0L;

        saveMagicScan(
                reason + "; próximo slot=" +
                (magicScanIndex + 1)
        );

        nextAllowedAnalysisMs =
                System.currentTimeMillis() + 850;
    }

    private String normalizeMagicOcr(String s) {
        if (s == null) return "";

        String v = s.toLowerCase(
                java.util.Locale.ROOT
        );

        v = java.text.Normalizer.normalize(
                v,
                java.text.Normalizer.Form.NFD
        );

        return v.replaceAll(
                "\\p{M}+",
                ""
        ).replaceAll(
                "\\s+",
                " "
        ).trim();
    }

    private boolean isAttackBuffDescription(
            String normalized) {

        if (normalized == null
                || normalized.isEmpty()) {
            return false;
        }

        // Aceita nomes diferentes e ordem variável dos drops.
        // A confirmação é pelo EFEITO, não pelo nome ou posição.
        boolean hasAttack =
                normalized.contains("ataque")
                || normalized.contains("attack");

        boolean hasIncrease =
                normalized.contains("aument")
                || normalized.contains("forca")
                || normalized.contains("força")
                || normalized.contains("super forca")
                || normalized.contains("bonus");

        boolean hasUnitContext =
                normalized.contains("unidade")
                || normalized.contains("exercito")
                || normalized.contains("tropa")
                || normalized.contains("selecionad");

        // Frase observada: "Aumenta o ataque de uma unidade selecionada..."
        return hasAttack
                && (hasIncrease || hasUnitContext);
    }

    private void resetMagicScan() {
        magicSlotYs.clear();
        magicScanIndex = 0;
        magicCandidateTapped = false;
        magicDescriptionOcrPending = false;
        magicCandidateTappedMs = 0L;
        saveMagicScan("reset");
        saveMagicOcr("");
    }

    private void saveMagicScan(String value) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(
                        PREF_MAGIC_SCAN,
                        value == null ? "" : value
                )
                .apply();
    }

    private void saveMagicOcr(String value) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(
                        PREF_MAGIC_OCR,
                        value == null ? "" : value
                )
                .apply();
    }

    private void searchMapAndTapCandidate(Bitmap b) {
        if (waitingForScreenChange) return;

        long now = System.currentTimeMillis();

        // 1) Prioridade: pontos de spawn já confirmados em combate.
        MapCandidate spawnCandidate = findCandidateNearKnownSpawn(b, now);
        if (spawnCandidate != null) {
            rememberPendingTarget(spawnCandidate, b.getWidth(), b.getHeight());

            setState(
                    EngineState.TARGET_PENDING,
                    "Verificando spawn conhecido"
            );

            saveLastTarget(
                    "Spawn conhecido " +
                    Math.round(spawnCandidate.x) + "," +
                    Math.round(spawnCandidate.y),
                    spawnCandidate.score
            );

            performAction(
                    "TESTAR_SPAWN_CONHECIDO",
                    spawnCandidate.x,
                    spawnCandidate.y,
                    MAP_TAP_COOLDOWN_MS
            );
            return;
        }

        // 2) Referência visual do Troll real fornecida pelo usuário.
        // É a primeira busca fora dos spawns conhecidos.
        MapCandidate visualTroll = findTrollByReference(b);

        if (visualTroll != null
                && !isBlacklisted(visualTroll.x, visualTroll.y, now)) {

            blacklist.add(
                    new BlacklistPoint(
                            visualTroll.x,
                            visualTroll.y,
                            now + SEARCH_BLACKLIST_MS
                    )
            );

            rememberPendingTarget(
                    visualTroll,
                    b.getWidth(),
                    b.getHeight()
            );

            saveTrollMatch(visualTroll.score);
            setPatrolMode("Troll por referência visual");
            setState(
                    EngineState.TARGET_PENDING,
                    "Troll visual encontrado"
            );

            saveLastTarget(
                    "Troll visual " +
                    Math.round(visualTroll.x) + "," +
                    Math.round(visualTroll.y),
                    visualTroll.score
            );

            performAction(
                    "CLICAR_TROLL_VISUAL",
                    visualTroll.x,
                    visualTroll.y,
                    1000
            );
            return;
        }

        // 3) Detector especializado em Trolls de pedra.
        List<MapCandidate> trollCandidates = findStoneTrollCandidates(b);

        Collections.sort(
                trollCandidates,
                (a, c) -> Double.compare(c.score, a.score)
        );

        for (MapCandidate c : trollCandidates) {
            if (isBlacklisted(c.x, c.y, now)) continue;

            blacklist.add(
                    new BlacklistPoint(
                            c.x,
                            c.y,
                            now + SEARCH_BLACKLIST_MS
                    )
            );

            rememberPendingTarget(c, b.getWidth(), b.getHeight());
            setPatrolMode("Caça por Troll");

            setState(
                    EngineState.TARGET_PENDING,
                    "Testando Troll provável"
            );

            saveLastTarget(
                    "Troll provável " +
                    Math.round(c.x) + "," +
                    Math.round(c.y),
                    c.score
            );

            performAction(
                    "TESTAR_TROLL_PROVAVEL",
                    c.x,
                    c.y,
                    MAP_TAP_COOLDOWN_MS
            );
            return;
        }

        // 4) Fallback conservador para não perder alvos que o detector
        // especializado ainda não reconhece.
        List<MapCandidate> candidates = findMapCandidates(b);

        Collections.sort(
                candidates,
                (a, c) -> Double.compare(c.score, a.score)
        );

        for (MapCandidate c : candidates) {
            if (isBlacklisted(c.x, c.y, now)) continue;

            blacklist.add(
                    new BlacklistPoint(
                            c.x,
                            c.y,
                            now + SEARCH_BLACKLIST_MS
                    )
            );

            rememberPendingTarget(c, b.getWidth(), b.getHeight());
            setPatrolMode("Busca ampla");

            setState(
                    EngineState.TARGET_PENDING,
                    "Testando alvo alternativo"
            );

            saveLastTarget(
                    "Mapa " +
                    Math.round(c.x) + "," +
                    Math.round(c.y),
                    c.score
            );

            performAction(
                    "TESTAR_ALVO",
                    c.x,
                    c.y,
                    MAP_TAP_COOLDOWN_MS
            );
            return;
        }

        pendingTargetNormX = -1f;
        pendingTargetNormY = -1f;

        // Nunca fica parado esperando o mesmo respawn.
        // Se a região atual está vazia, avança a rota.
        setPatrolMode(
                spawnMemory.isEmpty()
                        ? "Explorando rota"
                        : "Região vazia; próximo ponto"
        );

        routeStep = 0;
        moveRetries = 0;
        movePhaseSinceMs = now;

        setRouteStep("Avançando rota");
        setState(
                EngineState.ROUTE_MOVE,
                "Sem Troll aqui; seguindo rota"
        );

        nextAllowedAnalysisMs = now + 400;
    }

    private void handleRouteMovement(Bitmap b, long now) {
        // A rota aceita MAP confirmado e também UNKNOWN quando não existe nenhuma
        // evidência de menu, magia, vitória ou campo de batalha. Isso evita ficar
        // parado indefinidamente em um mapa real classificado como UNKNOWN.
        VisualScene routeScene = classifyVisualScene(b);

        boolean blockingUi = detectTargetMenu(b)
                || detectTurnMenu(b)
                || detectMagicScreen(b)
                || detectSpellTargetMode(b)
                || detectVictory(b)
                || detectReward(b)
                || findStackCandidates(b).size() >= 2;

        boolean mapAccepted = routeScene == VisualScene.MAP
                || (routeScene == VisualScene.UNKNOWN && !blockingUi);

        if (!mapAccepted
                || (routeScene == VisualScene.MAP
                    && lastVisualConfidence < 0.50)) {

            setRouteStep(
                    "Rota aguardando mapa seguro; " +
                    routeScene.name() + " " +
                    String.format(
                            java.util.Locale.US,
                            "%.2f",
                            lastVisualConfidence
                    )
            );

            nextAllowedAnalysisMs = now + 550;
            return;
        }

        // Mantém o índice físico alinhado com a zona lógica atual.
        int desiredSegmentStart =
                (currentHuntZone % HUNT_ZONE_COUNT) * 2;

        if (routeIndex < desiredSegmentStart
                || routeIndex > desiredSegmentStart + 1) {
            routeIndex = desiredSegmentStart;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_ROUTE_INDEX, routeIndex)
                    .apply();
        }

        int w = b.getWidth();
        int h = b.getHeight();

        // Segurança: se apareceu uma tela de alvo durante o deslocamento,
        // o fluxo normal assumirá no próximo ciclo.
        if (detectTargetMenu(b)) {
            setState(
                    EngineState.SEARCH_MAP,
                    "Alvo encontrado durante rota"
            );
            nextAllowedAnalysisMs = now + 250;
            return;
        }

        // Antes de qualquer novo movimento, procura primeiro
        // o Troll pela referência visual real.
        MapCandidate visualImmediate =
                findTrollByReference(b);

        if (visualImmediate != null) {
            setState(
                    EngineState.SEARCH_MAP,
                    "Troll visual encontrado na rota"
            );
            nextAllowedAnalysisMs = now + 250;
            return;
        }

        List<MapCandidate> immediate =
                findStoneTrollCandidates(b);

        if (!immediate.isEmpty()) {
            setState(
                    EngineState.SEARCH_MAP,
                    "Troll encontrado na rota"
            );
            nextAllowedAnalysisMs = now + 250;
            return;
        }

        if (state == EngineState.ROUTE_VERIFY) {
            if (now - movePhaseSinceMs < MOVE_VERIFY_MS) {
                nextAllowedAnalysisMs = now + 300;
                return;
            }

            double sig = sceneSignature(b);

            boolean changed =
                    lastMoveSceneSignature < 0
                    || Math.abs(sig - lastMoveSceneSignature) > 0.018;

            if (changed) {
                moveRetries = 0;
                advanceRouteIndex();
                setRouteStep(
                        "Movimento confirmado; ponto " +
                        (routeIndex + 1)
                );

                setState(
                        EngineState.SEARCH_MAP,
                        "Cheguei ao próximo ponto; procurando Troll"
                );

                nextAllowedAnalysisMs = now + 450;
                return;
            }

            moveRetries++;

            if (moveRetries >= MAX_MOVE_RETRIES) {
                // Não insistimos indefinidamente no mesmo clique.
                advanceRouteIndex();
                moveRetries = 0;

                setLastError(
                        "Rota: movimento sem confirmação; pulando waypoint"
                );

                setState(
                        EngineState.SEARCH_MAP,
                        "Waypoint pulado; procurando Troll"
                );

                nextAllowedAnalysisMs = now + 450;
                return;
            }

            setState(
                    EngineState.ROUTE_MOVE,
                    "Repetindo movimento da rota"
            );

            nextAllowedAnalysisMs = now + 350;
            return;
        }

        // ROUTE_MOVE
        float nx = ROUTE_TAPS[routeIndex][0];
        float ny = ROUTE_TAPS[routeIndex][1];

        float x = nx * w;
        float y = ny * h;

        lastMoveSceneSignature = sceneSignature(b);
        lastMoveTapX = x;
        lastMoveTapY = y;

        if (performAction(
                "MOVER_ROTA_" + (routeIndex + 1),
                x,
                y,
                MOVE_COOLDOWN_MS)) {

            increment(PREF_MOVE_COUNT);
            movePhaseSinceMs = now;
            setRouteStep(
                    "Movendo para ponto " +
                    (routeIndex + 1)
            );

            setState(
                    EngineState.ROUTE_VERIFY,
                    "Verificando deslocamento"
            );
        }
    }

    private void advanceRouteIndex() {
        routeIndex++;

        int segmentEnd =
                (currentHuntZone % HUNT_ZONE_COUNT) * 2 + 1;

        if (routeIndex > segmentEnd
                || routeIndex >= ROUTE_TAPS.length) {

            selectNextReadyHuntZone(
                    System.currentTimeMillis()
            );

            routeIndex =
                    (currentHuntZone % HUNT_ZONE_COUNT) * 2;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_ROUTE_INDEX, routeIndex)
                .putInt(PREF_HUNT_ZONE, currentHuntZone)
                .putInt(PREF_HUNT_CYCLE, huntCycle)
                .apply();
    }

    private void selectNextReadyHuntZone(long now) {
        int currentPos = 0;

        for (int i = 0; i < HUNT_ROUTE_ORDER.length; i++) {
            if (HUNT_ROUTE_ORDER[i] == currentHuntZone) {
                currentPos = i;
                break;
            }
        }

        int fallback = HUNT_ROUTE_ORDER[
                (currentPos + 1) % HUNT_ROUTE_ORDER.length
        ];

        for (int step = 1;
             step <= HUNT_ROUTE_ORDER.length;
             step++) {

            int pos =
                    (currentPos + step)
                    % HUNT_ROUTE_ORDER.length;

            int candidate =
                    HUNT_ROUTE_ORDER[pos];

            long defeated =
                    huntZoneLastDefeatedMs[candidate];

            boolean ready =
                    defeated <= 0L
                    || now - defeated
                    >= HUNT_ZONE_MIN_RESPAWN_MS;

            if (ready) {
                if (pos <= currentPos) {
                    huntCycle++;
                }

                currentHuntZone = candidate;
                saveHuntZoneState();

                setPatrolMode(
                        "Rota 6 Trolls: zona " +
                        (currentHuntZone + 1) +
                        "/6"
                );
                return;
            }
        }

        // Se todos ainda estiverem em cooldown, continua circulando;
        // nunca para esperando exclusivamente um único spawn.
        currentHuntZone = fallback;
        if (currentHuntZone == HUNT_ROUTE_ORDER[0]) {
            huntCycle++;
        }

        saveHuntZoneState();

        setPatrolMode(
                "6 zonas em cooldown; circulando para zona " +
                (currentHuntZone + 1)
        );
    }

    private void markCurrentHuntZoneDefeated(long now) {
        if (currentHuntZone < 0
                || currentHuntZone >= HUNT_ZONE_COUNT) {
            return;
        }

        huntZoneLastDefeatedMs[currentHuntZone] = now;

        setPatrolMode(
                "Troll da zona " +
                (currentHuntZone + 1) +
                "/6 eliminado"
        );

        selectNextReadyHuntZone(now);

        routeIndex =
                (currentHuntZone % HUNT_ZONE_COUNT) * 2;

        saveHuntZoneState();
    }

    private void saveHuntZoneState() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0;
             i < huntZoneLastDefeatedMs.length;
             i++) {

            if (i > 0) sb.append(",");
            sb.append(huntZoneLastDefeatedMs[i]);
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_HUNT_ZONE, currentHuntZone)
                .putInt(PREF_HUNT_CYCLE, huntCycle)
                .putString(
                        PREF_HUNT_ZONE_COOLDOWNS,
                        sb.toString()
                )
                .apply();
    }

    private void loadHuntZoneState() {
        android.content.SharedPreferences p =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        currentHuntZone = p.getInt(
                PREF_HUNT_ZONE,
                0
        );

        huntCycle = p.getInt(
                PREF_HUNT_CYCLE,
                0
        );

        if (currentHuntZone < 0
                || currentHuntZone >= HUNT_ZONE_COUNT) {
            currentHuntZone = 0;
        }

        String raw = p.getString(
                PREF_HUNT_ZONE_COOLDOWNS,
                ""
        );

        if (raw == null || raw.trim().isEmpty()) {
            return;
        }

        try {
            String[] values = raw.split(",");

            for (int i = 0;
                 i < values.length
                 && i < HUNT_ZONE_COUNT;
                 i++) {

                huntZoneLastDefeatedMs[i] =
                        Long.parseLong(values[i]);
            }
        } catch (Throwable ignored) {
            java.util.Arrays.fill(
                    huntZoneLastDefeatedMs,
                    0L
            );
        }
    }

    private double sceneSignature(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        int x0 = (int)(0.10f * w);
        int x1 = (int)(0.90f * w);
        int y0 = (int)(0.15f * h);
        int y1 = (int)(0.67f * h);

        long sum = 0;
        long sum2 = 0;
        int n = 0;

        int sx = Math.max(18, w / 32);
        int sy = Math.max(18, h / 54);

        for (int y = y0; y < y1; y += sy) {
            for (int x = x0; x < x1; x += sx) {
                int c = b.getPixel(x, y);

                int r = Color.red(c);
                int g = Color.green(c);
                int bl = Color.blue(c);

                int lum = (r * 3 + g * 5 + bl * 2) / 10;

                sum += lum;
                sum2 += (long)lum * lum;
                n++;
            }
        }

        if (n == 0) return 0.0;

        double mean = sum / (double)n;
        double variance =
                sum2 / (double)n - mean * mean;

        // assinatura compacta 0..1-ish
        return (mean / 255.0)
                + Math.max(0.0, variance) / 65025.0 * 0.35;
    }

    private void setRouteStep(String step) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_ROUTE_STEP, step)
                .apply();
    }

    private MapCandidate findCandidateNearKnownSpawn(Bitmap b, long now) {
        if (spawnMemory.isEmpty()) return null;

        List<MapCandidate> trolls = findStoneTrollCandidates(b);
        if (trolls.isEmpty()) return null;

        int w = b.getWidth();
        int h = b.getHeight();

        MapCandidate best = null;
        double bestScore = -999;

        for (int i = 0; i < spawnMemory.size(); i++) {
            SpawnPoint sp = spawnMemory.get(i);

            // Logo após uma morte, não martela o mesmo ponto.
            if (sp.lastDefeatedMs > 0
                    && now - sp.lastDefeatedMs < SPAWN_MIN_RESPAWN_MS) {
                continue;
            }

            float sx = sp.nx * w;
            float sy = sp.ny * h;
            float radius = SPAWN_RADIUS_NORM *
                    (float)Math.hypot(w, h);

            for (MapCandidate c : trolls) {
                double d = Math.hypot(c.x - sx, c.y - sy);
                if (d > radius) continue;

                double proximity =
                        1.0 - Math.min(1.0, d / radius);

                double score =
                        c.score
                        + proximity * 0.85
                        + Math.min(0.40, sp.confirmations * 0.08);

                if (i == preferredSpawnIndex) {
                    score += 0.18;
                }

                if (score > bestScore) {
                    bestScore = score;
                    best = new MapCandidate(c.x, c.y, score);
                    preferredSpawnIndex = i;
                }
            }
        }

        return best;
    }

    private boolean isCompatibleFlowScene(
            VisualScene expected,
            VisualScene actual) {

        if (expected == VisualScene.UNKNOWN || expected == actual) {
            return true;
        }

        if (expected == VisualScene.BATTLEFIELD_AFTER_ACTION
                && actual == VisualScene.BATTLEFIELD) {
            return true;
        }

        if (expected == VisualScene.BATTLEFIELD
                && actual == VisualScene.BATTLEFIELD_AFTER_ACTION) {
            return true;
        }

        return false;
    }

    private void expectFlowScene(
            VisualScene scene,
            long now) {

        expectedVisualScene = scene;
        flowFailureCount = 0;
        flowStepSinceMs = now;
        saveFlowExpected(scene);
        saveFlowFailures(0);
    }

    private void saveFlowExpected(VisualScene scene) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(
                        PREF_FLOW_EXPECTED,
                        scene == null ? "UNKNOWN" : scene.name()
                )
                .apply();
    }

    private void saveFlowFailures(int count) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_FLOW_FAILURES, count)
                .apply();
    }

    private void loadSceneReferences() {
        sceneReferences.clear();

        // Dataset principal: vários exemplos por classe, extraídos
        // de batalhas reais gravadas pelo usuário.
        loadSceneReference(VisualScene.MAP, "scene_map_9715_a.png");
        loadSceneReference(VisualScene.MAP, "scene_map_9715_b.png");
        loadSceneReference(VisualScene.MAP, "scene_map_9682_a.png");

        loadSceneReference(
                VisualScene.BATTLEFIELD,
                "scene_battlefield_9715_a.png"
        );
        loadSceneReference(
                VisualScene.BATTLEFIELD,
                "scene_battlefield_9715_b.png"
        );
        loadSceneReference(
                VisualScene.BATTLEFIELD,
                "scene_battlefield_9682_a.png"
        );

        loadSceneReference(
                VisualScene.TURN_MENU,
                "scene_turn_menu_9715_a.png"
        );
        loadSceneReference(
                VisualScene.TURN_MENU,
                "scene_turn_menu_9682_a.png"
        );

        loadSceneReference(
                VisualScene.MAGIC,
                "scene_magic_9682_a.png"
        );

        loadSceneReference(
                VisualScene.VICTORY,
                "scene_victory_9715_a.png"
        );
        loadSceneReference(
                VisualScene.VICTORY,
                "scene_victory_9715_b.png"
        );
        loadSceneReference(
                VisualScene.VICTORY,
                "scene_victory_9682_a.png"
        );

        loadSceneReference(
                VisualScene.BATTLEFIELD_AFTER_ACTION,
                "scene_battlefield_after_action_9682_a.png"
        );

        // Mantém as referências antigas como exemplos adicionais.
        loadSceneReference(VisualScene.MAP, "scene_map.png");
        loadSceneReference(
                VisualScene.TARGET_MENU,
                "scene_target_menu.png"
        );
        loadSceneReference(
                VisualScene.BATTLEFIELD,
                "scene_battlefield.png"
        );
        loadSceneReference(
                VisualScene.TURN_MENU,
                "scene_turn_menu.png"
        );
        loadSceneReference(
                VisualScene.MAGIC,
                "scene_magic.png"
        );
        loadSceneReference(
                VisualScene.VICTORY,
                "scene_victory.png"
        );
        loadSceneReference(
                VisualScene.BATTLEFIELD_AFTER_ACTION,
                "scene_battlefield_after_action.png"
        );
    }

    private void loadSceneReference(
            VisualScene scene,
            String assetName) {

        try {
            Bitmap ref = android.graphics.BitmapFactory.decodeStream(
                    getAssets().open(assetName)
            );

            if (ref != null) {
                sceneReferences.add(
                        new SceneReference(
                                scene,
                                ref,
                                assetName
                        )
                );
            }
        } catch (Throwable t) {
            // Alguns assets adicionais são opcionais em builds antigos.
            // A IA continua funcionando com os exemplos disponíveis.
        }
    }

    private VisualScene classifyVisualScene(Bitmap screen) {
        if (sceneReferences.isEmpty()) {
            lastVisualConfidence = 0.0;
            return VisualScene.UNKNOWN;
        }

        java.util.EnumMap<VisualScene, List<Double>> perClass =
                new java.util.EnumMap<>(VisualScene.class);

        for (SceneReference ref : sceneReferences) {
            if (ref == null
                    || ref.bitmap == null
                    || ref.bitmap.isRecycled()) {
                continue;
            }

            double score = sceneSimilarity(
                    screen,
                    ref.bitmap
            );

            List<Double> values = perClass.get(ref.scene);
            if (values == null) {
                values = new ArrayList<>();
                perClass.put(ref.scene, values);
            }
            values.add(score);
        }

        VisualScene bestScene = VisualScene.UNKNOWN;
        double bestClassScore = -1.0;
        double secondClassScore = -1.0;

        for (java.util.Map.Entry<VisualScene, List<Double>> e :
                perClass.entrySet()) {

            List<Double> scores = e.getValue();
            if (scores == null || scores.isEmpty()) {
                continue;
            }

            java.util.Collections.sort(
                    scores,
                    java.util.Collections.reverseOrder()
            );

            // k-NN/prototype ensemble:
            // melhor exemplo pesa mais, segundo melhor estabiliza
            // contra variações de terreno, drops e animações.
            double s1 = scores.get(0);
            double s2 = scores.size() > 1
                    ? scores.get(1)
                    : s1;

            double classScore =
                    s1 * 0.68 +
                    s2 * 0.32;

            if (classScore > bestClassScore) {
                secondClassScore = bestClassScore;
                bestClassScore = classScore;
                bestScene = e.getKey();
            } else if (classScore > secondClassScore) {
                secondClassScore = classScore;
            }
        }

        double margin =
                bestClassScore -
                Math.max(0.0, secondClassScore);

        double confidence =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                bestClassScore * 0.80
                                + margin * 0.75
                        )
                );

        lastVisualConfidence = confidence;

        // Em dúvida, não clica.
        if (bestClassScore < 0.52
                || confidence < 0.47
                || margin < 0.014) {
            return VisualScene.UNKNOWN;
        }

        return bestScene;
    }

    private double sceneSimilarity(
            Bitmap screen,
            Bitmap ref) {

        int rw = ref.getWidth();
        int rh = ref.getHeight();

        double weightedColor = 0.0;
        double weightedEdge = 0.0;
        double weightSum = 0.0;

        // A IA aprende a interface, não apenas o terreno:
        // topo + rodapé + lado direito têm peso maior.
        for (int gy = 1; gy < 24; gy++) {
            for (int gx = 1; gx < 14; gx++) {

                float nx = gx / 14.0f;
                float ny = gy / 24.0f;

                int x = Math.min(
                        screen.getWidth() - 1,
                        Math.max(
                                0,
                                (int)(nx * screen.getWidth())
                        )
                );

                int y = Math.min(
                        screen.getHeight() - 1,
                        Math.max(
                                0,
                                (int)(ny * screen.getHeight())
                        )
                );

                int rx = Math.min(
                        rw - 1,
                        Math.max(0, (int)(nx * rw))
                );

                int ry = Math.min(
                        rh - 1,
                        Math.max(0, (int)(ny * rh))
                );

                double weight = 1.0;

                // Barra superior/status do combate.
                if (ny < 0.12f) {
                    weight = 2.2;
                }

                // Controles inferiores Ação/Menu/Selecionar/Fechar.
                if (ny > 0.82f) {
                    weight = 2.8;
                }

                // Menu lateral direito.
                if (nx > 0.50f && ny > 0.24f) {
                    weight = Math.max(weight, 2.4);
                }

                // Centro do terreno recebe menos peso.
                if (nx > 0.18f
                        && nx < 0.78f
                        && ny > 0.18f
                        && ny < 0.75f) {
                    weight *= 0.72;
                }

                int a = screen.getPixel(x, y);
                int b = ref.getPixel(rx, ry);

                int ar = Color.red(a);
                int ag = Color.green(a);
                int ab = Color.blue(a);

                int br = Color.red(b);
                int bg = Color.green(b);
                int bb = Color.blue(b);

                double diff =
                        Math.abs(ar - br)
                        + Math.abs(ag - bg)
                        + Math.abs(ab - bb);

                double color =
                        1.0 - Math.min(
                                1.0,
                                diff / 500.0
                        );

                int x2 = Math.min(
                        screen.getWidth() - 1,
                        x + Math.max(
                                1,
                                screen.getWidth()/120
                        )
                );

                int rx2 = Math.min(
                        rw - 1,
                        rx + 1
                );

                int a2 = screen.getPixel(x2, y);
                int b2 = ref.getPixel(rx2, ry);

                int al =
                        (Color.red(a)*3
                        + Color.green(a)*5
                        + Color.blue(a)*2)/10;
                int al2 =
                        (Color.red(a2)*3
                        + Color.green(a2)*5
                        + Color.blue(a2)*2)/10;

                int bl =
                        (Color.red(b)*3
                        + Color.green(b)*5
                        + Color.blue(b)*2)/10;
                int bl2 =
                        (Color.red(b2)*3
                        + Color.green(b2)*5
                        + Color.blue(b2)*2)/10;

                boolean ae =
                        Math.abs(al-al2) > 20;
                boolean be =
                        Math.abs(bl-bl2) > 20;

                double edge = ae == be
                        ? 1.0
                        : 0.0;

                weightedColor += color * weight;
                weightedEdge += edge * weight;
                weightSum += weight;
            }
        }

        if (weightSum <= 0.0) {
            return 0.0;
        }

        double colorScore =
                weightedColor / weightSum;
        double edgeScore =
                weightedEdge / weightSum;

        return colorScore * 0.74
                + edgeScore * 0.26;
    }

    private void saveVisualScene(
            VisualScene scene,
            double confidence) {

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(
                        PREF_VISUAL_SCENE,
                        scene == null
                                ? "UNKNOWN"
                                : scene.name()
                )
                .putString(
                        PREF_VISUAL_CONFIDENCE,
                        String.format(
                                java.util.Locale.US,
                                "%.3f",
                                confidence
                        )
                )
                .apply();
    }

    private void loadTrollReference() {
        try {
            trollReference = android.graphics.BitmapFactory.decodeStream(
                    getAssets().open("troll_reference.png")
            );
        } catch (Throwable t) {
            trollReference = null;
            setLastError(
                    "Referência do Troll não carregada: " +
                    t.getClass().getSimpleName()
            );
        }
    }

    private MapCandidate findTrollByReference(Bitmap screen) {
        if (trollReference == null
                || trollReference.isRecycled()) {
            return null;
        }

        int w = screen.getWidth();
        int h = screen.getHeight();

        // Somente área do mapa. Nunca varre Ação/Menu/barras.
        int x0 = (int)(0.05f * w);
        int x1 = (int)(0.95f * w);
        int y0 = (int)(0.10f * h);
        int y1 = (int)(0.72f * h);

        int tw = trollReference.getWidth();
        int th = trollReference.getHeight();

        // O sprite pode variar um pouco de escala conforme captura/aparelho.
        float[] scales = new float[]{0.82f, 1.00f, 1.18f};

        MapCandidate best = null;
        double bestScore = -1.0;

        for (float scale : scales) {
            int sw = Math.max(28, Math.round(tw * scale));
            int sh = Math.max(34, Math.round(th * scale));

            int step = Math.max(
                    8,
                    w / TROLL_SCAN_STEP_DIV
            );

            for (int cy = y0 + sh/2;
                    cy <= y1 - sh/2;
                    cy += step) {

                for (int cx = x0 + sw/2;
                        cx <= x1 - sw/2;
                        cx += step) {

                    double score = trollReferenceScore(
                            screen,
                            cx - sw/2,
                            cy - sh/2,
                            sw,
                            sh
                    );

                    if (score > bestScore) {
                        bestScore = score;
                        best = new MapCandidate(
                                cx,
                                cy,
                                score
                        );
                    }
                }
            }
        }

        saveTrollMatch(bestScore);

        if (best == null
                || bestScore < TROLL_MATCH_MIN) {
            return null;
        }

        return best;
    }

    private double trollReferenceScore(
            Bitmap screen,
            int sx,
            int sy,
            int sw,
            int sh) {

        int rw = trollReference.getWidth();
        int rh = trollReference.getHeight();

        double colorSimilarity = 0.0;
        double edgeAgreement = 0.0;
        double stoneBonus = 0.0;
        int n = 0;

        // Amostragem compacta: não cria Bitmaps temporários.
        for (int ry = 4; ry < rh - 4; ry += 5) {
            for (int rx = 4; rx < rw - 4; rx += 5) {
                int x = sx + rx * sw / rw;
                int y = sy + ry * sh / rh;

                if (x < 1 || y < 1
                        || x >= screen.getWidth()-1
                        || y >= screen.getHeight()-1) {
                    continue;
                }

                int ref = trollReference.getPixel(rx, ry);
                int cur = screen.getPixel(x, y);

                int rr = Color.red(ref);
                int rg = Color.green(ref);
                int rb = Color.blue(ref);

                int cr = Color.red(cur);
                int cg = Color.green(cur);
                int cb = Color.blue(cur);

                double diff =
                        Math.abs(rr-cr)
                        + Math.abs(rg-cg)
                        + Math.abs(rb-cb);

                colorSimilarity +=
                        1.0 - Math.min(1.0, diff / 420.0);

                int refLum = (rr*3 + rg*5 + rb*2)/10;
                int curLum = (cr*3 + cg*5 + cb*2)/10;

                int ref2 = trollReference.getPixel(
                        Math.min(rw-1, rx+1),
                        ry
                );
                int cur2 = screen.getPixel(
                        Math.min(screen.getWidth()-1, x+1),
                        y
                );

                int refLum2 =
                        (Color.red(ref2)*3
                        + Color.green(ref2)*5
                        + Color.blue(ref2)*2)/10;

                int curLum2 =
                        (Color.red(cur2)*3
                        + Color.green(cur2)*5
                        + Color.blue(cur2)*2)/10;

                boolean re = Math.abs(refLum-refLum2) > 18;
                boolean ce = Math.abs(curLum-curLum2) > 18;

                if (re == ce) edgeAgreement += 1.0;

                int max = Math.max(cr, Math.max(cg, cb));
                int min = Math.min(cr, Math.min(cg, cb));
                int avg = (cr+cg+cb)/3;

                if (avg >= 62 && avg <= 205
                        && max-min <= 48) {
                    stoneBonus += 1.0;
                }

                n++;
            }
        }

        if (n == 0) return 0.0;

        colorSimilarity /= n;
        edgeAgreement /= n;
        stoneBonus /= n;

        // Referência é dominante, mas pedra/contorno ajudam contra árvores.
        return colorSimilarity * 0.62
                + edgeAgreement * 0.23
                + stoneBonus * 0.15;
    }

    private void saveTrollMatch(double score) {
        String value = score < 0
                ? "-"
                : String.format(
                        java.util.Locale.US,
                        "%.3f",
                        score
                );

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_TROLL_MATCH, value)
                .apply();
    }

    private List<MapCandidate> findStoneTrollCandidates(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        // ROI real do mapa, excluindo barra superior e os botões inferiores.
        int x0 = (int)(0.07f * w);
        int x1 = (int)(0.93f * w);
        int y0 = (int)(0.12f * h);
        int y1 = (int)(0.69f * h);

        int cell = Math.max(22, w / 26);
        List<MapCandidate> out = new ArrayList<>();

        for (int cy = y0; cy < y1; cy += cell) {
            for (int cx = x0; cx < x1; cx += cell) {
                int rx0 = Math.max(0, cx - cell);
                int rx1 = Math.min(w - 1, cx + cell);
                int ry0 = Math.max(0, cy - cell);
                int ry1 = Math.min(h - 1, cy + cell);

                double gray = localGrayStoneRatio(
                        b, rx0, ry0, rx1, ry1, 4);
                double edge = localEdgeScore(
                        b, rx0, ry0, rx1, ry1, 5);
                double green = localRatio(
                        b, rx0, ry0, rx1, ry1, 5, LocalKind.GREEN);
                double dark = localRatio(
                        b, rx0, ry0, rx1, ry1, 5, LocalKind.DARK);
                double warm = localRatio(
                        b, rx0, ry0, rx1, ry1, 5, LocalKind.WARM);

                // Trolls do vídeo são sprites compactos, cinza/pedra,
                // com muito contorno e pouca massa quente.
                double score =
                        gray * 3.4
                        + edge * 2.0
                        + dark * 0.45
                        - green * 0.55
                        - warm * 0.55;

                if (gray > 0.10
                        && edge > 0.085
                        && warm < 0.22
                        && score > 0.34) {
                    out.add(new MapCandidate(cx, cy, score));
                }
            }
        }

        Collections.sort(
                out,
                (a, c) -> Double.compare(c.score, a.score)
        );

        List<MapCandidate> dedup = new ArrayList<>();

        for (MapCandidate c : out) {
            boolean near = false;

            for (MapCandidate d : dedup) {
                if (Math.hypot(c.x - d.x, c.y - d.y)
                        < cell * 1.45) {
                    near = true;
                    break;
                }
            }

            if (!near) dedup.add(c);
            if (dedup.size() >= 8) break;
        }

        return dedup;
    }

    private double localGrayStoneRatio(
            Bitmap b,
            int x0, int y0, int x1, int y1,
            int step) {

        int total = 0;
        int stone = 0;

        for (int y = y0; y <= y1; y += step) {
            for (int x = x0; x <= x1; x += step) {
                int color = b.getPixel(x, y);

                int r = Color.red(color);
                int g = Color.green(color);
                int bl = Color.blue(color);

                int max = Math.max(r, Math.max(g, bl));
                int min = Math.min(r, Math.min(g, bl));
                int spread = max - min;
                int avg = (r + g + bl) / 3;

                total++;

                // Pedra/cinza observada nos Trolls do vídeo.
                if (avg >= 62
                        && avg <= 205
                        && spread <= 48
                        && Math.abs(g - r) <= 38
                        && Math.abs(g - bl) <= 38) {
                    stone++;
                }
            }
        }

        return total == 0 ? 0.0 : (double)stone / total;
    }

    private void rememberPendingTarget(
            MapCandidate c,
            int w,
            int h) {

        pendingTargetNormX =
                Math.max(0f, Math.min(1f, c.x / (float)w));
        pendingTargetNormY =
                Math.max(0f, Math.min(1f, c.y / (float)h));
    }

    private void confirmPendingSpawn(int w, int h) {
        if (pendingTargetNormX < 0
                || pendingTargetNormY < 0) {
            return;
        }

        int nearest = -1;
        double nearestD = Double.MAX_VALUE;

        for (int i = 0; i < spawnMemory.size(); i++) {
            SpawnPoint sp = spawnMemory.get(i);

            double d = Math.hypot(
                    sp.nx - pendingTargetNormX,
                    sp.ny - pendingTargetNormY
            );

            if (d < nearestD) {
                nearestD = d;
                nearest = i;
            }
        }

        if (nearest >= 0 && nearestD <= SPAWN_RADIUS_NORM) {
            SpawnPoint sp = spawnMemory.get(nearest);

            // Suaviza pequenas diferenças de posição do respawn.
            sp.nx = sp.nx * 0.72f
                    + pendingTargetNormX * 0.28f;
            sp.ny = sp.ny * 0.72f
                    + pendingTargetNormY * 0.28f;
            sp.confirmations++;
            sp.lastSeenMs = System.currentTimeMillis();

            lastConfirmedSpawnIndex = nearest;
            preferredSpawnIndex = nearest;
        } else {
            if (spawnMemory.size() >= MAX_SPAWN_MEMORY) {
                removeWeakestSpawn();
            }

            SpawnPoint sp = new SpawnPoint(
                    pendingTargetNormX,
                    pendingTargetNormY
            );

            sp.confirmations = 1;
            sp.lastSeenMs = System.currentTimeMillis();

            spawnMemory.add(sp);
            lastConfirmedSpawnIndex = spawnMemory.size() - 1;
            preferredSpawnIndex = lastConfirmedSpawnIndex;
        }

        saveSpawnMemory();
        setPatrolMode(
                "Spawn confirmado " +
                (lastConfirmedSpawnIndex + 1)
        );

        pendingTargetNormX = -1f;
        pendingTargetNormY = -1f;
    }

    private void markLastConfirmedSpawnDefeated(long now) {
        if (lastConfirmedSpawnIndex < 0
                || lastConfirmedSpawnIndex >= spawnMemory.size()) {
            return;
        }

        SpawnPoint sp =
                spawnMemory.get(lastConfirmedSpawnIndex);

        sp.lastDefeatedMs = now;
        preferredSpawnIndex =
                (lastConfirmedSpawnIndex + 1)
                % Math.max(1, spawnMemory.size());

        saveSpawnMemory();
        setPatrolMode(
                "Spawn " +
                (lastConfirmedSpawnIndex + 1) +
                " aguardando respawn"
        );
    }

    private void removeWeakestSpawn() {
        if (spawnMemory.isEmpty()) return;

        int weakest = 0;

        for (int i = 1; i < spawnMemory.size(); i++) {
            SpawnPoint a = spawnMemory.get(i);
            SpawnPoint b = spawnMemory.get(weakest);

            if (a.confirmations < b.confirmations
                    || (a.confirmations == b.confirmations
                    && a.lastSeenMs < b.lastSeenMs)) {
                weakest = i;
            }
        }

        spawnMemory.remove(weakest);

        if (lastConfirmedSpawnIndex == weakest) {
            lastConfirmedSpawnIndex = -1;
        } else if (lastConfirmedSpawnIndex > weakest) {
            lastConfirmedSpawnIndex--;
        }

        if (preferredSpawnIndex >= spawnMemory.size()) {
            preferredSpawnIndex =
                    spawnMemory.isEmpty() ? -1 : 0;
        }
    }

    private void saveSpawnMemory() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < spawnMemory.size(); i++) {
            SpawnPoint sp = spawnMemory.get(i);

            if (i > 0) sb.append(";");

            sb.append(sp.nx).append(",")
                    .append(sp.ny).append(",")
                    .append(sp.confirmations).append(",")
                    .append(sp.lastSeenMs).append(",")
                    .append(sp.lastDefeatedMs);
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("spawn_memory_data", sb.toString())
                .putInt(PREF_SPAWN_COUNT, spawnMemory.size())
                .apply();
    }

    private void loadSpawnMemory() {
        spawnMemory.clear();

        String raw = getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).getString("spawn_memory_data", "");

        if (raw == null || raw.trim().isEmpty()) {
            return;
        }

        try {
            String[] entries = raw.split(";");

            for (String entry : entries) {
                String[] p = entry.split(",");
                if (p.length < 5) continue;

                SpawnPoint sp = new SpawnPoint(
                        Float.parseFloat(p[0]),
                        Float.parseFloat(p[1])
                );

                sp.confirmations = Integer.parseInt(p[2]);
                sp.lastSeenMs = Long.parseLong(p[3]);
                sp.lastDefeatedMs = Long.parseLong(p[4]);

                spawnMemory.add(sp);

                if (spawnMemory.size() >= MAX_SPAWN_MEMORY) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            spawnMemory.clear();
        }
    }

    private void setPatrolMode(String mode) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_PATROL_MODE, mode)
                .apply();
    }

    private List<MapCandidate> findMapCandidates(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        // Ignora topo e interface inferior.
        int x0 = (int)(0.14f * w);
        int x1 = (int)(0.86f * w);
        int y0 = (int)(0.19f * h);
        int y1 = (int)(0.58f * h);

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
        // Nos vídeos reais o menu de turno ocupa a METADE DIREITA:
        // Doação, Fugir, Auto, Mensagem, Usar magia, Informação,
        // Terminar turno, Próximo e Fechar.
        int w = b.getWidth();
        int h = b.getHeight();

        double rightDark = localRatio(
                b,
                (int)(0.48f*w), (int)(0.28f*h),
                (int)(0.98f*w), (int)(0.93f*h),
                14, LocalKind.DARK);

        double rightPurple = localRatio(
                b,
                (int)(0.48f*w), (int)(0.28f*h),
                (int)(0.98f*w), (int)(0.93f*h),
                14, LocalKind.PURPLE);

        double leftGreen = localRatio(
                b,
                (int)(0.03f*w), (int)(0.18f*h),
                (int)(0.47f*w), (int)(0.78f*h),
                16, LocalKind.GREEN);

        return rightDark > 0.34
                && rightPurple > 0.18
                && leftGreen > 0.18
                && !detectMagicScreen(b);
    }

    private boolean detectSpellTargetMode(Bitmap b) {
        if (detectMagicScreen(b) || detectTurnMenu(b)) return false;

        int w = b.getWidth();
        int h = b.getHeight();

        // Campo verde + dois botões roxos "Magia/Cancelar" no canto inferior esquerdo.
        double fieldGreen = localRatio(
                b,
                (int)(0.08f*w), (int)(0.18f*h),
                (int)(0.88f*w), (int)(0.72f*h),
                18, LocalKind.GREEN);

        double lowerLeftDark = localRatio(
                b,
                0, (int)(0.73f*h),
                (int)(0.50f*w), (int)(0.92f*h),
                12, LocalKind.DARK);

        double lowerLeftPurple = localRatio(
                b,
                0, (int)(0.73f*h),
                (int)(0.50f*w), (int)(0.92f*h),
                12, LocalKind.PURPLE);

        return fieldGreen > 0.24
                && lowerLeftDark > 0.24
                && lowerLeftPurple > 0.10;
    }

    private float[] findOwnUnitTarget(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();

        // A tropa do jogador aparece na metade inferior/esquerda do campo.
        int x0 = (int)(0.16f*w);
        int x1 = (int)(0.48f*w);
        int y0 = (int)(0.42f*h);
        int y1 = (int)(0.68f*h);

        int step = Math.max(18, w / 28);

        double bestScore = -1;
        float bestX = -1;
        float bestY = -1;

        for (int cy = y0; cy <= y1; cy += step) {
            for (int cx = x0; cx <= x1; cx += step) {
                int rx0 = Math.max(0, cx - step);
                int rx1 = Math.min(w - 1, cx + step);
                int ry0 = Math.max(0, cy - step);
                int ry1 = Math.min(h - 1, cy + step);

                double warm = localRatio(
                        b, rx0, ry0, rx1, ry1, 5, LocalKind.WARM);
                double dark = localRatio(
                        b, rx0, ry0, rx1, ry1, 5, LocalKind.DARK);
                double green = localRatio(
                        b, rx0, ry0, rx1, ry1, 5, LocalKind.GREEN);
                double edge = localEdgeScore(
                        b, rx0, ry0, rx1, ry1, 6);

                double score =
                        warm * 3.0
                        + dark * 0.8
                        + edge * 1.5
                        - green * 0.55;

                if (warm > 0.035
                        && edge > 0.07
                        && score > bestScore) {
                    bestScore = score;
                    bestX = cx;
                    bestY = cy;
                }
            }
        }

        if (bestScore < 0.16) return null;
        return new float[]{bestX, bestY};
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
        magicSpellSelected = false;
        attackTargetTapped = false;
        magicPhaseSinceMs = 0;
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

        final int screenW = screen.getWidth();
        final int screenH = screen.getHeight();

        StackCandidate c4 = nearestCandidate(t4, candidates);
        StackCandidate c5 = nearestCandidate(t5, candidates);

        if (c4 == null || c5 == null) {
            chooseStrategicTargetAndTap(screenW, screenH);
            return;
        }

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

            if (name.startsWith("1_")
                    || name.startsWith("2_")
                    || name.startsWith("3_")
                    || name.startsWith("4_")) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_COMBAT_STEP, name)
                        .apply();
            }

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
                || s == EngineState.APPLY_ATTACK_TARGET
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

    private static class SpawnPoint {
        float nx;
        float ny;
        int confirmations = 0;
        long lastSeenMs = 0;
        long lastDefeatedMs = 0;

        SpawnPoint(float nx, float ny) {
            this.nx = nx;
            this.ny = ny;
        }
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
