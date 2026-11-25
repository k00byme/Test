package app.k00byme.chessassistant.extension;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import app.chesswow.engine.StockfishNative;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * In-process Stockfish 19 UCI client.
 *
 * The native bridge owns a single Stockfish instance and exposes its UCI input
 * and output through JNI. One reader thread drains native output continuously;
 * searches are serialized so stale bestmove/info messages cannot cross FENs.
 */
final class LocalStockfishEngine {
    private static final String TAG = "ChessAssistant.Stockfish";
    private static final String ENGINE_DIR = "stockfish19";
    private static final String NNUE_NAME = "nn-1a298aa575a0.nnue";
    private static final String NNUE_ASSET = "stockfish/" + NNUE_NAME;
    private static final long MIN_NNUE_BYTES = 80L * 1024L * 1024L;
    private static final long MIN_LIBRARY_BYTES = 1024L * 1024L;
    private static final long UCI_START_TIMEOUT_MS = 20000L;
    private static final long READY_TIMEOUT_MS = 60000L;
    private static final long STOP_DRAIN_TIMEOUT_MS = 1800L;
    private static final long PROGRESS_INTERVAL_MS = 220L;
    private static final Pattern UCI_MOVE =
            Pattern.compile("(?i)^[a-h][1-8][a-h][1-8][qrbn]?$");

