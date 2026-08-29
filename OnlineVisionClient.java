package com.oasisautoplay;

import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OnlineVisionClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String MODEL = "gpt-5.4";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static final class Decision {
        public final String scene;
        public final String action;
        public final double x;
        public final double y;
        public final double confidence;
        public final String reason;

        Decision(String scene, String action, double x, double y,
                 double confidence, String reason) {
            this.scene = scene;
            this.action = action;
            this.x = x;
            this.y = y;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    public interface Callback {
        void onSuccess(Decision decision);
        void onError(String error);
    }

    public void analyze(Bitmap source, String apiKey, Callback callback) {
        executor.execute(() -> {
            try {
                String dataUrl = toDataUrl(source);
                JSONObject payload = buildPayload(dataUrl);
                String raw = post(payload.toString(), apiKey);
                String text = extractOutputText(new JSONObject(raw));
                JSONObject d = new JSONObject(text);
                Decision decision = new Decision(
                        d.optString("scene", "UNKNOWN"),
                        d.optString("action", "NOOP"),
                        d.optDouble("x", 0.5),
                        d.optDouble("y", 0.5),
                        d.optDouble("confidence", 0.0),
                        d.optString("reason", "")
                );
                callback.onSuccess(decision);
            } catch (Throwable t) {
                callback.onError(shortError(t));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private String toDataUrl(Bitmap src) throws Exception {
        int targetW = Math.min(540, src.getWidth());
        int targetH = Math.max(1,
                Math.round(src.getHeight() * (targetW / (float) src.getWidth())));
        Bitmap scaled = src;
        if (targetW != src.getWidth()) {
            scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 58, out)) {
            throw new IllegalStateException("Falha ao comprimir screenshot");
        }
        if (scaled != src) {
            try { scaled.recycle(); } catch (Throwable ignored) {}
        }
        String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        return "data:image/jpeg;base64," + b64;
    }

    private JSONObject buildPayload(String dataUrl) throws Exception {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("scene", enumString("MAP", "TARGET_MENU", "BATTLEFIELD",
                "TURN_MENU", "MAGIC", "SPELL_TARGET", "VICTORY", "UNKNOWN"));
        props.put("action", enumString("NOOP", "TAP_TROLL", "ATTACK_TARGET_MENU",
                "OPEN_ACTION_MENU", "OPEN_USE_MAGIC", "TAP_BLESSING_SLOT",
                "APPLY_BLESSING_SELF", "CLOSE_VICTORY"));
        props.put("x", number01());
        props.put("y", number01());
        props.put("confidence", number01());
        props.put("reason", new JSONObject().put("type", "string"));
        schema.put("properties", props);
        schema.put("required", new JSONArray()
                .put("scene").put("action").put("x").put("y")
                .put("confidence").put("reason"));
        schema.put("additionalProperties", false);

        JSONObject format = new JSONObject()
                .put("type", "json_schema")
                .put("name", "oasis_screen_decision")
                .put("strict", true)
                .put("schema", schema);

        String instruction =
                "Você é o fallback visual de um autoplay externo para Oasis MMORPG. " +
                "Analise SOMENTE a screenshot atual. Não invente estado anterior. " +
                "Prioridade: vitória, menu do Troll, magia, alvo da magia, batalha, mapa. " +
                "TARGET_MENU é a janela com Atacar/Reconhecimento/Fechar. " +
                "MAGIC é a lista roxa de magias; Bênção é a magia que aumenta ataque/super força. " +
                "Em batalha, OPEN_ACTION_MENU abre Ação; TURN_MENU permite OPEN_USE_MAGIC. " +
                "TAP_BLESSING_SLOT deve apontar para o centro da linha da Bênção. " +
                "APPLY_BLESSING_SELF deve apontar para a tropa principal do próprio jogador. " +
                "TAP_TROLL só quando um Troll clicável estiver claramente visível no mapa. " +
                "Use NOOP quando houver dúvida. Coordenadas x/y são normalizadas de 0 a 1.";

        JSONArray content = new JSONArray()
                .put(new JSONObject().put("type", "input_text").put("text", instruction))
                .put(new JSONObject().put("type", "input_image")
                        .put("image_url", dataUrl).put("detail", "low"));

        JSONArray input = new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", content));

        return new JSONObject()
                .put("model", MODEL)
                .put("store", false)
                .put("input", input)
                .put("text", new JSONObject().put("format", format));
    }

    private JSONObject enumString(String... values) throws Exception {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return new JSONObject().put("type", "string").put("enum", a);
    }

    private JSONObject number01() throws Exception {
        return new JSONObject().put("type", "number")
                .put("minimum", 0.0).put("maximum", 1.0);
    }

    private String post(String body, String apiKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(12000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String response = readAll(in);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + trim(response, 220));
        }
        return response;
    }

    private String extractOutputText(JSONObject root) throws Exception {
        if (root.has("output_text")) {
            String t = root.optString("output_text", "");
            if (!t.isEmpty()) return t;
        }
        JSONArray output = root.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null) continue;
                    String text = part.optString("text", "");
                    if (!text.isEmpty()) return text;
                }
            }
        }
        throw new IllegalStateException("Resposta da IA sem output_text");
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String shortError(Throwable t) {
        String m = t == null ? "erro" : t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 240 ? m.substring(0, 240) : m;
    }
}
