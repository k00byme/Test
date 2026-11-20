# Chess Assistant — Morphe patch

Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package `com.chess`).

## Supported screens

- **Online human games:** `RealGameActivity`. The evaluation panel and two visual move suggestions are available; automatic move execution is disabled.
- **Play vs Bots:** `BotGameActivityV2`. The evaluation panel, move suggestions, and optional AUTO control are available.
- **Standalone analysis:** `StandaloneAnalysisActivityV2`.

## Move assistance

- Stockfish evaluation panel.
- Up to two MultiPV candidate moves: the engine's top line and its second line.
- Green arrow for the top line; yellow arrow for the second line.
- Blue origin-square highlight and destination highlight matching each arrow.
- No third / worst-move suggestion, badges, or text labels.
- Board orientation handling and stale-overlay clearing.
- The visual assistance remains enabled for online games as in the earlier build. AUTO never sends moves in human-vs-human games.

The normal MultiPV request asks for two lines, uses a short thinking budget, returns when both distinct first moves arrive, and polls the board frequently. Actual timing still depends on network conditions and the external service.

## Rare special assistance

Normal assistance remains visible continuously with the two strongest alternatives. Separately, when the quick analysis detects a possible unique line, forced mate, decisive continuation, or a possible material sacrifice, the patch requests a deeper search (depth 18, up to four lines, with a longer time budget). The special indicator and purple board arrow appear only when that deeper result confirms one of these patterns: a forced tactical/mating line, a uniquely saving or winning move, a winning continuation missed by the quick pass, or a non-capturing piece sacrifice that is attacked but retains sufficient engine compensation. Ordinary positions do not receive the special indicator.

Sacrifice detection uses the FEN board and attacked-square geometry as a preliminary filter; the engine evaluation is the confirmation step. This is still an engine heuristic, not a guarantee that every extraordinary move will be detected. The deep pass is asynchronous, so normal two-line suggestions appear first.

## Automatic play against bots

The bot-game screen has a separate **AUTO ON/OFF** control positioned outside the board. It starts disabled and can be toggled during a game.

When enabled, it waits for the human player's turn and selects the engine's best line most of the time, choosing the second line approximately once in five moves. Before each automatic move it waits a random **2 to 5 seconds**. It never selects a third or deliberately bad line. Automatic input is sent only to the native board view in `BotGameActivityV2`.

The player's color is read from exposed activity/board metadata when available, with board orientation as a fallback. The control is hidden if no free area exists around the board where it can be displayed without covering squares.

## Engine connectivity and privacy

The extension uses `https://chess-api.com/v1` over WebSocket for two MultiPV lines. If that service fails completely, it falls back to `https://stockfish.online/api/s/v2.php`, which provides one best move. An internet connection is required; there is no on-device Stockfish engine. The current FEN is sent to the external analysis provider.

## Compatibility and build

Only Chess.com Android `4.10.28-googleplay` is declared as supported. The XAPK file type is declared because the app is distributed as an XAPK split bundle.

The GitHub Actions workflow **Build Morphe Chess Assistant** runs `gradle buildAndroid` and uploads the generated `.mpp` artifact. The output is in `patches/build/libs/`.

## Verification status

The patch source is compiled by GitHub Actions. Automatic touch delivery, control placement, the random delay, online overlay rendering, candidate timing, and bot-side color detection still require runtime confirmation on an Android device. Successful compilation alone does not prove runtime behavior.