    private static final Object INIT_LOCK = new Object();
    private static final Object ENGINE_LOCK = new Object();
    private static final BlockingQueue<String> OUTPUT = new LinkedBlockingQueue<>();
    private static final ExecutorService INIT_EXECUTOR = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "ChessAssistant-Stockfish-init");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static volatile boolean initializing;
    private static volatile boolean ready;
    private static volatile String lastError = "";
    private static volatile Thread readerThread;
    private static volatile String loadedLibraryPath = "";

    interface ProgressListener {
        void onProgress(Analysis analysis);
    }

    static final class Line {
        final String move;
        final double evaluationWhite;
        final int mateWhite;
        final int depth;
        final int rank;

        Line(String move, double evaluationWhite, int mateWhite, int depth, int rank) {
            this.move = move;
            this.evaluationWhite = evaluationWhite;
            this.mateWhite = mateWhite;
            this.depth = depth;
            this.rank = rank;
        }
    }

    static final class Analysis {
        final String bestMove;
        final List<Line> lines;
        final int depth;
        final boolean complete;

        Analysis(String bestMove, List<Line> lines, int depth, boolean complete) {
            this.bestMove = bestMove == null ? "" : bestMove;
            this.lines = lines == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(lines));
            this.depth = depth;
            this.complete = complete;
        }
    }

    private LocalStockfishEngine() {}

    static void initializeAsync(Context context) {
        if (context == null || ready) return;
        synchronized (INIT_LOCK) {
            if (ready || initializing) return;
            initializing = true;
        }

        final Context appContext = context.getApplicationContext();
        INIT_EXECUTOR.execute(() -> {
            try {
                initializeInternal(appContext);
                ready = true;
                lastError = "";
                Log.i(TAG, "Stockfish 19 local engine ready (" + loadedLibraryPath + ")");
            } catch (Throwable error) {
                ready = false;
                lastError = safeMessage(error);
                Log.w(TAG, "Local Stockfish unavailable; remote providers remain enabled: "
                        + lastError, error);
                try {
                    if (StockfishNative.nativeIsRunning()) send("quit");
                } catch (Throwable ignored) {
                    // A failed native start must not affect the host application.
                }
            } finally {
                initializing = false;
            }
        });
    }

    static boolean isReady() {
        return ready;
    }

    static boolean isInitializing() {
        return initializing;
    }

    static String getLastError() {
        return lastError;
    }

    private static void initializeInternal(Context context) throws Exception {
        File root = new File(context.getFilesDir(), ENGINE_DIR);
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("não foi possível criar a pasta privada do motor");
        }

        File nnue = copyAssetIfNeeded(
                context.getAssets(), NNUE_ASSET, new File(root, NNUE_NAME), MIN_NNUE_BYTES);
        File library = findAndCopyLibrary(context, root);
        if (library == null) {
            throw new IOException("não há biblioteca Stockfish para a ABI deste aparelho");
        }

        library.setReadable(true, false);
        library.setExecutable(true, false);
        // Android 14+ restricts dynamic code loading from writable files. The
        // library is copied atomically into app-private storage, then made
        // read-only before System.load() so newer Android releases accept it.
        if (!library.setReadOnly() || library.canWrite()) {
            throw new IOException("não foi possível proteger a biblioteca nativa contra escrita");
        }
        nnue.setReadable(true, false);
        nnue.setReadOnly();
        loadedLibraryPath = library.getAbsolutePath();

        System.load(loadedLibraryPath);
        if (!StockfishNative.nativeStart()) {
            throw new IOException("não foi possível iniciar a thread nativa do Stockfish");
        }
        startReaderThread();

        synchronized (ENGINE_LOCK) {
            drainOutput();
            send("uci");
            if (!awaitExactLine("uciok", UCI_START_TIMEOUT_MS)) {
                throw new TimeoutException("Stockfish não respondeu ao comando UCI");
            }

            send("setoption name Threads value 2");
            send("setoption name Hash value 64");
            send("setoption name EvalFile value " + nnue.getAbsolutePath());
            send("setoption name MultiPV value 3");
            send("isready");
            if (!awaitExactLine("readyok", READY_TIMEOUT_MS)) {
                throw new TimeoutException("Stockfish não terminou de carregar a rede NNUE");
            }
        }
    }

    private static File findAndCopyLibrary(Context context, File root) throws IOException {
        // Build.SUPPORTED_ABIS describes the device, while the process can still be
        // 32-bit on a 64-bit device. Loading an ARM64 library into a 32-bit process
        // would fail, so select the ABI of this actual app process.
        String processAbi = Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
        String asset = "stockfish/native/" + processAbi + "/libchesswow_stockfish.so";
        File destination = new File(new File(root, processAbi), "libchesswow_stockfish.so");
        return copyAssetIfNeeded(
                context.getAssets(), asset, destination, MIN_LIBRARY_BYTES);
    }

    private static File copyAssetIfNeeded(AssetManager assets, String assetPath,
                                          File destination, long minimumBytes) throws IOException {
        if (destination.isFile() && destination.length() >= minimumBytes) {
            return destination;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("não foi possível criar " + parent.getAbsolutePath());
        }

        File temporary = new File(destination.getAbsolutePath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("não foi possível limpar arquivo temporário");
        }

        long written = 0L;
        try (InputStream input = new BufferedInputStream(assets.open(assetPath, AssetManager.ACCESS_STREAMING));
             FileOutputStream output = new FileOutputStream(temporary);
             BufferedOutputStream bufferedOutput = new BufferedOutputStream(output)) {
            // Android 14+ dynamic-code loading requires read-only files. Set this
            // immediately after opening the descriptor, before writing any bytes,
            // following the Android platform guidance for dynamically-loaded code.
            if (!temporary.setReadOnly()) {
                throw new IOException("não foi possível criar arquivo Stockfish somente leitura");
            }
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bufferedOutput.write(buffer, 0, count);
                written += count;
            }
            bufferedOutput.flush();
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }

        if (written < minimumBytes) {
            temporary.delete();
            throw new IOException("arquivo Stockfish incompleto: " + assetPath);
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("não foi possível substituir " + destination.getName());
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("não foi possível instalar " + destination.getName());
        }
        destination.setReadable(true, false);
        destination.setExecutable(true, false);
        return destination;
    }

    private static void startReaderThread() {
        Thread current = readerThread;
        if (current != null && current.isAlive()) return;
        OUTPUT.clear();
        Thread reader = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] bytes = StockfishNative.nativeReceive();
                    if (bytes == null) {
                        OUTPUT.offer("__STOCKFISH_EOF__");
                        return;
                    }
                    String line = new String(bytes, StandardCharsets.UTF_8).trim();
                    if (!line.isEmpty()) OUTPUT.put(line);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                OUTPUT.offer("__STOCKFISH_EOF__");
                lastError = safeMessage(error);
                ready = false;
            }
        }, "ChessAssistant-Stockfish-output");
        reader.setDaemon(true);
        readerThread = reader;
        reader.start();
    }

    private static void send(String command) {
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        StockfishNative.nativeSend(bytes);
    }

    private static boolean awaitExactLine(String expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("inicialização do Stockfish cancelada");
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            String line = OUTPUT.poll(Math.max(1L, Math.min(remaining, 250L)), TimeUnit.MILLISECONDS);
            if (line == null) continue;
            if ("__STOCKFISH_EOF__".equals(line)) {
                throw new IOException("a thread nativa do Stockfish foi encerrada");
            }
            if (expected.equals(line)) return true;
        }
        return false;
    }

    private static void drainOutput() {
        while (OUTPUT.poll() != null) {
            // Discard stale UCI output before starting a new request.
        }
    }

    static Analysis analyze(String fen, int depth, int multiPv,
                            long timeoutMs, ProgressListener listener) throws Exception {
        if (fen == null || fen.trim().isEmpty()) throw new IllegalArgumentException("FEN vazia");
        if (depth < 1 || depth > 30) throw new IllegalArgumentException("profundidade inválida");
        if (multiPv < 1 || multiPv > 5) throw new IllegalArgumentException("MultiPV inválido");
        if (!ready || !StockfishNative.nativeIsRunning()) {
            throw new IOException(lastError.isEmpty()
                    ? "Stockfish local ainda não está pronto" : lastError);
        }

        synchronized (ENGINE_LOCK) {
            if (!ready || !StockfishNative.nativeIsRunning()) {
                throw new IOException("Stockfish local não está em execução");
            }
            drainOutput();
            boolean whiteToMove = fen.trim().split("\\s+").length > 1
                    && "w".equals(fen.trim().split("\\s+")[1]);
            Map<Integer, Line> latest = new TreeMap<>();
            int maxDepth = 0;
            long lastProgress = 0L;
            long deadline = SystemClock.elapsedRealtime() + Math.max(1000L, timeoutMs);
            boolean complete = false;
            String bestMove = "";

            send("ucinewgame");
            send("setoption name MultiPV value " + multiPv);
            send("position fen " + fen.trim());
            send("go depth " + depth);

            try {
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("análise Stockfish cancelada");
                    }
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    String line = OUTPUT.poll(Math.max(1L, Math.min(remaining, 180L)),
                            TimeUnit.MILLISECONDS);
                    if (line == null) continue;
                    if ("__STOCKFISH_EOF__".equals(line)) {
                        throw new IOException("o motor Stockfish encerrou durante a análise");
                    }

                    if (line.startsWith("info ")) {
                        Line parsed = parseInfo(line, whiteToMove, multiPv);
                        if (parsed != null) {
                            latest.put(parsed.rank, parsed);
                            maxDepth = Math.max(maxDepth, parsed.depth);
                            long now = SystemClock.elapsedRealtime();
                            if (listener != null && now - lastProgress >= PROGRESS_INTERVAL_MS) {
                                lastProgress = now;
                                listener.onProgress(snapshot(latest, maxDepth, false, bestMove));
                            }
                        }
                    } else if (line.startsWith("bestmove ")) {
                        String[] tokens = line.split("\\s+");
                        if (tokens.length > 1 && isUciMove(tokens[1])) bestMove = tokens[1].toLowerCase();
                        complete = true;
                        break;
                    }
                }

                if (!complete) {
                    send("stop");
                    long stopDeadline = SystemClock.elapsedRealtime() + STOP_DRAIN_TIMEOUT_MS;
                    boolean stopAcknowledged = false;
                    while (SystemClock.elapsedRealtime() < stopDeadline) {
                        String line = OUTPUT.poll(120L, TimeUnit.MILLISECONDS);
                        if (line == null) continue;
                        if (line.startsWith("info ")) {
                            Line parsed = parseInfo(line, whiteToMove, multiPv);
                            if (parsed != null) {
                                latest.put(parsed.rank, parsed);
                                maxDepth = Math.max(maxDepth, parsed.depth);
                            }
                        } else if (line.startsWith("bestmove ")) {
                            String[] tokens = line.split("\\s+");
                            if (tokens.length > 1 && isUciMove(tokens[1])) {
                                bestMove = tokens[1].toLowerCase();
                            }
                            stopAcknowledged = true;
                            break;
                        } else if ("__STOCKFISH_EOF__".equals(line)) {
                            break;
                        }
                    }
                    if (!stopAcknowledged) {
                        ready = false;
                        lastError = "Stockfish não confirmou o fim da busca após STOP";
                        throw new TimeoutException(lastError);
                    }
                }
            } catch (InterruptedException interrupted) {
                // Clear the flag long enough to drain the cancelled UCI search. If its
                // bestmove is left queued, it could be mistaken for the next FEN.
                Thread.interrupted();
                boolean stopped = false;
                try {
                    send("stop");
                    stopped = drainUntilBestMove();
                } catch (Throwable ignored) {
                    // Keep the original cancellation reason.
                } finally {
                    if (!stopped) {
                        ready = false;
                        lastError = "Stockfish não confirmou o cancelamento da busca";
                    }
                    Thread.currentThread().interrupt();
                }
                throw interrupted;
            }

            Analysis result = snapshot(latest, maxDepth, complete, bestMove);
            if (result.lines.isEmpty()) {
                throw new TimeoutException("Stockfish não retornou uma variante válida");
            }
            if (listener != null) listener.onProgress(result);
            return result;
        }
    }

    private static boolean drainUntilBestMove() throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + STOP_DRAIN_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            String line = OUTPUT.poll(120L, TimeUnit.MILLISECONDS);
            if (line == null) continue;
            if (line.startsWith("bestmove ")) return true;
            if ("__STOCKFISH_EOF__".equals(line)) return false;
        }
        return false;
    }

    private static Line parseInfo(String line, boolean whiteToMove, int maxLines) {
        String[] tokens = line.trim().split("\\s+");
        int depth = 0;
        int rank = 1;
        String scoreType = "";
        int scoreValue = Integer.MIN_VALUE;
        String move = "";

        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            if ("depth".equals(token) && index + 1 < tokens.length) {
                depth = parseInt(tokens[++index], 0);
            } else if ("multipv".equals(token) && index + 1 < tokens.length) {
                rank = parseInt(tokens[++index], 1);
            } else if ("score".equals(token) && index + 2 < tokens.length) {
                scoreType = tokens[++index];
                scoreValue = parseInt(tokens[++index], Integer.MIN_VALUE);
            } else if ("pv".equals(token) && index + 1 < tokens.length) {
                move = tokens[index + 1];
                break;
            }
        }

        if (rank < 1 || rank > maxLines || depth < 1 || !isUciMove(move)
                || scoreValue == Integer.MIN_VALUE
                || (!"cp".equals(scoreType) && !"mate".equals(scoreType))) {
            return null;
        }

        double evaluationWhite = 0.0;
        int mateWhite = 0;
        if ("cp".equals(scoreType)) {
            evaluationWhite = scoreValue / 100.0;
            if (!whiteToMove) evaluationWhite = -evaluationWhite;
        } else {
            mateWhite = scoreValue;
            if (!whiteToMove) mateWhite = -mateWhite;
        }

        return new Line(move.toLowerCase(), evaluationWhite, mateWhite, depth, rank);
    }

    private static Analysis snapshot(Map<Integer, Line> latest, int depth,
                                     boolean complete, String bestMove) {
        ArrayList<Line> lines = new ArrayList<>();
        for (Map.Entry<Integer, Line> entry : latest.entrySet()) {
            if (entry.getValue() != null) lines.add(entry.getValue());
        }
        String selectedBest = bestMove;
        if ((selectedBest == null || selectedBest.isEmpty()) && !lines.isEmpty()) {
            selectedBest = lines.get(0).move;
        }
        return new Analysis(selectedBest, lines, depth, complete);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean isUciMove(String move) {
        return move != null && UCI_MOVE.matcher(move).matches();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "erro desconhecido" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "erro desconhecido" : error.getClass().getSimpleName();
        }
        return message.length() > 140 ? message.substring(0, 140) : message;
    }
}
