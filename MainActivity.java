package com.oasisautoplay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView versionView;
    private TextView accessibilityStatus;
    private TextView serviceStatus;
    private TextView heartbeatStatus;
    private TextView autoplayStatus;
    private TextView engineStatus;
    private TextView captureAttemptStatus;
    private TextView captureErrorStatus;
    private TextView analysisStatus;
    private TextView actionStatus;
    private TextView countersStatus;
    private TextView lastClickStatus;
    private TextView lastTargetStatus;
    private TextView levelUpStatus;
    private TextView patrolStatus;
    private TextView routeStatus;
    private TextView combatStepStatus;
    private TextView trollMatchStatus;
    private TextView visualAiStatus;
    private TextView flowStatus;
    private TextView magicScanStatus;
    private TextView huntRouteStatus;
    private TextView lastErrorStatus;
    private Button toggleButton;

    private SharedPreferences prefs;

    private final Runnable statusLoop = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(OasisAccessibilityService.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(42, 48, 42, 48);
        root.setBackgroundColor(Color.rgb(20, 24, 30));

        TextView title = text("Oasis Autoplay", 30f, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        versionView = text("", 15f, Color.LTGRAY);
        versionView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vParams = matchWrap();
        vParams.setMargins(0, 10, 0, 28);
        root.addView(versionView, vParams);

        TextView panelTitle = text("STATUS DO MOTOR", 17f, Color.WHITE);
        panelTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pt = matchWrap();
        pt.setMargins(0, 0, 0, 14);
        root.addView(panelTitle, pt);

        accessibilityStatus = statusLine();
        serviceStatus = statusLine();
        heartbeatStatus = statusLine();
        autoplayStatus = statusLine();
        engineStatus = statusLine();
        captureAttemptStatus = statusLine();
        captureErrorStatus = statusLine();
        analysisStatus = statusLine();
        actionStatus = statusLine();
        countersStatus = statusLine();
        lastClickStatus = statusLine();
        lastTargetStatus = statusLine();
        levelUpStatus = statusLine();
        patrolStatus = statusLine();
        routeStatus = statusLine();
        combatStepStatus = statusLine();
        trollMatchStatus = statusLine();
        visualAiStatus = statusLine();
        flowStatus = statusLine();
        magicScanStatus = statusLine();
        huntRouteStatus = statusLine();
        lastErrorStatus = statusLine();

        root.addView(accessibilityStatus, matchWrap());
        root.addView(serviceStatus, matchWrap());
        root.addView(heartbeatStatus, matchWrap());
        root.addView(autoplayStatus, matchWrap());
        root.addView(engineStatus, matchWrap());
        root.addView(captureAttemptStatus, matchWrap());
        root.addView(captureErrorStatus, matchWrap());
        root.addView(analysisStatus, matchWrap());
        root.addView(actionStatus, matchWrap());
        root.addView(countersStatus, matchWrap());
        root.addView(lastClickStatus, matchWrap());
        root.addView(lastTargetStatus, matchWrap());
        root.addView(levelUpStatus, matchWrap());
        root.addView(patrolStatus, matchWrap());
        root.addView(routeStatus, matchWrap());
        root.addView(combatStepStatus, matchWrap());
        root.addView(trollMatchStatus, matchWrap());
        root.addView(visualAiStatus, matchWrap());
        root.addView(flowStatus, matchWrap());
        root.addView(magicScanStatus, matchWrap());
        root.addView(huntRouteStatus, matchWrap());
        root.addView(lastErrorStatus, matchWrap());

        toggleButton = new Button(this);
        toggleButton.setText("ATIVAR AUTOPLAY");
        toggleButton.setOnClickListener(v -> toggleAutoplay());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, 28, 0, 10);
        root.addView(toggleButton, buttonParams);

        Button settingsButton = new Button(this);
        settingsButton.setText("CONFIGURAÇÕES DE ACESSIBILIDADE");
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams settingsParams = matchWrap();
        settingsParams.setMargins(0, 0, 0, 10);
        root.addView(settingsButton, settingsParams);

        Button oasisButton = new Button(this);
        oasisButton.setText("ABRIR OASIS");
        oasisButton.setOnClickListener(v -> openOasis());
        root.addView(oasisButton, matchWrap());

        TextView hint = text(
                "Fluxo atual: procurar alvo → confirmar menu → atacar → " +
                "usar Attack → Troll 2 → sobreviventes 1–3 → comparar 4/5 → " +
                "vitória/recompensa → voltar à busca.",
                14f,
                Color.LTGRAY
        );
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.setMargins(0, 24, 0, 0);
        root.addView(hint, hintParams);

        scroll.addView(root);
        setContentView(scroll);

        loadVersionName();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusLoop);
        handler.post(statusLoop);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusLoop);
        super.onPause();
    }

    private TextView statusLine() {
        TextView tv = text("", 16f, Color.LTGRAY);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 6, 0, 6);
        return tv;
    }

    private TextView text(String value, float size, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(size);
        tv.setTextColor(color);
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void loadVersionName() {
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            versionView.setText("Versão " + version);
        } catch (Throwable t) {
            versionView.setText("Versão atual");
        }
    }

    private void toggleAutoplay() {
        boolean accessibility = isAccessibilityServiceEnabled();

        if (!accessibility) {
            prefs.edit()
                    .putBoolean(OasisAccessibilityService.PREF_AUTOPLAY_ENABLED, false)
                    .apply();

            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        boolean enabled = prefs.getBoolean(
                OasisAccessibilityService.PREF_AUTOPLAY_ENABLED,
                false
        );

        prefs.edit()
                .putBoolean(
                        OasisAccessibilityService.PREF_AUTOPLAY_ENABLED,
                        !enabled
                )
                .apply();

        refreshStatus();
    }

    private void refreshStatus() {
        boolean accessibility = isAccessibilityServiceEnabled();

        boolean serviceConnected = prefs.getBoolean(
                OasisAccessibilityService.PREF_SERVICE_CONNECTED,
                false
        );

        long heartbeat = prefs.getLong(
                OasisAccessibilityService.PREF_SERVICE_HEARTBEAT,
                0
        );

        boolean heartbeatFresh =
                heartbeat > 0 &&
                (System.currentTimeMillis() - heartbeat) < 5000;

        boolean enabled = prefs.getBoolean(
                OasisAccessibilityService.PREF_AUTOPLAY_ENABLED,
                false
        );

        String engine = prefs.getString(
                OasisAccessibilityService.PREF_ENGINE_STATE,
                "Aguardando"
        );

        long captureAttempt = prefs.getLong(
                OasisAccessibilityService.PREF_LAST_CAPTURE_ATTEMPT,
                0
        );

        String captureError = prefs.getString(
                OasisAccessibilityService.PREF_LAST_CAPTURE_ERROR,
                ""
        );

        long analysis = prefs.getLong(
                OasisAccessibilityService.PREF_LAST_ANALYSIS,
                0
        );

        long action = prefs.getLong(
                OasisAccessibilityService.PREF_LAST_ACTION,
                0
        );

        long captures = prefs.getLong(
                OasisAccessibilityService.PREF_CAPTURE_COUNT,
                0
        );

        long targets = prefs.getLong(
                OasisAccessibilityService.PREF_TARGET_COUNT,
                0
        );

        long attacks = prefs.getLong(
                OasisAccessibilityService.PREF_ATTACK_COUNT,
                0
        );

        String lastClick = prefs.getString(
                OasisAccessibilityService.PREF_LAST_CLICK,
                "nenhum"
        );

        String lastTarget = prefs.getString(
                OasisAccessibilityService.PREF_LAST_TARGET,
                "nenhum"
        );

        String candidateScore = prefs.getString(
                OasisAccessibilityService.PREF_LAST_CANDIDATE_SCORE,
                "-"
        );

        int levelUps = prefs.getInt(
                OasisAccessibilityService.PREF_LEVELUP_COUNT,
                0
        );

        String levelStep = prefs.getString(
                OasisAccessibilityService.PREF_LEVELUP_STEP,
                "nenhum"
        );

        int spawnCount = prefs.getInt(
                OasisAccessibilityService.PREF_SPAWN_COUNT,
                0
        );

        String patrolMode = prefs.getString(
                OasisAccessibilityService.PREF_PATROL_MODE,
                "Mapeando spawns"
        );

        int routeIndex = prefs.getInt(
                OasisAccessibilityService.PREF_ROUTE_INDEX,
                0
        );

        int moveCount = prefs.getInt(
                OasisAccessibilityService.PREF_MOVE_COUNT,
                0
        );

        String routeStep = prefs.getString(
                OasisAccessibilityService.PREF_ROUTE_STEP,
                "não iniciada"
        );

        String combatStep = prefs.getString(
                OasisAccessibilityService.PREF_COMBAT_STEP,
                "aguardando"
        );

        String trollMatch = prefs.getString(
                OasisAccessibilityService.PREF_TROLL_MATCH,
                "-"
        );

        String visualScene = prefs.getString(
                OasisAccessibilityService.PREF_VISUAL_SCENE,
                "UNKNOWN"
        );

        String visualConfidence = prefs.getString(
                OasisAccessibilityService.PREF_VISUAL_CONFIDENCE,
                "0.000"
        );

        String flowExpected = prefs.getString(
                OasisAccessibilityService.PREF_FLOW_EXPECTED,
                "UNKNOWN"
        );

        int flowFailures = prefs.getInt(
                OasisAccessibilityService.PREF_FLOW_FAILURES,
                0
        );

        String magicScan = prefs.getString(
                OasisAccessibilityService.PREF_MAGIC_SCAN,
                ""
        );

        String magicOcr = prefs.getString(
                OasisAccessibilityService.PREF_MAGIC_OCR,
                ""
        );

        int huntZone = prefs.getInt(
                OasisAccessibilityService.PREF_HUNT_ZONE,
                0
        );

        int huntCycle = prefs.getInt(
                OasisAccessibilityService.PREF_HUNT_CYCLE,
                0
        );

        String lastError = prefs.getString(
                OasisAccessibilityService.PREF_LAST_ERROR,
                ""
        );

        accessibilityStatus.setText(
                "Acessibilidade: " + (accessibility ? "LIBERADA ✓" : "NÃO LIBERADA")
        );

        serviceStatus.setText(
                "Serviço: " +
                (serviceConnected && heartbeatFresh
                        ? "CONECTADO ✓"
                        : serviceConnected
                            ? "SEM RESPOSTA"
                            : "DESCONECTADO")
        );

        heartbeatStatus.setText(
                "Heartbeat: " + relativeTime(heartbeat)
        );

        autoplayStatus.setText(
                "Autoplay: " + (enabled ? "ATIVO ▶" : "PAUSADO ■")
        );

        engineStatus.setText(
                "Estado: " + (engine == null ? "Aguardando" : engine)
        );

        captureAttemptStatus.setText(
                "Tentativa de captura: " + relativeTime(captureAttempt)
        );

        captureErrorStatus.setText(
                "Captura: " +
                (captureError == null || captureError.isEmpty()
                        ? "sem erro registrado"
                        : captureError)
        );

        analysisStatus.setText(
                "Última análise: " + relativeTime(analysis)
        );

        actionStatus.setText(
                "Última ação: " + relativeTime(action)
        );

        countersStatus.setText(
                "Capturas: " + captures +
                "   Alvos: " + targets +
                "   Ataques: " + attacks
        );

        lastClickStatus.setText(
                "Último clique: " +
                (lastClick == null ? "nenhum" : lastClick)
        );

        lastTargetStatus.setText(
                "Último alvo: " +
                (lastTarget == null ? "nenhum" : lastTarget) +
                "   score=" +
                (candidateScore == null ? "-" : candidateScore)
        );

        levelUpStatus.setText(
                "Level-ups: " + levelUps +
                "   Etapa: " +
                (levelStep == null ? "nenhum" : levelStep)
        );

        patrolStatus.setText(
                "Spawns conhecidos: " + spawnCount +
                "   Caça: " +
                (patrolMode == null ? "-" : patrolMode)
        );

        routeStatus.setText(
                "Rota: ponto " + (routeIndex + 1) +
                "   Movimentos: " + moveCount +
                "   Etapa: " +
                (routeStep == null ? "-" : routeStep)
        );

        combatStepStatus.setText(
                "Combate: " +
                (combatStep == null ? "aguardando" : combatStep)
        );

        trollMatchStatus.setText(
                "Reconhecimento Troll: " +
                (trollMatch == null ? "-" : trollMatch) +
                "   mínimo=0.580"
        );

        visualAiStatus.setText(
                "IA visual local: " +
                (visualScene == null ? "UNKNOWN" : visualScene) +
                "  confiança=" +
                (visualConfidence == null ? "0.000" : visualConfidence)
        );

        flowStatus.setText(
                "Fluxo esperado: " +
                (flowExpected == null ? "UNKNOWN" : flowExpected) +
                "  falhas=" + flowFailures
        );

        magicScanStatus.setText(
                "Busca da magia: " +
                (magicScan == null ? "" : magicScan) +
                "\nOCR: " +
                (magicOcr == null ? "" : magicOcr)
        );

        huntRouteStatus.setText(
                "Rota 6 Trolls: zona " +
                (huntZone + 1) +
                "/6  ciclo=" +
                huntCycle
        );

        lastErrorStatus.setText(
                "Último erro: " +
                (lastError == null || lastError.isEmpty()
                        ? "nenhum"
                        : lastError)
        );

        toggleButton.setText(
                enabled ? "DESATIVAR AUTOPLAY" : "ATIVAR AUTOPLAY"
        );
    }

    private String relativeTime(long timestamp) {
        if (timestamp <= 0) return "nunca";

        long seconds = Math.max(
                0,
                (System.currentTimeMillis() - timestamp) / 1000
        );

        if (seconds <= 2) return "agora";
        if (seconds < 60) return "há " + seconds + " s";

        long minutes = seconds / 60;
        return "há " + minutes + " min";
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expected = new ComponentName(
                this,
                OasisAccessibilityService.class
        );

        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (TextUtils.isEmpty(enabledServices)) return false;

        TextUtils.SimpleStringSplitter splitter =
                new TextUtils.SimpleStringSplitter(':');

        splitter.setString(enabledServices);

        while (splitter.hasNext()) {
            String component = splitter.next();
            ComponentName enabled = ComponentName.unflattenFromString(component);

            if (enabled != null && enabled.equals(expected)) {
                return true;
            }
        }

        return false;
    }

    private void openOasis() {
        try {
            Intent intent = getPackageManager()
                    .getLaunchIntentForPackage("com.iwf.oasis");

            if (intent != null) startActivity(intent);
        } catch (Throwable ignored) {}
    }
}
