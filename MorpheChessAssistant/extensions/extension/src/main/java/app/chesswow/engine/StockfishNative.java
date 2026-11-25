package app.chesswow.engine;

/**
 * JNI entry point for the bundled Stockfish 19 bridge.
 *
 * The exported JNI names in libchesswow_stockfish.so intentionally match
 * this class and these method signatures. The library is loaded by absolute
 * path after it has been copied from the APK assets to the app-private files
 * directory; this avoids relying on Android's native-library extraction mode.
 */
public final class StockfishNative {
    private StockfishNative() {}

    public static native boolean nativeStart();

    /** Sends one UTF-8 UCI command without a trailing newline. */
    public static native void nativeSend(byte[] line);

    /** Blocks until the engine emits one line, or returns null after engine exit. */
    public static native byte[] nativeReceive();

    public static native boolean nativeIsRunning();
}
