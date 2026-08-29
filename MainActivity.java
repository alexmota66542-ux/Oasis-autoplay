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
import android.widget.TextView;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView versionView;
    private TextView accessibilityStatus;
    private TextView serviceStatus;
    private TextView autoplayStatus;
    private TextView analysisStatus;
    private TextView actionStatus;
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(42, 56, 42, 42);
        root.setBackgroundColor(Color.rgb(20, 24, 30));

        TextView title = text("Oasis Autoplay", 30f, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        versionView = text("", 15f, Color.LTGRAY);
        versionView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vParams = matchWrap();
        vParams.setMargins(0, 10, 0, 38);
        root.addView(versionView, vParams);

        TextView panelTitle = text("STATUS DO MOTOR", 17f, Color.WHITE);
        panelTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pt = matchWrap();
        pt.setMargins(0, 0, 0, 20);
        root.addView(panelTitle, pt);

        accessibilityStatus = statusLine();
        serviceStatus = statusLine();
        autoplayStatus = statusLine();
        analysisStatus = statusLine();
        actionStatus = statusLine();

        root.addView(accessibilityStatus, matchWrap());
        root.addView(serviceStatus, matchWrap());
        root.addView(autoplayStatus, matchWrap());
        root.addView(analysisStatus, matchWrap());
        root.addView(actionStatus, matchWrap());

        toggleButton = new Button(this);
        toggleButton.setText("ATIVAR AUTOPLAY");
        toggleButton.setOnClickListener(v -> toggleAutoplay());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, 38, 0, 12);
        root.addView(toggleButton, buttonParams);

        Button settingsButton = new Button(this);
        settingsButton.setText("CONFIGURAÇÕES DE ACESSIBILIDADE");
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams settingsParams = matchWrap();
        settingsParams.setMargins(0, 0, 0, 12);
        root.addView(settingsButton, settingsParams);

        Button oasisButton = new Button(this);
        oasisButton.setText("ABRIR OASIS");
        oasisButton.setOnClickListener(v -> openOasis());
        root.addView(oasisButton, matchWrap());

        TextView hint = text(
                "Com o serviço liberado, toque em ATIVAR AUTOPLAY. " +
                "Depois abra o Oasis e acompanhe “Última análise”.",
                14f,
                Color.LTGRAY
        );
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.setMargins(0, 28, 0, 0);
        root.addView(hint, hintParams);

        setContentView(root);

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
        TextView tv = text("", 17f, Color.LTGRAY);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 7, 0, 7);
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

        boolean enabled = prefs.getBoolean(
                OasisAccessibilityService.PREF_AUTOPLAY_ENABLED,
                false
        );

        long analysis = prefs.getLong(
                OasisAccessibilityService.PREF_LAST_ANALYSIS,
                0
        );

        long action = prefs.getLong(
                OasisAccessibilityService.PREF_LAST_ACTION,
                0
        );

        accessibilityStatus.setText(
                "Acessibilidade: " + (accessibility ? "LIBERADA ✓" : "NÃO LIBERADA")
        );

        serviceStatus.setText(
                "Serviço: " + (serviceConnected ? "CONECTADO ✓" : "DESCONECTADO")
        );

        autoplayStatus.setText(
                "Autoplay: " + (enabled ? "ATIVO ▶" : "PAUSADO ■")
        );

        analysisStatus.setText(
                "Última análise: " + relativeTime(analysis)
        );

        actionStatus.setText(
                "Última ação: " + relativeTime(action)
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

            if (intent != null) {
                startActivity(intent);
            }
        } catch (Throwable ignored) {}
    }
}
