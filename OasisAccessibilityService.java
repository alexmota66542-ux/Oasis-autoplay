package com.oasisautoplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
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

import com.google.android.gms.tasks.Tasks;
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
import java.util.concurrent.TimeUnit;

public class OasisAccessibilityService extends AccessibilityService {
    private static final String TAG = "OasisAutoplay";
    private static final String OASIS_PACKAGE = "com.iwf.oasis";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private String lastPackage = "";
    private boolean screenshotBusy = false;
    private long lastActionMs = 0;

    // Estado de combate
    private boolean inCombat = false;
    private boolean attackBuffUsed = false;
    private boolean openingSplashDone = false;

    // IDs lógicos 1..5, mas posição é atualizada a cada frame.
    private final Map<Integer, StackTrack> tracks = new HashMap<>();

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (OASIS_PACKAGE.equals(lastPackage)) analyzeScreen();
            handler.postDelayed(this, 900);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(loop);
        handler.postDelayed(loop, 800);
        Log.d(TAG, "Autoplay estratégico v0.3 iniciado");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        lastPackage = event.getPackageName().toString();
    }

    @Override public void onInterrupt() {
        Log.d(TAG, "Serviço interrompido");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(loop);
        recognizer.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void analyzeScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || screenshotBusy) return;
        screenshotBusy = true;

        takeScreenshot(
                Display.DEFAULT_DISPLAY,
                worker,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        Bitmap soft = null;
                        HardwareBuffer buffer = null;
                        try {
                            buffer = result.getHardwareBuffer();
                            ColorSpace cs = result.getColorSpace();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, cs);
                            if (hw != null) soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                            if (soft != null) detectStateAndAct(soft);
                        } catch (Throwable t) {
                            Log.e(TAG, "Falha analisando screenshot", t);
                        } finally {
                            if (soft != null) soft.recycle();
                            if (buffer != null) buffer.close();
                            screenshotBusy = false;
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        screenshotBusy = false;
                        Log.w(TAG, "Screenshot falhou: " + errorCode);
                    }
                }
        );
    }

    private void detectStateAndAct(Bitmap b) {
        if (System.currentTimeMillis() - lastActionMs < 1000) return;

        int w = b.getWidth();
        int h = b.getHeight();

        // Vitória
        double tan = ratio(b, 0.08, 0.16, 0.92, 0.78, 24, PixelKind.TAN);
        if (tan > 0.48) {
            resetCombat();
            action("VICTORY_CLOSE", 0.25f * w, 0.91f * h);
            return;
        }

        // Menu roxo de magias
        double purple = ratio(b, 0.05, 0.14, 0.95, 0.83, 26, PixelKind.PURPLE);
        if (purple > 0.30) {
            inCombat = true;
            if (!attackBuffUsed) {
                // No vídeo, o buff Attack é o terceiro slot útil da lista.
                // Seleciona o ícone e confirma.
                float attackIconX = 0.085f * w;
                float attackIconY = 0.275f * h;
                tap(attackIconX, attackIconY);

                handler.postDelayed(
                        () -> tap(0.25f * w, 0.91f * h),
                        420
                );

                attackBuffUsed = true;
                lastActionMs = System.currentTimeMillis();
                Log.d(TAG, "MAGIA: Attack usada (única magia do combate)");
            }
            return;
        }

        // Menu de alvo no mapa
        double magenta = ratio(b, 0.02, 0.69, 0.49, 0.82, 16, PixelKind.MAGENTA);
        if (magenta > 0.10 && !inCombat) {
            action("MAP_TARGET_MENU_ATTACK", 0.25f * w, 0.765f * h);
            return;
        }

        // Arena de combate: procura rótulos pretos dos stacks.
        List<StackCandidate> candidates = findStackCandidates(b);
        if (candidates.size() >= 2) {
            inCombat = true;
            enrichCountsWithOcr(b, candidates);
            updateTracks(candidates);
            chooseStrategicTargetAndTap(w, h);
        }
    }

    private void resetCombat() {
        inCombat = false;
        attackBuffUsed = false;
        openingSplashDone = false;
        tracks.clear();
    }

    // Procura caixas pretas com números abaixo dos sprites.
    private List<StackCandidate> findStackCandidates(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int x0 = (int)(0.04 * w);
        int x1 = (int)(0.96 * w);
        int y0 = (int)(0.12 * h);
        int y1 = (int)(0.78 * h);

        boolean[][] visited = new boolean[Math.max(1, (y1-y0)/4 + 2)][Math.max(1, (x1-x0)/4 + 2)];
        List<StackCandidate> out = new ArrayList<>();

        int step = 4;
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int gy = (y-y0)/step;
                int gx = (x-x0)/step;
                if (visited[gy][gx]) continue;

                int c = b.getPixel(x, y);
                if (!isLabelDark(c)) continue;

                // flood fill simplificado em grade
                ArrayList<int[]> q = new ArrayList<>();
                q.add(new int[]{gx, gy});
                visited[gy][gx] = true;
                int qi = 0;
                int minGX=gx, maxGX=gx, minGY=gy, maxGY=gy, n=0;

                while (qi < q.size() && n < 5000) {
                    int[] p = q.get(qi++);
                    int px = x0 + p[0]*step;
                    int py = y0 + p[1]*step;
                    n++;
                    minGX = Math.min(minGX, p[0]);
                    maxGX = Math.max(maxGX, p[0]);
                    minGY = Math.min(minGY, p[1]);
                    maxGY = Math.max(maxGY, p[1]);

                    int[][] dirs={{1,0},{-1,0},{0,1},{0,-1}};
                    for (int[] d:dirs) {
                        int nx=p[0]+d[0], ny=p[1]+d[1];
                        if (ny<0 || nx<0 || ny>=visited.length || nx>=visited[0].length) continue;
                        if (visited[ny][nx]) continue;
                        int sx=x0+nx*step, sy=y0+ny*step;
                        if (sx>=x1 || sy>=y1) continue;
                        if (!isLabelDark(b.getPixel(sx,sy))) continue;
                        visited[ny][nx]=true;
                        q.add(new int[]{nx,ny});
                    }
                }

                int left=x0+minGX*step, right=x0+(maxGX+1)*step;
                int top=y0+minGY*step, bottom=y0+(maxGY+1)*step;
                int bw=right-left, bh=bottom-top;

                // rótulos do jogo são baixos e relativamente largos
                if (bw >= 24 && bw <= 150 && bh >= 10 && bh <= 52) {
                    Rect label = new Rect(left, top, right, bottom);
                    // centro do sprite estimado logo acima do rótulo
                    float sx = (left+right)/2f;
                    float sy = Math.max(0, top - Math.max(24, bh*1.4f));
                    out.add(new StackCandidate(sx, sy, label));
                }
            }
        }

        // remove duplicados próximos
        Collections.sort(out, Comparator.comparingDouble(a -> a.x));
        List<StackCandidate> dedup = new ArrayList<>();
        for (StackCandidate c : out) {
            boolean near=false;
            for (StackCandidate d : dedup) {
                if (Math.hypot(c.x-d.x, c.y-d.y) < 35) { near=true; break; }
            }
            if (!near) dedup.add(c);
        }

        return dedup;
    }

    private void enrichCountsWithOcr(Bitmap b, List<StackCandidate> candidates) {
        for (StackCandidate c : candidates) {
            Rect r = grow(c.label, b.getWidth(), b.getHeight(), 4, 3);
            Bitmap crop = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height());
            try {
                Text text = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(crop, 0)),
                        700, TimeUnit.MILLISECONDS
                );
                c.count = parseBestInteger(text);
            } catch (Throwable ignored) {
                c.count = -1;
            } finally {
                crop.recycle();
            }
        }
    }

    private long parseBestInteger(Text text) {
        long best = -1;
        if (text == null) return best;

        String raw = text.getText();
        if (raw == null) return best;

        String[] pieces = raw.split("[^0-9]+");
        for (String p : pieces) {
            if (p.isEmpty()) continue;
            try {
                long v = Long.parseLong(p);
                if (v > best) best = v;
            } catch (Exception ignored) {}
        }
        return best;
    }

    private void updateTracks(List<StackCandidate> cands) {
        if (tracks.isEmpty() && cands.size() >= 5) {
            // formação inicial: esquerda -> direita = IDs 1..5
            Collections.sort(cands, Comparator.comparingDouble(a -> a.x));
            for (int i=0; i<5; i++) {
                StackCandidate c=cands.get(i);
                tracks.put(i+1, new StackTrack(i+1, c.x, c.y, c.count));
            }
            Log.d(TAG, "Formação Troll 1..5 inicializada");
            return;
        }

        // acompanha cada ID pela posição mais próxima ao frame anterior
        List<StackCandidate> remaining = new ArrayList<>(cands);
        for (int id=1; id<=5; id++) {
            StackTrack t=tracks.get(id);
            if (t == null || !t.alive) continue;

            StackCandidate best=null;
            double bestDist=Double.MAX_VALUE;
            for (StackCandidate c:remaining) {
                double d=Math.hypot(c.x-t.x, c.y-t.y);
                if (d<bestDist) {
                    bestDist=d;
                    best=c;
                }
            }

            // movimento de Troll: permite deslocamento grande entre frames,
            // mas evita "teleportar" para outro stack distante.
            if (best != null && bestDist < 230) {
                t.x=best.x;
                t.y=best.y;
                if (best.count >= 0) t.count=best.count;
                remaining.remove(best);
            } else {
                t.missed++;
                if (t.missed >= 3) t.alive=false;
            }
        }

        // qualquer track visto volta a zerar missed
        for (StackTrack t:tracks.values()) {
            if (t.alive) t.missed=Math.max(0, t.missed-1);
        }
    }

    private void chooseStrategicTargetAndTap(int w, int h) {
        List<StackTrack> alive = aliveTracks();
        if (alive.isEmpty()) return;

        StackTrack target = null;

        // 1) Abertura: prioridade tática ao Troll 2 para splash 1-2-3.
        StackTrack t2 = tracks.get(2);
        if (!openingSplashDone && t2 != null && t2.alive) {
            target = t2;
            openingSplashDone = true;
            Log.d(TAG, "ALVO: Troll 2 (abertura splash 1-2-3)");
        }

        // 2) Se 1/2/3 ainda existem e estão mais próximos da nossa metade,
        // elimina a frente antes que o dano em área do Troll alcance nossa tropa.
        if (target == null) {
            StackTrack urgent = null;
            for (int id=1; id<=3; id++) {
                StackTrack t=tracks.get(id);
                if (t == null || !t.alive) continue;
                if (urgent == null || t.y > urgent.y) urgent=t;
            }
            if (urgent != null && urgent.y > 0.42f*h) {
                target = urgent;
                Log.d(TAG, "ALVO: frente 1-2-3 em zona de risco, Troll " + urgent.id);
            }
        }

        // 3) Sobrou 4 e 5: ataca o Troll com maior quantidade, na POSIÇÃO ATUAL.
        if (target == null) {
            StackTrack t4=tracks.get(4), t5=tracks.get(5);
            boolean a4=t4!=null && t4.alive;
            boolean a5=t5!=null && t5.alive;

            if (a4 && a5) {
                if (t4.count >= 0 && t5.count >= 0) {
                    target = (t4.count >= t5.count) ? t4 : t5;
                    Log.d(TAG, "ALVO: maior entre 4/5 = Troll " + target.id +
                            " (" + target.count + ")");
                } else {
                    // OCR falhou: usa o mais avançado/perigoso como fallback.
                    target = (t4.y >= t5.y) ? t4 : t5;
                    Log.d(TAG, "ALVO: fallback 4/5 por proximidade = Troll " + target.id);
                }
            }
        }

        // 4) Fallback geral: maior quantidade lida; se não houver OCR, o mais próximo.
        if (target == null) {
            for (StackTrack t:alive) {
                if (target == null) { target=t; continue; }
                if (t.count >= 0 && (target.count < 0 || t.count > target.count)) {
                    target=t;
                } else if (t.count < 0 && target.count < 0 && t.y > target.y) {
                    target=t;
                }
            }
        }

        if (target != null) {
            // toca no sprite atual, NÃO na casa inicial.
            float tx = clamp(target.x, 20, w-20);
            float ty = clamp(target.y, 60, h-160);
            action("ATTACK_TROLL_" + target.id, tx, ty);
        }
    }

    private List<StackTrack> aliveTracks() {
        List<StackTrack> out=new ArrayList<>();
        for (int id=1; id<=5; id++) {
            StackTrack t=tracks.get(id);
            if (t!=null && t.alive) out.add(t);
        }
        return out;
    }

    private Rect grow(Rect r, int w, int h, int gx, int gy) {
        return new Rect(
                Math.max(0, r.left-gx),
                Math.max(0, r.top-gy),
                Math.min(w, r.right+gx),
                Math.min(h, r.bottom+gy)
        );
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private boolean isLabelDark(int c) {
        int r=Color.red(c), g=Color.green(c), b=Color.blue(c);
        return r<55 && g<55 && b<55;
    }

    private enum PixelKind { TAN, PURPLE, MAGENTA }

    private boolean matches(int c, PixelKind kind) {
        int r=Color.red(c), g=Color.green(c), b=Color.blue(c);
        switch (kind) {
            case TAN:
                return r>175 && g>135 && g<220 && b>90 && b<190 && r>g;
            case PURPLE:
                return r>35 && r<155 && b>50 && b<180 && b>g && r>g;
            case MAGENTA:
                return r>120 && b>90 && g<90 && r>g*1.7;
            default:
                return false;
        }
    }

    private double ratio(
            Bitmap bitmap,
            double x1, double y1, double x2, double y2,
            int step, PixelKind kind
    ) {
        int sx=Math.max(0,(int)(x1*bitmap.getWidth()));
        int sy=Math.max(0,(int)(y1*bitmap.getHeight()));
        int ex=Math.min(bitmap.getWidth()-1,(int)(x2*bitmap.getWidth()));
        int ey=Math.min(bitmap.getHeight()-1,(int)(y2*bitmap.getHeight()));
        int hit=0,total=0;

        for (int y=sy;y<=ey;y+=step) {
            for (int x=sx;x<=ex;x+=step) {
                total++;
                if (matches(bitmap.getPixel(x,y),kind)) hit++;
            }
        }
        return total==0?0:((double)hit/total);
    }

    private void action(String state, float x, float y) {
        if (System.currentTimeMillis()-lastActionMs < 900) return;
        if (tap(x,y)) {
            lastActionMs=System.currentTimeMillis();
            Log.d(TAG,state+" -> tap "+x+","+y);
        }
    }

    public boolean tap(float x, float y) {
        Path path=new Path();
        path.moveTo(x,y);
        GestureDescription.StrokeDescription stroke=
                new GestureDescription.StrokeDescription(path,0,80);
        GestureDescription gesture=
                new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture,null,null);
    }

    private static class StackCandidate {
        float x,y;
        Rect label;
        long count=-1;

        StackCandidate(float x,float y,Rect label) {
            this.x=x;
            this.y=y;
            this.label=label;
        }
    }

    private static class StackTrack {
        int id;
        float x,y;
        long count;
        boolean alive=true;
        int missed=0;

        StackTrack(int id,float x,float y,long count) {
            this.id=id;
            this.x=x;
            this.y=y;
            this.count=count;
        }
    }
}
