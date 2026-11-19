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
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
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
    private static final String CHESS_API_HOST = "chess-api.com";
    private static final String CHESS_API_PATH = "/v1";
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
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress(CHESS_API_HOST, 443), 8000);
            socket.setSoTimeout(16000);

            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String webSocketKey = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String request = "GET " + CHESS_API_PATH + " HTTP/1.1\r\n"
                    + "Host: " + CHESS_API_HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + webSocketKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Origin: https://chess-api.com\r\n"
                    + "User-Agent: ChessAssistant-Morphe/1.2\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String handshake = readWebSocketHandshake(input);
            if (!handshake.startsWith("HTTP/1.1 101") && !handshake.startsWith("HTTP/1.0 101")) {
                return EngineResult.error("WebSocket recusado pelo serviço");
            }
            String expectedAccept = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1").digest(
                            (webSocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                    .getBytes(StandardCharsets.US_ASCII)
                    ),
                    Base64.NO_WRAP
            );
            if (!handshake.toLowerCase(java.util.Locale.US)
                    .contains("sec-websocket-accept: " + expectedAccept.toLowerCase(java.util.Locale.US))) {
                return EngineResult.error("handshake WebSocket inválido");
            }

            JSONObject payload = new JSONObject();
            payload.put("fen", fen);
            payload.put("depth", ENGINE_DEPTH);
            payload.put("maxThinkingTime", ENGINE_MAX_THINKING_TIME_MS);
            payload.put("variants", ENGINE_VARIANTS);
            sendWebSocketFrame(output, 0x1, payload.toString().getBytes(StandardCharsets.UTF_8));

            boolean whiteToMove = fen.split("\\s+")[1].equals("w");
            ArrayList<CandidateMove> found = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 15500L;

            while (System.currentTimeMillis() < deadline) {
                String message;
                try {
                    message = readWebSocketTextMessage(new DataInputStream(input), output);
                } catch (SocketTimeoutException timeout) {
                    break;
                }
                if (message == null) break;

                String trimmed = message.trim();
                JSONObject json;
                try {
                    if (trimmed.toLowerCase(java.util.Locale.US).startsWith("bestmove ")) {
                        String[] tokens = trimmed.split("\\s+");
                        json = new JSONObject();
                        json.put("type", "bestmove");
                        if (tokens.length > 1) json.put("move", tokens[1]);
                    } else {
                        json = new JSONObject(trimmed);
                    }
                } catch (Exception ignored) {
                    continue;
                }

                String type = json.optString("type", "").toLowerCase(java.util.Locale.US);
                if ("error".equals(type) || json.has("error")) {
                    return EngineResult.error(json.optString("text",
                            json.optString("error", "erro do serviço MultiPV")));
                }

                collectCandidatePayload(json, whiteToMove, found);

                // Keep collecting and refreshing unique move candidates until the
                // service marks the analysis complete with its final bestmove event.
                if ("bestmove".equals(type)) break;
            }

            if (found.isEmpty()) {
                return EngineResult.error("o WebSocket não retornou variantes");
            }

            Collections.sort(found, (left, right) ->
                    Double.compare(right.scoreForMover(), left.scoreForMover()));

            // Keep at most the requested MultiPV alternatives. Distinct first moves
            // are retained so all arrows can be drawn simultaneously on the board.
            while (found.size() > ENGINE_VARIANTS) found.remove(found.size() - 1);

            CandidateMove best = found.get(0).withLabel("MELHOR");
            ArrayList<CandidateMove> selected = new ArrayList<>();
            selected.add(best);

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
            if (message == null || message.trim().isEmpty()) message = "falha na conexão MultiPV";
            if (message.length() > 90) message = message.substring(0, 90);
            return EngineResult.error(message);
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void collectCandidatePayload(JSONObject json, boolean whiteToMove,
                                                 ArrayList<CandidateMove> found) {
        CandidateMove primary = parseCandidate(json, whiteToMove);
        if (primary != null) addOrUpdateCandidate(found, primary);

        String[] arrayKeys = {"variants", "moves", "lines", "pvs"};
        for (String key : arrayKeys) {
            JSONArray array = json.optJSONArray(key);
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                CandidateMove candidate = parseCandidate(item, whiteToMove);
                if (candidate != null) addOrUpdateCandidate(found, candidate);
            }
        }
    }

    private static void addOrUpdateCandidate(ArrayList<CandidateMove> candidates, CandidateMove incoming) {
        for (int i = 0; i < candidates.size(); i++) {
            CandidateMove current = candidates.get(i);
            if (current.move.equalsIgnoreCase(incoming.move)) {
                if (incoming.depth >= current.depth) candidates.set(i, incoming);
                return;
            }
        }
        candidates.add(incoming);
    }

    private static String readWebSocketHandshake(BufferedInputStream input) throws Exception {
        StringBuilder headers = new StringBuilder();
        int matched = 0;
        while (headers.length() < 16384) {
            int value = input.read();
            if (value < 0) throw new EOFException("handshake interrompido");
            headers.append((char) value);
            if ((matched == 0 || matched == 2) && value == '\r') matched++;
            else if ((matched == 1 || matched == 3) && value == '\n') {
                matched++;
                if (matched == 4) return headers.toString();
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new IOException("cabeçalhos WebSocket muito longos");
    }

    private static void sendWebSocketFrame(BufferedOutputStream output, int opcode, byte[] payload)
            throws IOException {
        SecureRandom random = new SecureRandom();
        byte[] mask = new byte[4];
        random.nextBytes(mask);

        output.write(0x80 | (opcode & 0x0F));
        int length = payload == null ? 0 : payload.length;
        if (length < 126) {
            output.write(0x80 | length);
        } else if (length <= 65535) {
            output.write(0x80 | 126);
            output.write((length >>> 8) & 0xFF);
            output.write(length & 0xFF);
        } else {
            output.write(0x80 | 127);
            long longLength = length;
            for (int i = 7; i >= 0; i--) output.write((int) (longLength >>> (8 * i)) & 0xFF);
        }

        output.write(mask);
        if (payload != null) {
            byte[] masked = new byte[length];
            for (int i = 0; i < length; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
            output.write(masked);
        }
        output.flush();
    }

    private static String readWebSocketTextMessage(DataInputStream input, BufferedOutputStream output)
            throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean receivingText = false;

        while (true) {
            int first = input.readUnsignedByte();
            int second = input.readUnsignedByte();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;

            if (length == 126) {
                length = ((long) input.readUnsignedByte() << 8) | input.readUnsignedByte();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | input.readUnsignedByte();
            }
            if (length < 0 || length > 1024 * 1024) {
                throw new IOException("quadro WebSocket excedeu o limite");
            }

            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                input.readFully(mask);
            }
            byte[] payload = new byte[(int) length];
            input.readFully(payload);
            if (masked && mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i & 3]);
            }

            if (opcode == 0x8) return null;
            if (opcode == 0x9) {
                sendWebSocketFrame(output, 0xA, payload);
                continue;
            }
            if (opcode == 0xA) continue;

            if (opcode == 0x1) {
                message.reset();
                message.write(payload);
                receivingText = true;
            } else if (opcode == 0x0 && receivingText) {
                message.write(payload);
            } else {
                continue;
            }

            if (fin && receivingText) {
                return new String(message.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static CandidateMove parseCandidate(JSONObject json, boolean whiteToMove) {
        String move = json.optString("move", "").trim();
        if (!isUciMove(move)) move = json.optString("lan", "").trim();
        if (!isUciMove(move)) move = json.optString("uci", "").trim();
        if (!isUciMove(move)) move = json.optString("bestmove", "").trim();
        if (!isUciMove(move)) {
            String from = json.optString("from", "").trim();
            String to = json.optString("to", "").trim();
            if (from.matches("(?i)^[a-h][1-8]$") && to.matches("(?i)^[a-h][1-8]$")) {
                move = from + to;
                String promotion = json.optString("promotion", "").trim();
                if (promotion.matches("(?i)^[qrbn]$")) move += promotion;
            }
        }
        if (!isUciMove(move)) return null;
        move = move.toLowerCase(java.util.Locale.US);

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

            // Weaker alternatives are painted first; the best move remains on top.
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

            int color = MOVE_COLORS[Math.min(index, MOVE_COLORS.length - 1)];

            // Highlight every suggested move: origin in blue, destination in that move's arrow color.
            float inset = square * 0.055f;
            fromSquarePaint.setColor(0x6638BDF8);
            toSquarePaint.setColor((color & 0x00FFFFFF) | 0x88000000);
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
