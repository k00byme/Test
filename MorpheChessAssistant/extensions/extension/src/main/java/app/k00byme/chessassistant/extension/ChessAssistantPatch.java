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
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
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
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime code merged into Chess.com by Morphe.
 *
 * This extension is installed from online games, Play-vs-Bots, and standalone analysis.
 * It draws visual assistance on all supported screens; automatic move input remains bot-only.
 */
public final class ChessAssistantPatch {
    private static final String TAG = "morphe.chess.assistant.analysis";
    private static final String BOARD_CLASS_V2 = "com.chess.chessboard.v2.ChessBoardView";
    private static final String BOARD_CLASS_V1 = "com.chess.chessboard.view.ChessBoardView";
    private static final String CHESS_API_HOST = "chess-api.com";
    private static final String CHESS_API_PATH = "/v1";
    private static final String CHESS_API_URL = "https://chess-api.com/v1";
    private static final String STOCKFISH_URL = "https://stockfish.online/api/s/v2.php";
    private static final int ENGINE_DEPTH = 12;
    private static final int ENGINE_VARIANTS = 2;
    private static final int ENGINE_MAX_THINKING_TIME_MS = 100;
    private static final long BOARD_POLL_INTERVAL_MS = 350L;
    private static final long ENGINE_TIMEOUT_MS = 15000L;
    private static final long AUTO_MIN_DELAY_MS = 2000L;
    private static final long AUTO_MAX_DELAY_MS = 5000L;
    private static final int SPECIAL_ENGINE_DEPTH = 18;
    private static final int SPECIAL_ENGINE_VARIANTS = 4;
    private static final int SPECIAL_ENGINE_MAX_THINKING_TIME_MS = 100;
    private static final long SPECIAL_ENGINE_TIMEOUT_MS = 15000L;
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
        private final ExecutorService specialWorker = Executors.newSingleThreadExecutor();
        private final TextView panel;
        private final BoardDrawing drawing;
        private final boolean botGame;
        private final TextView autoButton;
        private final Random autoSelector = new Random();
        private final Random autoTiming = new Random();
        private final Runnable poller = new Runnable() {
            @Override public void run() {
                scanAndAnalyze();
                if (isAttachedToWindow()) handler.postDelayed(this, BOARD_POLL_INTERVAL_MS);
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
        private boolean autoEnabled;
        private boolean autoMoveInProgress;
        private String lastAutoFen = "";
        private boolean specialRequestInFlight;
        private String specialRequestedFen = "";
        private String analysisSummary = "";
        private String specialMessage = "";
        private String specialMove = "";

        AnalysisOverlay(Activity activity) {
            super(activity);
            activityRef = new WeakReference<>(activity);
            botGame = "com.chess.features.versusbots.game.BotGameActivityV2".equals(activity.getClass().getName());
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

            if (botGame) {
                autoButton = new TextView(activity);
                autoButton.setTextSize(12.5f);
                autoButton.setTypeface(Typeface.create("sans-serif-bold", Typeface.NORMAL));
                autoButton.setTextColor(0xFFFFFFFF);
                autoButton.setGravity(Gravity.CENTER);
                autoButton.setPadding(dp(activity, 16), dp(activity, 9), dp(activity, 16), dp(activity, 9));
                autoButton.setMinHeight(dp(activity, 38));
                autoButton.setElevation(dp(activity, 7));
                autoButton.setClickable(true);
                autoButton.setFocusable(true);
                addView(autoButton, new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                ));
                autoButton.setVisibility(View.GONE);
                updateAutoButton();
                autoButton.setOnClickListener(view -> {
                    autoEnabled = !autoEnabled;
                    updateAutoButton();
                    if (autoEnabled) {
                        setStatus("AUTO ligada — aguardando sua vez.");
                        maybeAutoMove(activeFen);
                    } else {
                        setStatus("AUTO desligada.");
                    }
                });
            } else {
                autoButton = null;
            }
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
            specialWorker.shutdownNow();
            super.onDetachedFromWindow();
        }

        private void scanAndAnalyze() {
            Activity activity = activityRef.get();
            if (closed || activity == null || activity.isFinishing()) return;

            View content = activity.findViewById(android.R.id.content);
            View found = findBoard(content);
            if (found == null) {
                boardView = null;
                if (autoButton != null) autoButton.setVisibility(View.GONE);
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
                if (autoButton != null) autoButton.setVisibility(View.GONE);
                activeFen = "";
                requestedFen = "";
                clearAnalysis();
                drawing.setBoard(found);
                setStatus("Aguardando uma posição válida do tabuleiro…");
                return;
            }

            drawing.setBoard(found);
            positionAutoButton(found);
            if (!fen.equals(activeFen)) {
                activeFen = fen;
                autoMoveInProgress = false;
                clearAnalysis();
            }

            if (!fen.equals(requestedFen) && !requestInFlight) requestAnalysis(fen);
            drawing.setEngine(candidates, evaluation, mate, isBoardFlipped(found));
            if (autoEnabled) maybeAutoMove(fen);
        }

        private void clearAnalysis() {
            bestMove = "";
            evaluation = 0.0;
            mate = 0;
            candidates = new CandidateMove[0];
            analysisSummary = "";
            specialMessage = "";
            specialMove = "";
            specialRequestedFen = "";
            drawing.setSpecialMove("");
            drawing.setEngine(candidates, evaluation, mate, boardView != null && isBoardFlipped(boardView));
        }

        private void requestAnalysis(final String fen) {
            requestedFen = fen;
            requestInFlight = true;
            setStatus("Stockfish analisando duas alternativas…");
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
                        String mode = candidates.length >= 2 ? "2 opções" : candidates.length + " opção(ões)";
                        analysisSummary = "STOCKFISH 18  •  profundidade " + ENGINE_DEPTH
                                + "\nAvaliação: " + evalText
                                + "\n" + mode;
                        setStatus(analysisSummary);
                        drawing.setEngine(candidates, evaluation, mate, isBoardFlipped(boardView));
                        if (candidates.length >= 2) requestSpecialAnalysis(fen, candidates.clone());
                        if (autoEnabled) maybeAutoMove(fen);
                    });
                });
            } catch (RuntimeException ex) {
                requestInFlight = false;
                setStatus("Não foi possível iniciar a análise.");
            }
        }



        /**
         * A second, deeper pass is deliberately separate from normal assistance.
         * It runs only when the quick MultiPV result suggests a unique line,
         * a tactical sacrifice, a decisive continuation, or a forced mate.
         */
        private boolean shouldRequestSpecialAnalysis(String fen, CandidateMove[] normal) {
            // Do not pre-filter with shallow scores: hidden sacrifices and quiet tactical
            // moves often look ordinary until the deeper pass examines the position.
            return isFen(fen) && normal != null && normal.length > 0;
        }

        private void requestSpecialAnalysis(final String fen, final CandidateMove[] normal) {
            if (closed || !isAttachedToWindow() || !fen.equals(activeFen)
                    || !shouldRequestSpecialAnalysis(fen, normal)
                    || fen.equals(specialRequestedFen) || specialRequestInFlight) return;

            specialRequestedFen = fen;
            specialRequestInFlight = true;
            try {
                specialWorker.execute(() -> {
                    EngineResult deep = fetchSpecialAnalysis(fen);
                    final SpecialFinding finding = deep.error == null
                            ? findSpecialFinding(fen, normal, deep) : null;
                    MAIN.post(() -> {
                        specialRequestInFlight = false;
                        if (closed || !isAttachedToWindow()) return;

                        // Ignore stale deep results if the board has already advanced.
                        if (!fen.equals(activeFen)) {
                            if (isFen(activeFen) && candidates.length >= 1) {
                                requestSpecialAnalysis(activeFen, candidates.clone());
                            }
                            return;
                        }

                        specialMove = finding == null ? "" : finding.candidate.move;
                        specialMessage = finding == null ? "" : finding.title
                                + "\n" + finding.detail;
                        drawing.setSpecialMove(specialMove);
                        if (finding != null) {
                            setStatus(analysisSummary + "\n\n✦ ASSISTÊNCIA ESPECIAL\n"
                                    + specialMessage + "\nLance: " + formatMove(specialMove));
                        }
                    });
                });
            } catch (RuntimeException ex) {
                specialRequestInFlight = false;
            }
        }

        private static String formatMove(String move) {
            if (!isUciMove(move)) return move == null ? "" : move;
            return move.substring(0, 2) + " → " + move.substring(2, 4)
                    + (move.length() == 5 ? " = " + move.substring(4).toUpperCase(java.util.Locale.US) : "");
        }

        private void updateAutoButton() {
            if (autoButton == null) return;
            autoButton.setText(autoEnabled ? "AUTO  •  LIGADA" : "AUTO  •  DESLIGADA");
            GradientDrawable background = new GradientDrawable();
            background.setColor(autoEnabled ? 0xFF81B64C : 0xFF3B414B);
            background.setCornerRadius(dp(getContext(), 18));
            background.setStroke(dp(getContext(), 1), autoEnabled ? 0xFFB7DB8A : 0xFF68717E);
            autoButton.setBackground(background);
        }

        /** Place the control in free space around the board; never cover a square. */
        private void positionAutoButton(View board) {
            if (autoButton == null || board == null || getWidth() <= 0 || getHeight() <= 0) return;

            autoButton.measure(
                    View.MeasureSpec.makeMeasureSpec(getWidth(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(getHeight(), View.MeasureSpec.AT_MOST)
            );
            int buttonWidth = autoButton.getMeasuredWidth();
            int buttonHeight = autoButton.getMeasuredHeight();
            int margin = dp(getContext(), 8);

            int[] boardLocation = new int[2];
            int[] overlayLocation = new int[2];
            board.getLocationOnScreen(boardLocation);
            getLocationOnScreen(overlayLocation);

            float boardSize = Math.min(board.getWidth(), board.getHeight());
            float boardLeft = boardLocation[0] - overlayLocation[0]
                    + (board.getWidth() - boardSize) / 2.0f;
            float boardTop = boardLocation[1] - overlayLocation[1]
                    + (board.getHeight() - boardSize) / 2.0f;
            float boardRight = boardLeft + boardSize;
            float boardBottom = boardTop + boardSize;

            int topSpace = Math.max(0, (int) boardTop - margin);
            int bottomSpace = Math.max(0, getHeight() - (int) boardBottom - margin);
            int leftSpace = Math.max(0, (int) boardLeft - margin);
            int rightSpace = Math.max(0, getWidth() - (int) boardRight - margin);

            FrameLayout.LayoutParams params;
            if (topSpace >= buttonHeight) {
                params = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );
                params.topMargin = Math.max(0, (topSpace - buttonHeight) / 2);
            } else if (bottomSpace >= buttonHeight) {
                params = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );
                params.topMargin = (int) boardBottom + (bottomSpace - buttonHeight) / 2;
            } else if (leftSpace >= buttonWidth) {
                params = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START
                );
                params.leftMargin = Math.max(0, (leftSpace - buttonWidth) / 2);
                params.topMargin = Math.max(0, (int) (boardTop + (boardSize - buttonHeight) / 2));
            } else if (rightSpace >= buttonWidth) {
                params = new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END
                );
                params.rightMargin = Math.max(0, (rightSpace - buttonWidth) / 2);
                params.topMargin = Math.max(0, (int) (boardTop + (boardSize - buttonHeight) / 2));
            } else {
                autoButton.setVisibility(View.GONE);
                return;
            }

            autoButton.setLayoutParams(params);
            autoButton.setVisibility(View.VISIBLE);
        }

        private void maybeAutoMove(final String fen) {
            if (!botGame || !autoEnabled || autoMoveInProgress || closed
                    || !isAttachedToWindow() || boardView == null
                    || !isFen(fen) || !fen.equals(activeFen)
                    || candidates.length == 0 || fen.equals(lastAutoFen)) return;

            Activity activity = activityRef.get();
            Boolean playerIsWhite = readPlayerColor(activity, boardView);
            if (playerIsWhite == null) return;

            boolean whiteToMove = "w".equals(fen.split("\\s+")[1]);
            if (whiteToMove != playerIsWhite) return;

            // Prefer the top engine line; choose the second line approximately once in five turns.
            CandidateMove selected = candidates[0];
            if (candidates.length > 1 && autoSelector.nextInt(5) == 0) {
                selected = candidates[1];
            }
            if (!isUciMove(selected.move)) return;
            if (selected.move.length() == 5) {
                lastAutoFen = fen;
                setStatus("AUTO: promoção detectada; escolha a peça manualmente.");
                return;
            }

            final CandidateMove move = selected;
            final View targetBoard = boardView;
            final boolean flipped = isBoardFlipped(targetBoard);
            final long delayMs = AUTO_MIN_DELAY_MS
                    + autoTiming.nextInt((int) (AUTO_MAX_DELAY_MS - AUTO_MIN_DELAY_MS + 1L));
            lastAutoFen = fen;
            autoMoveInProgress = true;
            setStatus("AUTO: jogada em " + (delayMs / 1000.0) + " s.");

            handler.postDelayed(() -> {
                if (!autoEnabled || closed || !isAttachedToWindow()
                        || !fen.equals(activeFen) || targetBoard != boardView
                        || !targetBoard.isShown()) {
                    autoMoveInProgress = false;
                    return;
                }

                if (!dispatchAutomaticMove(targetBoard, move.move, flipped, fen)) {
                    autoMoveInProgress = false;
                    setStatus("AUTO: não foi possível acionar o tabuleiro.");
                    return;
                }

                setStatus("AUTO: jogada selecionada pela análise.");
                handler.postDelayed(() -> autoMoveInProgress = false, 450L);
            }, delayMs);
        }

        private boolean dispatchAutomaticMove(View board, String move, boolean flipped, String fen) {
            if (board == null || !isUciMove(move) || !fen.equals(activeFen)) return false;
            float[] from = squareCenter(board, move.substring(0, 2), flipped);
            float[] to = squareCenter(board, move.substring(2, 4), flipped);
            if (from == null || to == null) return false;

            final long[] firstDown = {0L};
            final long[] secondDown = {0L};
            handler.postDelayed(() -> {
                if (!fen.equals(activeFen) || board != boardView) return;
                firstDown[0] = SystemClock.uptimeMillis();
                dispatchBoardTouch(board, MotionEvent.ACTION_DOWN, firstDown[0], from[0], from[1]);
            }, 20L);
            handler.postDelayed(() -> {
                if (firstDown[0] == 0L) return;
                dispatchBoardTouch(board, MotionEvent.ACTION_UP, firstDown[0], from[0], from[1]);
            }, 75L);
            handler.postDelayed(() -> {
                if (firstDown[0] == 0L || !fen.equals(activeFen) || board != boardView) return;
                secondDown[0] = SystemClock.uptimeMillis();
                dispatchBoardTouch(board, MotionEvent.ACTION_DOWN, secondDown[0], to[0], to[1]);
            }, 145L);
            handler.postDelayed(() -> {
                if (secondDown[0] == 0L) return;
                dispatchBoardTouch(board, MotionEvent.ACTION_UP, secondDown[0], to[0], to[1]);
            }, 205L);
            return true;
        }

        private float[] squareCenter(View board, String square, boolean flipped) {
            if (board == null || square == null || square.length() != 2
                    || square.charAt(0) < 'a' || square.charAt(0) > 'h'
                    || square.charAt(1) < '1' || square.charAt(1) > '8') return null;
            float size = Math.min(board.getWidth(), board.getHeight());
            if (size <= 0f) return null;
            float squareSize = size / 8.0f;
            int file = square.charAt(0) - 'a';
            int rank = square.charAt(1) - '1';
            int column = flipped ? 7 - file : file;
            int row = flipped ? rank : 7 - rank;
            float left = (board.getWidth() - size) / 2.0f;
            float top = (board.getHeight() - size) / 2.0f;
            return new float[] {
                    left + (column + 0.5f) * squareSize,
                    top + (row + 0.5f) * squareSize
            };
        }

        private void dispatchBoardTouch(View board, int action, long downTime, float x, float y) {
            long eventTime = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            try {
                board.dispatchTouchEvent(event);
            } finally {
                event.recycle();
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


    /** Resolve the human side from game metadata when possible; board orientation is the fallback. */
    private static Boolean readPlayerColor(Activity activity, View board) {
        Boolean value = playerColorFromObject(activity);
        if (value != null) return value;
        value = playerColorFromObject(board);
        if (value != null) return value;
        return board == null ? null : !isBoardFlipped(board);
    }

    private static Boolean playerColorFromObject(Object target) {
        if (target == null) return null;
        String[] colorGetters = {
                "getPlayerColor", "getUserColor", "getMyColor",
                "getPlayerSide", "getUserSide", "getPlayingColor"
        };
        for (String name : colorGetters) {
            try {
                Method method = target.getClass().getMethod(name);
                if (method.getParameterTypes().length != 0) continue;
                Boolean parsed = parsePlayerColor(method.invoke(target));
                if (parsed != null) return parsed;
            } catch (Throwable ignored) {
                // Continue through version-specific metadata accessors.
            }
        }
        String[] whiteGetters = {"isWhitePlayer", "isPlayerWhite", "getIsWhitePlayer", "isWhiteSide"};
        for (String name : whiteGetters) {
            try {
                Method method = target.getClass().getMethod(name);
                if (method.getParameterTypes().length == 0) {
                    Object result = method.invoke(target);
                    if (result instanceof Boolean) return (Boolean) result;
                }
            } catch (Throwable ignored) {
                // Continue through the available metadata.
            }
        }
        String[] blackGetters = {"isBlackPlayer", "isPlayerBlack", "getIsBlackPlayer", "isBlackSide"};
        for (String name : blackGetters) {
            try {
                Method method = target.getClass().getMethod(name);
                if (method.getParameterTypes().length == 0) {
                    Object result = method.invoke(target);
                    if (result instanceof Boolean) return !((Boolean) result);
                }
            } catch (Throwable ignored) {
                // Continue through the available metadata.
            }
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase(java.util.Locale.US);
                if (!(name.contains("playercolor") || name.contains("usercolor")
                        || name.contains("mycolor") || name.contains("playerside")
                        || name.contains("userside") || name.equals("iswhiteplayer")
                        || name.equals("isblackplayer"))) continue;
                try {
                    field.setAccessible(true);
                    Object result = field.get(target);
                    if (result instanceof Boolean && name.contains("white")) return (Boolean) result;
                    if (result instanceof Boolean && name.contains("black")) return !((Boolean) result);
                    Boolean parsed = parsePlayerColor(result);
                    if (parsed != null) return parsed;
                } catch (Throwable ignored) {
                    // Ignore unavailable obfuscated fields.
                }
            }
        }
        return null;
    }

    private static Boolean parsePlayerColor(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.US);
        if ("white".equals(text) || "w".equals(text) || "light".equals(text)
                || text.endsWith(".white")) return true;
        if ("black".equals(text) || "b".equals(text) || "dark".equals(text)
                || text.endsWith(".black")) return false;
        return null;
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


    private static EngineResult fetchSpecialAnalysis(String fen) {
        EngineResult result = fetchChessApi(
                fen,
                SPECIAL_ENGINE_DEPTH,
                SPECIAL_ENGINE_VARIANTS,
                SPECIAL_ENGINE_MAX_THINKING_TIME_MS,
                SPECIAL_ENGINE_TIMEOUT_MS,
                true
        );
        if (result.error != null) return result;
        if (result.candidates.length == 0) {
            return EngineResult.error("a análise profunda não retornou uma linha válida");
        }
        return result;
    }

    private static SpecialFinding findSpecialFinding(String fen, CandidateMove[] normal,
                                                     EngineResult deep) {
        if (deep == null || deep.error != null || deep.candidates.length == 0) return null;

        CandidateMove first = deep.candidates[0];
        CandidateMove second = deep.candidates.length > 1 ? deep.candidates[1] : null;

        // If the deep provider returns only its final best line, use the quick pass's
        // other line for comparison instead of silently disabling special detection.
        if (second == null && normal != null) {
            for (CandidateMove candidate : normal) {
                if (candidate != null && !candidate.move.equalsIgnoreCase(first.move)) {
                    second = candidate;
                    break;
                }
            }
        }

        double best = first.scoreForMover();
        double next = second == null ? Double.NaN : second.scoreForMover();
        double gap = second == null ? 0.0 : best - next;

        if (first.mate != 0 && best > 500.0) {
            return new SpecialFinding(first, "SEQUÊNCIA TÁTICA EXCEPCIONAL",
                    "A busca profunda confirmou uma linha forçada de mate.");
        }

        if (isLikelyMaterialSacrifice(fen, first.move)
                && best >= -0.35 && (second == null || gap >= 0.25)) {
            return new SpecialFinding(first, "SACRIFÍCIO COM COMPENSAÇÃO",
                    "O motor confirma que o sacrifício mantém a posição competitiva ou vencedora.");
        }

        if (second != null && best >= -0.50 && next <= -1.10 && gap >= 0.75) {
            return new SpecialFinding(first, "ÚNICA JOGADA",
                    "Esta linha mantém a posição; as alternativas analisadas deixam o lado em clara desvantagem.");
        }

        if (second != null && best >= 1.25 && gap >= 0.60) {
            return new SpecialFinding(first, "JOGADA VENCEDORA",
                    "A busca profunda encontrou uma continuação decisiva.");
        }

        if (normal != null && normal.length > 0) {
            double quickBest = normal[0].scoreForMover();
            boolean differentMove = !first.move.equalsIgnoreCase(normal[0].move);

            if (best >= 0.85 && best - quickBest >= 0.65
                    && (second == null || gap >= 0.40)) {
                return new SpecialFinding(first, "CONTINUAÇÃO OCULTA",
                        "A análise rápida não identificou a força desta linha; a busca profunda confirmou a continuação.");
            }

            if (differentMove && best >= 1.00
                    && (second == null || gap >= 0.40)) {
                return new SpecialFinding(first, "OPORTUNIDADE BRILHANTE",
                        "A busca profunda encontrou uma jogada excepcional que não aparecia como primeira opção na análise rápida.");
            }
        }

        return null;
    }

    private static boolean isLikelyMaterialSacrifice(String fen, String move) {
        if (!isUciMove(move)) return false;
        String[] fields = fen == null ? new String[0] : fen.trim().split("\\s+");
        if (fields.length != 6) return false;
        String[] ranks = fields[0].split("/");
        if (ranks.length != 8) return false;

        char[][] board = new char[8][8];
        for (int row = 0; row < 8; row++) {
            int file = 0;
            for (int i = 0; i < ranks[row].length(); i++) {
                char token = ranks[row].charAt(i);
                if (token >= '1' && token <= '8') {
                    int count = token - '0';
                    while (count-- > 0 && file < 8) board[row][file++] = '.';
                } else if (file < 8) {
                    board[row][file++] = token;
                } else {
                    return false;
                }
            }
            if (file != 8) return false;
        }

        int fromFile = move.charAt(0) - 'a';
        int fromRow = 8 - (move.charAt(1) - '0');
        int toFile = move.charAt(2) - 'a';
        int toRow = 8 - (move.charAt(3) - '0');
        if (fromRow < 0 || fromRow > 7 || toRow < 0 || toRow > 7
                || fromFile < 0 || fromFile > 7 || toFile < 0 || toFile > 7) return false;

        char piece = board[fromRow][fromFile];
        if (piece == '.' || Character.toLowerCase(piece) == 'k') return false;

        boolean moverIsWhite = Character.isUpperCase(piece);
        char captured = board[toRow][toFile];
        if (captured != '.' && Character.isUpperCase(captured) == moverIsWhite) return false;

        // Handle en passant, where the captured pawn is not on the destination square.
        if (Character.toLowerCase(piece) == 'p' && fromFile != toFile && captured == '.') {
            int capturedRow = toRow + (moverIsWhite ? 1 : -1);
            if (capturedRow < 0 || capturedRow > 7
                    || Character.toLowerCase(board[capturedRow][toFile]) != 'p'
                    || Character.isUpperCase(board[capturedRow][toFile]) == moverIsWhite) {
                return false;
            }
            board[capturedRow][toFile] = '.';
            captured = moverIsWhite ? 'p' : 'P';
        }

        char movedPiece = piece;
        if (move.length() == 5) {
            char promoted = move.charAt(4);
            movedPiece = moverIsWhite ? Character.toUpperCase(promoted)
                    : Character.toLowerCase(promoted);
        }

        // A capture of an equal- or higher-value piece is not treated as a material
        // sacrifice by this geometric pre-filter. The deep engine result is decisive.
        if (pieceValue(captured) >= pieceValue(movedPiece) && captured != '.') return false;

        board[fromRow][fromFile] = '.';
        board[toRow][toFile] = movedPiece;

        return isSquareAttacked(board, toRow, toFile, !moverIsWhite);
    }

    private static int pieceValue(char piece) {
        switch (Character.toLowerCase(piece)) {
            case 'p': return 1;
            case 'n':
            case 'b': return 3;
            case 'r': return 5;
            case 'q': return 9;
            case 'k': return 100;
            default: return 0;
        }
    }

    private static boolean isSquareAttacked(char[][] board, int targetRow, int targetFile,
                                            boolean byWhite) {
        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                char piece = board[row][file];
                if (piece == '.' || Character.isUpperCase(piece) != byWhite) continue;
                char type = Character.toLowerCase(piece);
                int dr = targetRow - row;
                int df = targetFile - file;

                if (type == 'p') {
                    if (Math.abs(df) == 1 && dr == (byWhite ? -1 : 1)) return true;
                } else if (type == 'n') {
                    if ((Math.abs(dr) == 2 && Math.abs(df) == 1)
                            || (Math.abs(dr) == 1 && Math.abs(df) == 2)) return true;
                } else if (type == 'k') {
                    if (Math.max(Math.abs(dr), Math.abs(df)) == 1) return true;
                } else {
                    boolean diagonal = Math.abs(dr) == Math.abs(df) && dr != 0;
                    boolean straight = (dr == 0) != (df == 0);
                    if ((type == 'b' && !diagonal)
                            || (type == 'r' && !straight)
                            || (type == 'q' && !diagonal && !straight)) continue;

                    int stepRow = Integer.signum(dr);
                    int stepFile = Integer.signum(df);
                    int r = row + stepRow;
                    int f = file + stepFile;
                    boolean clear = true;
                    while (r != targetRow || f != targetFile) {
                        if (board[r][f] != '.') { clear = false; break; }
                        r += stepRow;
                        f += stepFile;
                    }
                    if (clear) return true;
                }
            }
        }
        return false;
    }

    private static EngineResult fetchAnalysis(String fen) {
        EngineResult multiPv = fetchChessApi(fen);
        if (multiPv.error == null && multiPv.candidates.length > 0) return multiPv;

        // The REST endpoint returns the completed result instead of a progressive
        // stream, so it is a useful second path when the WebSocket is unavailable.
        EngineResult rest = fetchChessApiHttp(
                fen, ENGINE_DEPTH, ENGINE_VARIANTS, ENGINE_MAX_THINKING_TIME_MS
        );
        if (rest.error == null && rest.candidates.length > 0) return rest;

        // Preserve the original Stockfish Online provider as the last fallback.
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

        String primaryError = multiPv.error == null ? "nenhuma variante recebida" : multiPv.error;
        String restError = rest.error == null ? "nenhuma jogada no retorno REST" : rest.error;
        String fallbackError = fallback.error == null ? "o serviço não retornou uma jogada" : fallback.error;
        return EngineResult.error("WebSocket: " + primaryError
                + " | REST: " + restError + " | Reserva: " + fallbackError);
    }

    private static EngineResult fetchChessApiHttp(String fen, int depth, int variants,
                                                  int maxThinkingTimeMs) {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("fen", fen);
            payload.put("depth", depth);
            payload.put("maxThinkingTime", maxThinkingTimeMs);
            payload.put("variants", variants);

            connection = (HttpURLConnection) new URL(CHESS_API_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "ChessAssistant-Morphe/2.0");

            byte[] requestBody = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
                output.flush();
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return EngineResult.error("Chess API REST HTTP " + code);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            if (code < 200 || code >= 300) {
                return EngineResult.error("Chess API REST HTTP " + code + ": " + body);
            }

            JSONObject json = new JSONObject(body.toString());
            if ("error".equalsIgnoreCase(json.optString("type", ""))
                    || json.has("error")
                    || (json.has("success") && !json.optBoolean("success", false))) {
                return EngineResult.error(readServiceError(json, "o serviço REST recusou a posição"));
            }

            boolean whiteToMove = fen.split("\\s+")[1].equals("w");
            ArrayList<CandidateMove> found = new ArrayList<>();
            collectCandidatePayload(json, whiteToMove, found, depth);
            if (found.isEmpty()) {
                return EngineResult.error(json.optString("text", "o serviço REST não retornou uma jogada válida"));
            }

            Collections.sort(found, (left, right) ->
                    Double.compare(right.scoreForMover(), left.scoreForMover()));
            while (found.size() > variants) found.remove(found.size() - 1);

            CandidateMove best = found.get(0).withLabel("EXCELENTE");
            ArrayList<CandidateMove> selected = new ArrayList<>();
            selected.add(best);
            if (found.size() > 1) {
                CandidateMove second = found.get(1);
                double loss = best.scoreForMover() - second.scoreForMover();
                selected.add(second.withLabel(loss <= 0.75 ? "BOA" : "MEDIANA"));
            }

            CandidateMove[] candidates = selected.toArray(new CandidateMove[0]);
            return new EngineResult(best.move, best.evaluation, best.mate, null, candidates);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || message.trim().isEmpty()) message = "falha no Chess API REST";
            if (message.length() > 90) message = message.substring(0, 90);
            return EngineResult.error(message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readServiceError(JSONObject json, String fallback) {
        Object detail = json.opt("data");
        if (detail == null || detail == JSONObject.NULL) detail = json.opt("error");
        if (detail == null || detail == JSONObject.NULL) detail = json.opt("message");
        String message = detail == null || detail == JSONObject.NULL
                ? fallback : String.valueOf(detail);
        if (message.length() > 90) message = message.substring(0, 90);
        return message;
    }

    private static EngineResult fetchChessApi(String fen) {
        return fetchChessApi(fen, ENGINE_DEPTH, ENGINE_VARIANTS,
                ENGINE_MAX_THINKING_TIME_MS, ENGINE_TIMEOUT_MS, true);
    }

    private static EngineResult fetchChessApi(String fen, int depth, int variants,
                                              int maxThinkingTimeMs, long timeoutMs,
                                              boolean waitForFinal) {
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress(CHESS_API_HOST, 443), 5000);
            socket.setSoTimeout((int) Math.min(Math.max(timeoutMs, 1000L), 16000L));

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
                    + "User-Agent: ChessAssistant-Morphe/2.0\r\n\r\n";
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
            payload.put("depth", depth);
            payload.put("maxThinkingTime", maxThinkingTimeMs);
            payload.put("variants", variants);
            sendWebSocketFrame(output, 0x1, payload.toString().getBytes(StandardCharsets.UTF_8));

            boolean whiteToMove = fen.split("\\s+")[1].equals("w");
            ArrayList<CandidateMove> found = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean receivedFinal = false;
            String finalMove = "";

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
                    return EngineResult.error(readServiceError(json, "erro do serviço MultiPV"));
                }

                if (!"bestmove".equals(type) || hasEngineScore(json)) {
                    collectCandidatePayload(json, whiteToMove, found, depth);
                }

                if ("bestmove".equals(type)) {
                    receivedFinal = true;
                    finalMove = extractFinalMove(json, trimmed);
                    break;
                }
                if (!waitForFinal && found.size() >= variants) break;
            }

            if (waitForFinal && !receivedFinal) {
                return EngineResult.error("o motor não enviou a resposta final antes do limite de tempo");
            }
            if (found.isEmpty()) {
                return EngineResult.error("o WebSocket não retornou variantes com avaliação");
            }
            if (waitForFinal && finalMove.isEmpty()) {
                return EngineResult.error("a resposta final não continha uma jogada UCI válida");
            }

            Collections.sort(found, (left, right) ->
                    Double.compare(right.scoreForMover(), left.scoreForMover()));

            // The final bestmove event is authoritative. If it was already reported
            // provisionally, move that candidate to the first slot without replacing
            // its evaluation with a score-less final event.
            if (!finalMove.isEmpty()) {
                int finalIndex = -1;
                for (int i = 0; i < found.size(); i++) {
                    if (found.get(i).move.equalsIgnoreCase(finalMove)) {
                        finalIndex = i;
                        break;
                    }
                }
                if (finalIndex < 0) {
                    return EngineResult.error("o lance final não estava entre as variantes avaliadas");
                }
                if (finalIndex > 0) {
                    CandidateMove confirmed = found.remove(finalIndex);
                    found.add(0, confirmed);
                }
            }

            while (found.size() > variants) found.remove(found.size() - 1);

            CandidateMove best = found.get(0).withLabel("EXCELENTE");
            ArrayList<CandidateMove> selected = new ArrayList<>();
            selected.add(best);

            if (found.size() > 1) {
                CandidateMove second = found.get(1);
                double loss = best.scoreForMover() - second.scoreForMover();
                selected.add(second.withLabel(loss <= 0.75 ? "BOA" : "MEDIANA"));
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

    private static boolean hasEngineScore(JSONObject json) {
        Object eval = json.opt("eval");
        Object centipawns = json.opt("centipawns");
        Object mate = json.opt("mate");
        return (eval instanceof Number || eval instanceof String)
                || (centipawns instanceof Number || centipawns instanceof String)
                || mate instanceof Number;
    }

    private static String extractFinalMove(JSONObject json, String rawMessage) {
        String move = json.optString("move", "").trim();
        if (!isUciMove(move)) move = json.optString("lan", "").trim();
        if (!isUciMove(move)) move = json.optString("uci", "").trim();
        if (!isUciMove(move)) {
            String raw = json.optString("bestmove", "").trim();
            if (raw.isEmpty()) raw = rawMessage == null ? "" : rawMessage.trim();
            Matcher matcher = Pattern.compile(
                    "(?i)bestmove\\s+([a-h][1-8][a-h][1-8][qrbn]?)"
            ).matcher(raw);
            if (matcher.find()) move = matcher.group(1);
        }
        return isUciMove(move) ? move.toLowerCase(java.util.Locale.US) : "";
    }

    private static void collectCandidatePayload(JSONObject json, boolean whiteToMove,
                                                 ArrayList<CandidateMove> found) {
        collectCandidatePayload(json, whiteToMove, found, ENGINE_DEPTH);
    }

    private static void collectCandidatePayload(JSONObject json, boolean whiteToMove,
                                                 ArrayList<CandidateMove> found, int defaultDepth) {
        CandidateMove primary = parseCandidate(json, whiteToMove, defaultDepth);
        if (primary != null) addOrUpdateCandidate(found, primary);

        String[] arrayKeys = {"variants", "moves", "lines", "pvs"};
        for (String key : arrayKeys) {
            JSONArray array = json.optJSONArray(key);
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                CandidateMove candidate = parseCandidate(item, whiteToMove, defaultDepth);
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
        return parseCandidate(json, whiteToMove, ENGINE_DEPTH);
    }

    private static CandidateMove parseCandidate(JSONObject json, boolean whiteToMove, int defaultDepth) {
        String move = json.optString("move", "").trim();
        if (!isUciMove(move)) move = json.optString("lan", "").trim();
        if (!isUciMove(move)) move = json.optString("uci", "").trim();
        if (!isUciMove(move)) {
            String bestMoveField = json.optString("bestmove", "").trim();
            Matcher matcher = Pattern.compile(
                    "(?i)bestmove\\s+([a-h][1-8][a-h][1-8][qrbn]?)"
            ).matcher(bestMoveField);
            if (matcher.find()) move = matcher.group(1);
        }
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

        double evaluation = Double.NaN;
        Object evalValue = json.opt("eval");
        if (evalValue instanceof Number) {
            evaluation = ((Number) evalValue).doubleValue();
        } else if (evalValue instanceof String) {
            try {
                evaluation = Double.parseDouble((String) evalValue);
            } catch (NumberFormatException ignored) {
                evaluation = Double.NaN;
            }
        }

        if (Double.isNaN(evaluation)) {
            Object centipawnValue = json.opt("centipawns");
            try {
                if (centipawnValue instanceof Number) {
                    evaluation = ((Number) centipawnValue).doubleValue() / 100.0;
                } else if (centipawnValue instanceof String) {
                    evaluation = Double.parseDouble((String) centipawnValue) / 100.0;
                }
            } catch (NumberFormatException ignored) {
                evaluation = Double.NaN;
            }
        }

        Object mateValue = json.opt("mate");
        int mate = mateValue instanceof Number ? ((Number) mateValue).intValue() : 0;
        if (Double.isNaN(evaluation) && mate == 0) return null;
        if (Double.isNaN(evaluation)) evaluation = 0.0;

        int depth = json.optInt("depth", defaultDepth);
        if (depth <= 0) depth = defaultDepth;
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
            connection.setRequestProperty("User-Agent", "ChessAssistant-Morphe/2.0");

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return EngineResult.error("Stockfish Online HTTP " + code);

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            if (code < 200 || code >= 300) {
                return EngineResult.error("Stockfish Online HTTP " + code + ": " + body);
            }

            JSONObject json = new JSONObject(body.toString());
            if (!json.optBoolean("success", false)) {
                return EngineResult.error("Stockfish Online: "
                        + readServiceError(json, "o serviço recusou a posição"));
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
            if (move.isEmpty()) {
                return EngineResult.error(json.optString("data",
                        "Stockfish Online não retornou uma jogada válida"));
            }
            return new EngineResult(move, eval, mateIn, null);
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || message.trim().isEmpty()) message = "falha de conexão";
            if (message.length() > 90) message = message.substring(0, 90);
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

        CandidateMove withDepth(int value) {
            return new CandidateMove(move, evaluation, mate, value, whiteToMove, label);
        }
    }

    private static final class SpecialFinding {
        final CandidateMove candidate;
        final String title;
        final String detail;

        SpecialFinding(CandidateMove candidate, String title, String detail) {
            this.candidate = candidate;
            this.title = title;
            this.detail = detail;
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
        private String specialMove = "";

        BoardDrawing(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setBoard(View value) {
            if (board != value) board = value;
            invalidate();
        }

        void setSpecialMove(String move) {
            specialMove = move == null ? "" : move;
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
                drawCandidate(canvas, candidates[i], i, left, top, square, false);
            }
            if (isUciMove(specialMove)) {
                CandidateMove exceptional = new CandidateMove(
                        specialMove, evaluation, mate, SPECIAL_ENGINE_DEPTH, false, "ESPECIAL");
                drawCandidate(canvas, exceptional, 0, left, top, square, true);
            }
        }

        private void drawCandidate(Canvas canvas, CandidateMove candidate, int index,
                                   float left, float top, float square, boolean special) {
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

            int color = special ? 0xFFBD75FF
                    : MOVE_COLORS[Math.min(index, MOVE_COLORS.length - 1)];

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
