# Chess Assistant — Morphe patch

Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package `com.chess`).

## Supported screens

- **Online human games:** `RealGameActivity`. The evaluation panel and visual move suggestions are available; automatic move execution is disabled.
- **Play vs Bots:** `BotGameActivityV2`. The evaluation panel, move suggestions, and optional AUTO control are available.
- **Standalone analysis:** `StandaloneAnalysisActivityV2`.

## Move assistance

- Stockfish evaluation panel and board-orientation handling.
- Up to three ordinary candidate arrows: green (best), yellow (second), and red (third).
- A separate purple arrow for a distinct alternative returned by the deeper search. Tactical labels remain heuristics; the purple line is only available when the deep provider returns a distinct candidate.
- Partial candidate snapshots are merged for the same FEN, so a delayed one-line response does not erase alternatives already received.
- Stale overlays and old feedback markers are cleared when the active position changes.
- AUTO never sends moves in human-vs-human games.

The regular analysis asks Chess API's progressive WebSocket for exactly three variations and renders up to three. The WebSocket is the primary source: when it completes a three-line result, the patch does not launch duplicate REST or Stockfish Online requests. Lichess Cloud Eval is queried in parallel only as a cache source; only one Lichess request may be in flight, with a local request interval. If the live stream is incomplete, Chess API HTTPS and Stockfish Online are started as bounded fallback requests. The exact-FEN LRU cache keeps up to 48 completed three-line results, so revisiting an analyzed position can render immediately. Candidate lines are deduplicated by their UCI first move and sorted using the side-to-move evaluation.

## Rare special assistance

A separate deeper pass uses depth 18 and up to four WebSocket variations, with a bounded timeout. It starts only after the regular analysis completes, so it does not compete with the first live arrows or classify a purple line using an incomplete one-move snapshot. A search for an old FEN is cancelled when the board changes, and its late callback is ignored. It checks forced tactical sequences, uniquely saving moves, decisive continuations, hidden winning lines, and material sacrifices with sufficient compensation. When no tactical condition matches, a distinct fourth line can still be surfaced as the purple alternative.

## Automatic play against bots

The bot-game screen has a separate **AUTO ON/OFF** control positioned outside the board. It starts disabled and can be toggled during a game.

When enabled, it waits for the human player's turn and selects the engine's best line most of the time, choosing the second line approximately once in five moves. Before each automatic move it waits a random **2 to 5 seconds**. It never selects a third or deliberately bad line. Automatic input is sent only to the native board view in `BotGameActivityV2`.

The player's color is read from exposed activity/board metadata when available, with board orientation as a fallback. The control is hidden if no free area exists around the board where it can be displayed without covering squares.

## Native Chess.com move feedback

Two live-game signals are preserved:

1. The board's native `MoveFeedback` constructor, which carries immediate states such as `CORRECT` and `INCORRECT`.
2. The return value of `PositionAnalysis.c`, which is `PositionAnalysis$b`. Its native `AnalysisMoveClassification` enum is read from field `d`, and the post-move position from field `a`.

The overlay uses Chess.com's original drawable resources for classifications such as `BRILLIANT`, `GREAT`, `BEST`, `EXCELLENT`, `GOOD`, `INACCURACY`, `MISTAKE`, `BLUNDER`, `MISS`, and `FORCED`. No feedback text panel is shown. The old Game Review, puzzle, lesson, and battle feedback hooks are not installed.

## Local Stockfish 19 engine

The patch bundles Stockfish 19 as an in-process native engine, built from the pinned ChessWOW Stockfish 19 source commit. The build includes JNI libraries for `arm64-v8a` and `armeabi-v7a`, plus the official Stockfish 19 NNUE network. Morphe inserts these files into the patched APK as assets; the extension extracts them to the app-private files directory and loads the native library by absolute path.

The local UCI engine uses **2 threads**, a **64 MB hash**, **3 MultiPV lines** for the regular analysis, and **4 lines at depth 18** for the separate purple pass. The normal pass targets depth 12. Searches run on a dedicated worker and are serialized; when a position changes, the previous search is stopped and its final UCI response is drained before the next FEN is sent. The NNUE network is extracted once and reused.

The existing Chess API, Lichess Cloud Eval and Stockfish Online paths remain as fallbacks when the local library, ABI or NNUE cannot be loaded, or when local analysis fails. In those cases the patch retains the previous bounded network-provider behavior. A first position can also use the remote fallback if local initialization has not completed yet.

When the local engine is ready, regular and deep FEN analysis stays on-device and does not need to be sent to those providers. Initial setup requires roughly 100 MB of additional private storage for the NNUE network and native libraries. The network's SHA-256 is checked before it is loaded, and the extracted native library is made read-only before dynamic loading for Android 14+ compatibility. Internet is still needed for fallback analysis and normal Chess.com functions.

Stockfish is licensed under GPL-3.0-or-later. The patch bundle includes its license and authors file. Corresponding source: [ChessWOW Stockfish 19 pinned source](https://github.com/chesswow/stockfish/tree/028e24b5d52bf652fba036084d9a7040d4020111), based on upstream Stockfish tag `sf_19`.

## Compatibility and build

Only Chess.com Android `4.10.28-googleplay` is declared as supported. The XAPK file type is declared because the app is distributed as an XAPK split bundle.

The GitHub Actions workflow **Build Morphe Chess Assistant** runs `gradle buildAndroid` and uploads the generated `.mpp` artifact. The output is in `patches/build/libs/`.

## Verification status

GitHub Actions compilation, artifact integrity, and DEX content checks can validate the package structure. They cannot prove that every external provider returns multiple candidates for every FEN, nor replace an in-device check of feedback timing, overlay rendering, candidate colors, and board-side behavior.
