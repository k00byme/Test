package app.k00byme.chessassistant.extension;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime code merged into Chess.com by Morphe.
 *
 * This extension is installed from the dedicated Play-vs-Bots activity,
 * the general RealGameActivity used for online games, and standalone analysis.
 * It only draws an overlay; it never clicks squares, submits moves, or changes game state.
 */
public final class ChessAssistantPatch {
    private static final String TAG = "morphe.chess.assistant.analysis";
    private static final String BOARD_CLASS_V2 = "com.chess.chessboard.v2.ChessBoardView";
    private static final String BOARD_CLASS_V1 = "com.chess.chessboard.view.ChessBoardView";
    private static final String CHESS_API_URL = "https://chess-api.com/v1";
    private static final String STOCKFISH_URL = "https://stockfish.online/api/s/v2.php";
    private static final int ENGINE_DEPTH = 12;
    private static final int ENGINE_VARIANTS = 5;
    private static final int ENGINE_MAX_THINKING_TIME_MS = 100;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ChessAssistantPatch() {}

    /** Called from BotGameActivityV2 or the standalone analysis screen. */
    public static void install(Activity activity) {
        if (activity == null) return;
        String activityName = activity.getClass().getName();
        boolean supported = "com.chess.features.versusbots.game.BotGameActivityV2".equals(activityName)
                || "com.chess.features.analysis.standalonev2.StandaloneAnalysisActivityV2".equals(activityName)
                || "com.chess.realchess.ui.game.RealGameActivity".equals(activityName);
        if (!supported) return;

        // onCreate is hooked before the app creates its content view, so defer installation.
        MAIN.postDelayed(() -> {
            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                return;
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) content;
            if (root.findViewWithTag(TAG) != null) return;

            AnalysisOverlay overlay = new AnalysisOverlay(activity);
            overlay.setTag(TAG);
            root.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }, 1100L);
    }

    private static final class AnalysisOverlay extends FrameLayout {
        private final WeakReference<Activity> activityRef;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final TextView panel;
        private final LinearLayout candidatePanel;
        private final BoardDrawing drawing;
        private final Runnable poller = new Runnable() {
            @Override public void run() {
                scanAndAnalyze();
                if (isAttachedToWindow()) handler.postDelayed(this, 1200L);
            }
        };

        private volatile boolean requestInFlight;
        private volatile boolean closed;
        private String requestedFen = "";
        private String activeFen = "";
        private String bestMove = "";
        private double evaluation = 0.0;
        private int mate = 0;
        private CandidateMove[] candidates = new CandidateMove[0];
        private View boardView;

        AnalysisOverlay(Activity activity) {
            super(activity);
            activityRef = new WeakReference<>(activity);
            setClipChildren(false);
            setClipToPadding(false);
            setClickable(false);
            setFocusable(false);

            drawing = new BoardDrawing(activity);
            drawing.setClickable(false);
            drawing.setFocusable(false);
            addView(drawing, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
            ));

            panel = new TextView(activity);
            panel.setText("Procurando o tabuleiro…");
            panel.setTextColor(0xFFFFFFFF);
            panel.setTextSize(12.5f);
            panel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            panel.setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9));

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE820242B);
            background.setCornerRadius(dp(activity, 12));
            background.setStroke(dp(activity, 1), 0x665B6472);
            panel.setBackground(background);
            panel.setElevation(dp(activity, 7));
            panel.setClickable(false);
            panel.setFocusable(false);

            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END
            );
            int margin = dp(activity, 10);
            panelParams.topMargin = margin;
            panelParams.rightMargin = margin;
            addView(panel, panelParams);

            candidatePanel = new LinearLayout(activity);
            candidatePanel.setOrientation(LinearLayout.VERTICAL);
            candidatePanel.setGravity(Gravity.END);
            candidatePanel.setClickable(false);
            candidatePanel.setFocusable(false);
            candidatePanel.setVisibility(GONE);

            FrameLayout.LayoutParams candidateParams = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END
            );
            candidateParams.bottomMargin = dp(activity, 12);
            candidateParams.rightMargin = dp(activity, 10);
            addView(candidatePanel, candidateParams);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            handler.removeCallbacks(poller);
            handler.post(poller);
        }

        @Override protected void onDetachedFromWindow() {
            closed = true;
            handler.removeCallbacksAndMessages(null);
            worker.shutdownNow();
            super.onDetachedFromWindow();
        }

        private void scanAndAnalyze() {
            Activity activity = activityRef.get();
            if (closed || activity == null || activity.isFinishing()) return;

            View content = activity.findViewById(android.R.id.content);
            View found = findBoard(content);
            if (found == null) {
                boardView = null;
                activeFen = "";
                requestedFen = "";
                clearAnalysis();
                drawing.setBoard(null);
                setStatus("Aguardando o tabuleiro do jogo…");
                return;
            }
            boardView = found;

            String fen = readFen(found);
            if (!isFen(fen)) {
                activeFen = "";
                requestedFen = "";
                clearAnalysis();
                drawing.setBoard(found);
                setStatus("Aguardando uma posição válida do tabuleiro…");
                return;
            }

            drawing.setBoard(found);
            if (!fen.equals(activeFen)) {
                activeFen = fen;
                clearAnalysis();
            }

            if (!fen.equals(requestedFen) && !requestInFlight) requestAnalysis(fen);
            drawing.setEngine(candidates, evaluation, mate, isBoardFlipped(found));
        }

        private void clearAnalysis() {
            bestMove = "";
            evaluation = 0.0;
            mate = 0;
            candidates = new CandidateMove[0];
            drawing.setEngine(candidates, evaluation, mate, boardView != null && isBoardFlipped(boardView));
            showCandidateBadges(candidates);
        }

        private void requestAnalysis(final String fen) {
            requestedFen = fen;
            requestInFlight = true;
            setStatus("Stockfish analisando cinco alternativas…");
            try {
                worker.execute(() -> {
                    EngineResult result = fetchAnalysis(fen);
                    MAIN.post(() -> {
                        requestInFlight = false;
                        if (closed || !isAttachedToWindow()) return;

                        // A newer position may have arrived while the previous request was running.
                        if (!fen.equals(activeFen)) {
                            String newestFen = activeFen;
                            if (isFen(newestFen) && !newestFen.equals(requestedFen)) requestAnalysis(newestFen);
                            return;
                        }

                        if (result.error != null) {
                            clearAnalysis();
                            setStatus("Análise indisponível: " + result.error);
                            return;
                        }

                        bestMove = result.bestMove;
                        evaluation = result.evaluation;
                        mate = result.mate;
                        candidates = result.candidates == null ? new CandidateMove[0] : result.candidates;
                        showCandidateBadges(candidates);

                        String evalText = result.mate != 0
                                ? "Mate em " + result.mate
                                : String.format(java.util.Locale.US, "%+.2f", result.evaluation);
                        String mode = candidates.length >= 3 ? "3 opções" : candidates.length + " opção(ões)";
                        setStatus("STOCKFISH 18  •  profundidade " + ENGINE_DEPTH
                                + "\nAvaliação: " + evalText
                                + "\n" + mode);
                        drawing.setEngine(candidates, evaluation, mate, isBoardFlipped(boardView));
                    });
                });
            } catch (RuntimeException ex) {
                requestInFlight = false;
                setStatus("Não foi possível iniciar a análise.");
            }
        }

        private void showCandidateBadges(CandidateMove[] values) {
            candidatePanel.removeAllViews();
            if (values == null || values.length == 0 || closed) {
                candidatePanel.setVisibility(GONE);
                return;
            }

            int[] colors = BoardDrawing.MOVE_COLORS;
            for (int i = 0; i < values.length; i++) {
                CandidateMove candidate = values[i];
                TextView badge = new TextView(getContext());
                String moveText = candidate.move.substring(0, 2) + " → " + candidate.move.substring(2, 4);
                if (candidate.move.length() > 4) moveText += "=" + Character.toUpperCase(candidate.move.charAt(4));
                String scoreText = candidate.mate != 0
                        ? (candidate.mate > 0 ? "M+" + candidate.mate : "M" + candidate.mate)
                        : String.format(java.util.Locale.US, "%+.2f", candidate.evaluation);
                badge.setText(candidate.label + "  ·  " + moveText + "  ·  " + scoreText);
                badge.setTextColor(0xFFFFFFFF);
                badge.setTextSize(11.5f);
                badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                badge.setPadding(dp(getContext(), 10), dp(getContext(), 7),
                        dp(getContext(), 10), dp(getContext(), 7));

                GradientDrawable chip = new GradientDrawable();
                chip.setColor(0xE820242B);
                chip.setCornerRadius(dp(getContext(), 10));
                chip.setStroke(dp(getContext(), 2), colors[Math.min(i, colors.length - 1)]);
                badge.setBackground(chip);
                badge.setElevation(dp(getContext(), 5));
                badge.setClickable(false);
                badge.setFocusable(false);

                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                );
                if (i > 0) badgeParams.topMargin = dp(getContext(), 5);
                candidatePanel.addView(badge, badgeParams);
            }
            candidatePanel.setVisibility(VISIBLE);
        }

        private void setStatus(String value) {
            if (closed) return;
            MAIN.post(() -> {
                if (!closed && isAttachedToWindow()) panel.setText(value);
            });
        }
    }

    private static View findBoard(View view) {
        if (view == null) return null;
        String className = view.getClass().getName();
        if (BOARD_CLASS_V2.equals(className) || BOARD_CLASS_V1.equals(className)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findBoard(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String invokeFen(Object position) {
        if (position == null) return "";
        String[] names = {"o", "toFen", "getFen", "getFEN", "fen"};
        for (String name : names) {
            try {
                Method method = position.getClass().getMethod(name);
                if (method.getParameterTypes().length != 0) continue;
                Object value = method.invoke(position);
                if (value instanceof String && isFen((String) value)) return ((String) value).trim();
            } catch (Throwable ignored) {
                // Continue through the version-specific position accessors.
            }
        }
        return "";
    }

    private static String readFen(View board) {
        if (board == null) return "";
        try {
            Object position = board.getClass().getMethod("getPosition").invoke(board);
            String direct = invokeFen(position);
            if (isFen(direct)) return direct;

            // The legacy board wraps its StandardPosition in a field named "a".
            if (position != null) {
                for (Class<?> type = position.getClass(); type != null; type = type.getSuperclass()) {
                    try {
                        Field field = type.getDeclaredField("a");
                        field.setAccessible(true);
                        String nested = invokeFen(field.get(position));
                        if (isFen(nested)) return nested;
                    } catch (Throwable ignored) {
                        // This wrapper does not expose the legacy position field.
                    }
                }
            }
        } catch (Throwable ignored) {
            // The board can be present before the first game position is available.
        }
        return "";
    }

    private static Boolean readBooleanValue(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value == null) return null;
        String[] getters = {"getValue", "get", "isValue", "a"};
        for (String name : getters) {
            try {
                Method method = value.getClass().getMethod(name);
                if (method.getParameterTypes().length != 0) continue;
                Object result = method.invoke(value);
                if (result instanceof Boolean) return (Boolean) result;
            } catch (Throwable ignored) {
                // Try the next accessor.
            }
        }
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                        field.setAccessible(true);
                        Object result = field.get(value);
                        if (result instanceof Boolean) return (Boolean) result;
                    }
                } catch (Throwable ignored) {
                    // Ignore inaccessible implementation fields.
                }
            }
        }
        return null;
    }

    private static Boolean readDelegatedBoolean(View board, Object delegate) {
        if (delegate == null) return null;
        // ChessBoardView.isBoardFlipped is a Kotlin delegated property. Its generated
        // delegate exposes getValue(thisRef, property), not a zero-argument getter.
        for (Method method : delegate.getClass().getMethods()) {
            if (!"getValue".equals(method.getName()) || method.getParameterTypes().length != 2) continue;
            try {
                method.setAccessible(true);
                Object value = method.invoke(delegate, board, null);
                Boolean result = readBooleanValue(value);
                if (result != null) return result;
            } catch (Throwable ignored) {
                // Continue to the delegate's backing state.
            }
        }
        // Fallback for the delegated property holder's backing-state field.
        for (Class<?> type = delegate.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field state = type.getDeclaredField("c");
                state.setAccessible(true);
                Boolean result = readBooleanValue(state.get(delegate));
                if (result != null) return result;
            } catch (Throwable ignored) {
                // The delegate may use a different implementation.
            }
        }
        return null;
    }

    private static boolean isBoardFlipped(View board) {
        if (board == null) return false;
        String[] getters = {"getFlipBoard", "isBoardFlipped", "getBoardFlipped", "getIsBoardFlipped"};
        for (String name : getters) {
            try {
                Object value = board.getClass().getMethod(name).invoke(board);
                Boolean result = readBooleanValue(value);
                if (result != null) return result;
            } catch (Throwable ignored) {
                // Read the observable property exposed by ChessBoardView below.
            }
        }
        try {
            for (Class<?> type = board.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField("isBoardFlipped");
                    field.setAccessible(true);
                    Boolean result = readDelegatedBoolean(board, field.get(board));
                    if (result != null) return result;
                } catch (NoSuchFieldException ignored) {
                    // Continue through superclasses.
                }
            }
        } catch (Throwable ignored) {
            // Default orientation is White at the bottom.
        }
        return false;
    }

    private static boolean isFen(String fen) {
        if (fen == null || fen.isEmpty() || fen.length() > 120) return false;
        String[] fields = fen.split("\\s+");
        if (fields.length != 6) return false;
        if (!fields[1].matches("[wb]")) return false;
        if (!fields[2].matches("(-|[KQkq]+)")) return false;
        if (!fields[3].matches("(-|[a-h][36])")) return false;
        return fields[0].matches("[rnbqkpRNBQKP1-8/]+")
                && fields[4].matches("\\d+")
                && fields[5].matches("\\d+");
    }

    private static EngineResult fetchAnalysis(String fen) {
        EngineResult multiPv = fetchChessApi(fen);
        if (multiPv.error == null && multiPv.candidates.length > 0) return multiPv;

        // Keep the previous service as a fallback so the original best-move feature
        // remains available when the MultiPV provider is temporarily unreachable.
        EngineResult fallback = fetchStockfish(fen);
        if (fallback.error == null && !fallback.bestMove.isEmpty()) {
            boolean whiteToMove = fen.split("\\s+")[1].equals("w");
            CandidateMove single = new CandidateMove(
                    fallback.bestMove, fallback.evaluation, fallback.mate,
                    ENGINE_DEPTH, whiteToMove, "MELHOR"
            );
            return new EngineResult(fallback.bestMove, fallback.evaluation, fallback.mate,
                    null, new CandidateMove[]{single});
        }
        String primaryError = multiPv.error == null ? "nenhuma variação recebida" : multiPv.error;
        String fallbackError = fallback.error == null ? "sem melhor jogada" : fallback.error;
        return EngineResult.error(primaryError + " (reserva: " + fallbackError + ")");
    }

    private static EngineResult fetchChessApi(String fen) {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("fen", fen);
            payload.put("depth", ENGINE_DEPTH);
            payload.put("maxThinkingTime", ENGINE_MAX_THINKING_TIME_MS);
            payload.put("variants", ENGINE_VARIANTS);

            connection = (HttpURLConnection) new URL(CHESS_API_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "ChessAssistant-Morphe/1.1");

            byte[] requestBody = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
                output.flush();
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return EngineResult.error("HTTP " + code);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            if (code < 200 || code >= 300) return EngineResult.error("chess-api HTTP " + code);

            JSONObject json = new JSONObject(body.toString());
            if ("error".equalsIgnoreCase(json.optString("type", ""))) {
                return EngineResult.error(json.optString("text", "erro retornado pelo serviço"));
            }

            boolean whiteToMove = fen.split("\\s+")[1].equals("w");
            ArrayList<CandidateMove> found = new ArrayList<>();

            CandidateMove primary = parseCandidate(json, whiteToMove);
            if (primary != null) found.add(primary);

            JSONArray variants = json.optJSONArray("variants");
            if (variants != null) {
                for (int i = 0; i < variants.length(); i++) {
                    JSONObject item = variants.optJSONObject(i);
                    if (item == null) continue;
                    CandidateMove candidate = parseCandidate(item, whiteToMove);
                    if (candidate == null) continue;

                    boolean duplicate = false;
                    for (CandidateMove current : found) {
                        if (current.move.equals(candidate.move)) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) found.add(candidate);
                }
            }

            if (found.isEmpty()) {
                return EngineResult.error(json.optString("text", "o serviço não retornou jogadas"));
            }

            Collections.sort(found, (left, right) ->
                    Double.compare(right.scoreForMover(), left.scoreForMover()));

            ArrayList<CandidateMove> selected = new ArrayList<>();
            CandidateMove best = found.get(0);
            selected.add(best.withLabel("MELHOR"));

            if (found.size() > 1) {
                CandidateMove good = found.get(1);
                double loss = best.scoreForMover() - good.scoreForMover();
                selected.add(good.withLabel(loss <= 0.75 ? "BOA" : "MEDIANA"));
            }

            if (found.size() > 2) {
                CandidateMove lessFavorable = found.get(found.size() - 1);
                double loss = best.scoreForMover() - lessFavorable.scoreForMover();
                boolean clearlyBad = loss >= 1.20
                        || (lessFavorable.mate != 0
                        && (whiteToMove ? lessFavorable.mate < 0 : lessFavorable.mate > 0));
                selected.add(lessFavorable.withLabel(clearlyBad ? "RUIM" : "MENOS BOA"));
            }

            CandidateMove[] candidates = selected.toArray(new CandidateMove[0]);
            return new EngineResult(best.move, best.evaluation, best.mate, null, candidates);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || message.trim().isEmpty()) message = "falha na análise MultiPV";
            if (message.length() > 90) message = message.substring(0, 90);
            return EngineResult.error(message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static CandidateMove parseCandidate(JSONObject json, boolean whiteToMove) {
        String move = json.optString("move", "").trim();
        if (!isUciMove(move)) move = json.optString("lan", "").trim();
        if (!isUciMove(move)) return null;

        double evaluation = json.optDouble("eval", Double.NaN);
        if (Double.isNaN(evaluation)) {
            String centipawns = json.optString("centipawns", "");
            try {
                evaluation = Double.parseDouble(centipawns) / 100.0;
            } catch (NumberFormatException ignored) {
                evaluation = 0.0;
            }
        }

        int mate = json.isNull("mate") ? 0 : json.optInt("mate", 0);
        int depth = json.optInt("depth", ENGINE_DEPTH);
        return new CandidateMove(move, evaluation, mate, depth, whiteToMove, "");
    }

    private static boolean isUciMove(String move) {
        return move != null && move.matches("(?i)^[a-h][1-8][a-h][1-8][qrbn]?$");
    }

    private static EngineResult fetchStockfish(String fen) {
        HttpURLConnection connection = null;
        try {
            String query = "fen=" + URLEncoder.encode(fen, "UTF-8")
                    + "&depth=" + ENGINE_DEPTH;
            URL url = new URL(STOCKFISH_URL + "?" + query);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ChessAssistant-Morphe/1.0");

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return EngineResult.error("HTTP " + code);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            if (code < 200 || code >= 300) return EngineResult.error("HTTP " + code);
            JSONObject json = new JSONObject(body.toString());
            if (!json.optBoolean("success", false)) {
                return EngineResult.error("a API recusou a posição");
            }

            String bestMoveField = json.optString("bestmove", "");
            Matcher moveMatcher = Pattern.compile(
                    "bestmove\\s+([a-h][1-8][a-h][1-8][qrbn]?)"
            ).matcher(bestMoveField);
            String move = moveMatcher.find() ? moveMatcher.group(1) : "";

            int mateIn = 0;
            Object mateValue = json.opt("mate");
            if (mateValue instanceof Number) mateIn = ((Number) mateValue).intValue();

            double eval = json.optDouble("evaluation", 0.0);
            return new EngineResult(move, eval, mateIn, null);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || message.trim().isEmpty()) message = "falha de conexão";
            if (message.length() > 70) message = message.substring(0, 70);
            return EngineResult.error(message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static final class CandidateMove {
        final String move;
        final double evaluation;
        final int mate;
        final int depth;
        final boolean whiteToMove;
        final String label;

        CandidateMove(String move, double evaluation, int mate, int depth,
                      boolean whiteToMove, String label) {
            this.move = move;
            this.evaluation = evaluation;
            this.mate = mate;
            this.depth = depth;
            this.whiteToMove = whiteToMove;
            this.label = label;
        }

        double scoreForMover() {
            if (mate != 0) {
                boolean moverIsMating = whiteToMove ? mate > 0 : mate < 0;
                return moverIsMating ? 1000.0 - Math.abs(mate) : -1000.0 + Math.abs(mate);
            }
            return whiteToMove ? evaluation : -evaluation;
        }

        CandidateMove withLabel(String value) {
            return new CandidateMove(move, evaluation, mate, depth, whiteToMove, value);
        }
    }

    private static final class EngineResult {
        final String bestMove;
        final double evaluation;
        final int mate;
        final String error;
        final CandidateMove[] candidates;

        EngineResult(String bestMove, double evaluation, int mate, String error) {
            this(bestMove, evaluation, mate, error, new CandidateMove[0]);
        }

        EngineResult(String bestMove, double evaluation, int mate, String error,
                     CandidateMove[] candidates) {
            this.bestMove = bestMove;
            this.evaluation = evaluation;
            this.mate = mate;
            this.error = error;
            this.candidates = candidates == null ? new CandidateMove[0] : candidates;
        }

        static EngineResult error(String error) {
            return new EngineResult("", 0.0, 0, error, new CandidateMove[0]);
        }
    }

    private static final class BoardDrawing extends View {
        static final int[] MOVE_COLORS = {
                0xFF37D69A, // Best
                0xFFFFC857, // Good / medium
                0xFFF07878  // Least favorable
        };

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fromSquarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint toSquarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private View board;
        private CandidateMove[] candidates = new CandidateMove[0];
        private double evaluation = 0.0;
        private int mate = 0;
        private boolean flipped;

        BoardDrawing(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setBoard(View value) {
            if (board != value) board = value;
            invalidate();
        }

        void setEngine(CandidateMove[] moves, double eval, int mateIn, boolean isFlipped) {
            candidates = moves == null ? new CandidateMove[0] : moves;
            evaluation = eval;
            mate = mateIn;
            flipped = isFlipped;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (board == null || !board.isShown() || board.getWidth() <= 0 || board.getHeight() <= 0) {
                return;
            }

            int[] boardLocation = new int[2];
            int[] overlayLocation = new int[2];
            board.getLocationOnScreen(boardLocation);
            getLocationOnScreen(overlayLocation);

            float size = Math.min(board.getWidth(), board.getHeight());
            float left = boardLocation[0] - overlayLocation[0]
                    + (board.getWidth() - size) / 2.0f;
            float top = boardLocation[1] - overlayLocation[1]
                    + (board.getHeight() - size) / 2.0f;
            float square = size / 8.0f;

            // Evaluation bar along the board's left edge. White occupies the upper segment.
            float normalized = (float) (0.5 + 0.5 * Math.tanh(evaluation / 3.5));
            if (mate > 0) normalized = 1.0f;
            if (mate < 0) normalized = 0.0f;
            float barWidth = dp(getContext(), 5);
            float barGap = dp(getContext(), 4);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC111318);
            canvas.drawRoundRect(left - barGap - barWidth, top, left - barGap,
                    top + size, dp(getContext(), 2), dp(getContext(), 2), paint);
            paint.setColor(0xFFF4F1E8);
            canvas.drawRoundRect(left - barGap - barWidth, top,
                    left - barGap, top + size * (1.0f - normalized),
                    dp(getContext(), 2), dp(getContext(), 2), paint);

            if (candidates.length == 0) return;

            // Draw weaker alternatives first so the best move remains visually on top.
            for (int i = candidates.length - 1; i >= 0; i--) {
                drawCandidate(canvas, candidates[i], i, left, top, square);
            }
        }

        private void drawCandidate(Canvas canvas, CandidateMove candidate, int index,
                                   float left, float top, float square) {
            String move = candidate.move;
            if (!isUciMove(move)) return;

            int fromFile = move.charAt(0) - 'a';
            int fromRank = move.charAt(1) - '1';
            int toFile = move.charAt(2) - 'a';
            int toRank = move.charAt(3) - '1';

            int fromColumn = flipped ? 7 - fromFile : fromFile;
            int fromRow = flipped ? fromRank : 7 - fromRank;
            int toColumn = flipped ? 7 - toFile : toFile;
            int toRow = flipped ? toRank : 7 - toRank;

            float startX = left + (fromColumn + 0.5f) * square;
            float startY = top + (fromRow + 0.5f) * square;
            float endX = left + (toColumn + 0.5f) * square;
            float endY = top + (toRow + 0.5f) * square;

            if (index == 0) {
                float inset = square * 0.055f;
                fromSquarePaint.setColor(0x6638BDF8);
                toSquarePaint.setColor(0x8864DD7F);
                canvas.drawRoundRect(
                        new RectF(left + fromColumn * square + inset,
                                top + fromRow * square + inset,
                                left + (fromColumn + 1) * square - inset,
                                top + (fromRow + 1) * square - inset),
                        square * 0.09f, square * 0.09f, fromSquarePaint);
                canvas.drawRoundRect(
                        new RectF(left + toColumn * square + inset,
                                top + toRow * square + inset,
                                left + (toColumn + 1) * square - inset,
                                top + (toRow + 1) * square - inset),
                        square * 0.09f, square * 0.09f, toSquarePaint);
            }

            int color = MOVE_COLORS[Math.min(index, MOVE_COLORS.length - 1)];
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(index == 0
                    ? Math.max(dp(getContext(), 3), square * 0.075f)
                    : Math.max(dp(getContext(), 2), square * 0.045f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(startX, startY, endX, endY, paint);

            float angle = (float) Math.atan2(endY - startY, endX - startX);
            float head = square * (index == 0 ? 0.28f : 0.20f);
            Path arrowHead = new Path();
            arrowHead.moveTo(endX, endY);
            arrowHead.lineTo(
                    endX - head * (float) Math.cos(angle - Math.PI / 5.5),
                    endY - head * (float) Math.sin(angle - Math.PI / 5.5)
            );
            arrowHead.lineTo(
                    endX - head * (float) Math.cos(angle + Math.PI / 5.5),
                    endY - head * (float) Math.sin(angle + Math.PI / 5.5)
            );
            arrowHead.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrowHead, paint);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
