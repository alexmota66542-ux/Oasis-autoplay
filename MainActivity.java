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
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView versionView, statusView, onlineStatusView;
    private Button toggleButton, onlineToggleButton;
    private EditText apiKeyInput;

    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
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
        root.setPadding(36, 42, 36, 42);
        root.setBackgroundColor(Color.rgb(20, 24, 30));

        TextView title = text("Oasis Autoplay", 29f, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        versionView = text("", 14f, Color.LTGRAY);
        versionView.setGravity(Gravity.CENTER);
        root.addView(versionView, spaced(0, 8, 0, 20));

        statusView = text("", 15f, Color.LTGRAY);
        root.addView(statusView, matchWrap());

        toggleButton = new Button(this);
        toggleButton.setOnClickListener(v -> toggleAutoplay());
        root.addView(toggleButton, spaced(0, 20, 0, 8));

        Button accessibility = new Button(this);
        accessibility.setText("CONFIGURAÇÕES DE ACESSIBILIDADE");
        accessibility.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, spaced(0, 0, 0, 8));

        Button oasis = new Button(this);
        oasis.setText("ABRIR OASIS");
        oasis.setOnClickListener(v -> openOasis());
        root.addView(oasis, spaced(0, 0, 0, 24));

        TextView onlineTitle = text("IA ONLINE HÍBRIDA", 18f, Color.WHITE);
        onlineTitle.setGravity(Gravity.CENTER);
        root.addView(onlineTitle, spaced(0, 6, 0, 10));

        TextView onlineHint = text(
                "A IA online só é chamada quando a leitura local fica incerta ou travada. " +
                "A chave fica salva somente neste aparelho e não é colocada no GitHub.",
                14f, Color.LTGRAY);
        root.addView(onlineHint, spaced(0, 0, 0, 10));

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("Cole sua OpenAI API key");
        apiKeyInput.setTextColor(Color.WHITE);
        apiKeyInput.setHintTextColor(Color.GRAY);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String savedKey = prefs.getString(OasisAccessibilityService.PREF_ONLINE_API_KEY, "");
        if (savedKey != null && !savedKey.isEmpty()) apiKeyInput.setText(savedKey);
        root.addView(apiKeyInput, matchWrap());

        Button saveKey = new Button(this);
        saveKey.setText("SALVAR CHAVE API");
        saveKey.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            prefs.edit().putString(OasisAccessibilityService.PREF_ONLINE_API_KEY, key).apply();
            refreshStatus();
        });
        root.addView(saveKey, spaced(0, 8, 0, 8));

        onlineToggleButton = new Button(this);
        onlineToggleButton.setOnClickListener(v -> {
            boolean current = prefs.getBoolean(
                    OasisAccessibilityService.PREF_ONLINE_AI_ENABLED, false);
            prefs.edit().putBoolean(
                    OasisAccessibilityService.PREF_ONLINE_AI_ENABLED, !current).apply();
            refreshStatus();
        });
        root.addView(onlineToggleButton, spaced(0, 0, 0, 8));

        onlineStatusView = text("", 14f, Color.LTGRAY);
        root.addView(onlineStatusView, matchWrap());

        TextView safety = text(
                "Segurança: a resposta online é limitada a ações conhecidas; coordenadas fora " +
                "das áreas válidas são descartadas. Após cada toque, o motor volta a capturar " +
                "a tela para confirmar o novo estado.",
                13f, Color.GRAY);
        root.addView(safety, spaced(0, 16, 0, 0));

        scroll.addView(root);
        setContentView(scroll);
        loadVersionName();
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusLoop);
        handler.post(statusLoop);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(statusLoop);
        super.onPause();
    }

    private void refreshStatus() {
        boolean accessibility = isAccessibilityServiceEnabled();
        boolean autoplay = prefs.getBoolean(OasisAccessibilityService.PREF_AUTOPLAY_ENABLED, false);
        boolean service = prefs.getBoolean(OasisAccessibilityService.PREF_SERVICE_CONNECTED, false);
        long heartbeat = prefs.getLong(OasisAccessibilityService.PREF_SERVICE_HEARTBEAT, 0);
        boolean fresh = heartbeat > 0 && System.currentTimeMillis() - heartbeat < 5000;

        String engine = prefs.getString(OasisAccessibilityService.PREF_ENGINE_STATE, "Aguardando");
        String localScene = prefs.getString(OasisAccessibilityService.PREF_VISUAL_SCENE, "UNKNOWN");
        String localConf = prefs.getString(OasisAccessibilityService.PREF_VISUAL_CONFIDENCE, "0.000");
        String lastClick = prefs.getString(OasisAccessibilityService.PREF_LAST_CLICK, "nenhum");
        String combat = prefs.getString(OasisAccessibilityService.PREF_COMBAT_STEP, "aguardando");
        String magic = prefs.getString(OasisAccessibilityService.PREF_MAGIC_SCAN, "");
        String ocr = prefs.getString(OasisAccessibilityService.PREF_MAGIC_OCR, "");
        String error = prefs.getString(OasisAccessibilityService.PREF_LAST_ERROR, "");
        long captures = prefs.getLong(OasisAccessibilityService.PREF_CAPTURE_COUNT, 0);
        long targets = prefs.getLong(OasisAccessibilityService.PREF_TARGET_COUNT, 0);
        long attacks = prefs.getLong(OasisAccessibilityService.PREF_ATTACK_COUNT, 0);

        statusView.setText(
                "Acessibilidade: " + (accessibility ? "LIBERADA ✓" : "NÃO LIBERADA") + "\n" +
                "Serviço: " + (service && fresh ? "CONECTADO ✓" : service ? "SEM RESPOSTA" : "DESCONECTADO") + "\n" +
                "Autoplay: " + (autoplay ? "ATIVO ▶" : "PAUSADO ■") + "\n" +
                "Estado: " + safe(engine) + "\n" +
                "IA local: " + safe(localScene) + " confiança=" + safe(localConf) + "\n" +
                "Combate: " + safe(combat) + "\n" +
                "Último clique: " + safe(lastClick) + "\n" +
                "Capturas: " + captures + "   Alvos: " + targets + "   Ataques: " + attacks + "\n" +
                "Magia: " + safe(magic) + "\nOCR: " + safe(ocr) + "\n" +
                "Último erro: " + (error == null || error.isEmpty() ? "nenhum" : error)
        );

        toggleButton.setText(autoplay ? "DESATIVAR AUTOPLAY" : "ATIVAR AUTOPLAY");

        boolean online = prefs.getBoolean(OasisAccessibilityService.PREF_ONLINE_AI_ENABLED, false);
        String key = prefs.getString(OasisAccessibilityService.PREF_ONLINE_API_KEY, "");
        String onlineStatus = prefs.getString(OasisAccessibilityService.PREF_ONLINE_AI_STATUS, "Nunca consultada");
        String onlineScene = prefs.getString(OasisAccessibilityService.PREF_ONLINE_AI_LAST_SCENE, "-");
        String onlineAction = prefs.getString(OasisAccessibilityService.PREF_ONLINE_AI_LAST_ACTION, "-");
        String onlineError = prefs.getString(OasisAccessibilityService.PREF_ONLINE_AI_LAST_ERROR, "");

        onlineToggleButton.setText(online ? "DESATIVAR IA ONLINE" : "ATIVAR IA ONLINE");
        onlineStatusView.setText(
                "Online: " + (online ? "ATIVA" : "DESATIVADA") +
                "   Chave: " + (key != null && !key.isEmpty() ? "SALVA ✓" : "NÃO CONFIGURADA") + "\n" +
                "Status: " + safe(onlineStatus) + "\n" +
                "Última decisão: " + safe(onlineScene) + " / " + safe(onlineAction) +
                (onlineError == null || onlineError.isEmpty() ? "" : "\nErro online: " + onlineError)
        );
    }

    private void toggleAutoplay() {
        if (!isAccessibilityServiceEnabled()) {
            prefs.edit().putBoolean(OasisAccessibilityService.PREF_AUTOPLAY_ENABLED, false).apply();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        boolean enabled = prefs.getBoolean(OasisAccessibilityService.PREF_AUTOPLAY_ENABLED, false);
        prefs.edit().putBoolean(OasisAccessibilityService.PREF_AUTOPLAY_ENABLED, !enabled).apply();
        refreshStatus();
    }

    private void loadVersionName() {
        try {
            versionView.setText("Versão " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Throwable t) { versionView.setText("Versão atual"); }
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expected = new ComponentName(this, OasisAccessibilityService.class);
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (enabled != null && enabled.equals(expected)) return true;
        }
        return false;
    }

    private void openOasis() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.iwf.oasis");
            if (intent != null) startActivity(intent);
        } catch (Throwable ignored) {}
    }

    private String safe(String s) { return s == null ? "" : s; }

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
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(l, t, r, b);
        return p;
    }
}
