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
    public static final String PREF_MOVE_COUNT = "move_count";

    private static final long ANALYSIS_INTERVAL_MS = 850;
    private static final long MAP_TAP_COOLDOWN_MS = 1100;
    private static final long MENU_COOLDOWN_MS = 1200;
    private static final long MAGIC_COOLDOWN_MS = 900;
    private static final long ATTACK_COOLDOWN_MS = 1500;
    private static final long VICTORY_COOLDOWN_MS = 1000;
    private static final long UNKNOWN_TIMEOUT_MS = 9000;
    private static final long SEARCH_BLACKLIST_MS = 25000;
    private static final long PACKAGE_EVENT_STALE_MS = 5000;
    private static final long SPAWN_MIN_RESPAWN_MS = 18000;
    private static final long SPAWN_RECHECK_MS = 1800;
    private static final float SPAWN_RADIUS_NORM = 0.10f;
    private static final int MAX_SPAWN_MEMORY = 8;
    private static final long MOVE_COOLDOWN_MS = 1400;
    private static final long MOVE_VERIFY_MS = 900;
    private static final int MAX_MOVE_RETRIES = 3;

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

    private int routeIndex = 0;
    private int routeStep = 0;
    private int moveRetries = 0;
    private long movePhaseSinceMs = 0;
    private float lastMoveTapX = -1f;
    private float lastMoveTapY = -1f;
    private double lastMoveSceneSignature = -1.0;

    private final Map<Integer, StackTrack> tracks = new HashMap<>();
    private final List<BlacklistPoint> blacklist = new ArrayList<>();

    // Rota circular conservadora baseada no vídeo de caça.
    // Cada bloco representa uma pequena caminhada entre regiões de spawn.
    // Os valores são coordenadas NORMALIZADAS da área jogável, não do mapa absoluto.
    // Isso evita depender de uma posição mundial fixa.
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

        loadSpawnMemory();
        routeIndex = getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).getInt(PREF_ROUTE_INDEX, 0);

        if (routeIndex < 0 || routeIndex >= ROUTE_TAPS.length) {
            routeIndex = 0;
        }

        setPatrolMode("Rota circular ativa");

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

        boolean combatContext = isCombatState(state);

        // O menu de alvo só é válido durante busca/validação de alvo.
        if (!combatContext && detectTargetMenu(b)) {
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
        if (combatContext && !attackBuffUsed && detectMagicScreen(b)) {
            setState(
                    EngineState.CAST_ATTACK_BUFF,
                    magicSpellSelected ? "Attack selecionado; aguardando campo"
                            : "Selecionando Attack"
            );

            if (!magicSpellSelected) {
                castAttackBuff(b);
            } else {
                // A tela ainda não fechou. Não repete a magia.
                if (magicPhaseSinceMs > 0 && now - magicPhaseSinceMs > 3500) {
                    // Uma única tentativa extra no botão Selecionar.
                    performAction(
                            "CONFIRMAR_ATTACK",
                            0.25f * b.getWidth(),
                            0.88f * b.getHeight(),
                            1200
                    );
                    magicPhaseSinceMs = now;
                } else {
                    nextAllowedAnalysisMs = now + 500;
                }
            }
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
                            "ALVO_ATTACK_PROPRIA_TROPA",
                            own[0], own[1], 1500)) {
                        attackTargetTapped = true;
                        magicPhaseSinceMs = now;
                    }
                } else {
                    // Coordenada de segurança observada nos vídeos reais.
                    if (performAction(
                            "ALVO_ATTACK_PROPRIA_TROPA_FALLBACK",
                            0.32f * b.getWidth(),
                            0.55f * b.getHeight(),
                            1500)) {
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
            magicSpellSelected = false;
            attackTargetTapped = false;
            magicPhaseSinceMs = 0;
            setState(EngineState.BATTLEFIELD, "Attack confirmado");
            nextAllowedAnalysisMs = now + 500;
        }

        if (combatContext && !attackBuffUsed
                && !magicSpellSelected
                && detectTurnMenu(b)) {

            setState(EngineState.TURN_MENU, "Abrindo Usar magia");

            performAction(
                    "ABRIR_MAGIA",
                    0.74f * b.getWidth(),
                    0.60f * b.getHeight(),
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

        if (state == EngineState.ROUTE_MOVE
                || state == EngineState.ROUTE_VERIFY) {
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
        final int screenW = b.getWidth();
        final int screenH = b.getHeight();

        // Arma de Fogo: terceiro ícone da coluna de magias.
        float attackIconX = 0.085f * screenW;
        float attackIconY = 0.275f * screenH;

        if (performAction(
                "SELECIONAR_ARMA_DE_FOGO",
                attackIconX,
                attackIconY,
                650)) {

            magicPhaseSinceMs = now;

            main.postDelayed(() -> {
                if (destroyed || !isAutoplayEnabled()) return;
                if (!isForegroundSafeForAutomation(System.currentTimeMillis())) return;

                boolean selected = performAction(
                        "CONFIRMAR_ARMA_DE_FOGO",
                        0.25f * screenW,
                        0.88f * screenH,
                        1100
                );

                if (selected) {
                    magicSpellSelected = true;
                    magicPhaseSinceMs = System.currentTimeMillis();
                    setState(
                            EngineState.APPLY_ATTACK_TARGET,
                            "Attack selecionado; escolhendo própria tropa"
                    );
                } else {
                    setLastError("Falha ao confirmar Arma de Fogo");
                }
            }, 500);
        }
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

        // 2) Detector especializado em Trolls de pedra.
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

        // 3) Fallback conservador para não perder alvos que o detector
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

        // Antes de qualquer novo movimento, verifica se há Troll perto.
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

        if (routeIndex >= ROUTE_TAPS.length) {
            routeIndex = 0;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_ROUTE_INDEX, routeIndex)
                .apply();
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
