# Chess Assistant — Morphe patch

Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package `com.chess`).

## Supported screens

- **Online human games:** `RealGameActivity`. The evaluation panel and two visual move suggestions are available; automatic move execution is disabled.
- **Play vs Bots:** `BotGameActivityV2`. The evaluation panel, move suggestions, and optional AUTO control are available.
- **Standalone analysis:** `StandaloneAnalysisActivityV2`.

## Move assistance

- Stockfish evaluation panel.
- Up to two analyzed MultiPV candidate moves: the engine's final top line and its second line.
- Green arrow for the final best line; yellow arrow for the second alternative.
- Blue origin-square highlight and destination highlight matching each arrow.
- No third / worst-move arrow or ordinary move badges; the separate special-alert panel has its own descriptive text.
- Board orientation handling and stale-overlay clearing.
- Visual assistance remains enabled on supported online game screens and in post-game analysis. AUTO never sends moves in human-vs-human games.

The normal engine request uses depth 12, up to two variants, and a 100 ms thinking-time setting. The completed Chess API HTTPS response is the primary source, and its top-level `move` field remains authoritative for the green recommendation. If that request fails, the patch immediately tries Stockfish Online on a separate host; only after that does it try the Chess API WebSocket as a final MultiPV fallback. This avoids spending the full WebSocket timeout on the same host before reaching the independent fallback provider.

## Rare special assistance

A separate deeper pass is requested for each valid board position, instead of being gated by shallow-score thresholds. It uses depth 18, up to four variants, and a 100 ms thinking-time setting. The HTTPS endpoint is tried first; WebSocket is used only if that request fails.

The special indicator can identify a forced tactical sequence, a uniquely saving move, a decisive continuation, a hidden winning line, or a material sacrifice that retains sufficient engine compensation. Capture sacrifices, en passant, and promotions are included in the preliminary sacrifice scan. The indicator and purple board arrow appear only when the deeper engine result meets the corresponding confirmation criteria; checkmate is not required. This remains an engine-based heuristic, not a guarantee that every brilliant move will be found or that every label is infallible. The deeper search runs asynchronously so it does not block the regular green/yellow suggestions.

## Automatic play against bots

The bot-game screen has a separate **AUTO ON/OFF** control positioned outside the board. It starts disabled and can be toggled during a game.

When enabled, it waits for the human player's turn and selects the engine's best line most of the time, choosing the second line approximately once in five moves. Before each automatic move it waits a random **2 to 5 seconds**. It never selects a third or deliberately bad line. Automatic input is sent only to the native board view in `BotGameActivityV2`.

The player's color is read from exposed activity/board metadata when available, with board orientation as a fallback. The control is hidden if no free area exists around the board where it can be displayed without covering squares.

## Engine connectivity and privacy

The normal move assistance tries `https://chess-api.com/v1` over HTTPS first and treats its top-level `move` field as authoritative for the green recommendation. On failure it immediately tries `https://stockfish.online/api/s/v2.php` on a separate host; that fallback provides one best move. The Chess API WebSocket is the final fallback and now performs TLS using the explicit hostname for SNI/certificate validation. Failed positions are retried automatically after 3, 6, 12, and then 30 seconds, while a newly detected position is analyzed immediately. The deeper special-analysis request also uses HTTPS before WebSocket. An internet connection is required; there is no on-device Stockfish engine. The current FEN is sent to the external analysis provider.

## Compatibility and build

Only Chess.com Android `4.10.28-googleplay` is declared as supported. The XAPK file type is declared because the app is distributed as an XAPK split bundle.

The GitHub Actions workflow **Build Morphe Chess Assistant** runs `gradle buildAndroid` and uploads the generated `.mpp` artifact. The output is in `patches/build/libs/`.

## Verification status

The patch source is compiled by GitHub Actions. Automatic touch delivery, control placement, the random delay, online overlay rendering, candidate timing, and bot-side color detection still require runtime confirmation on an Android device. Successful compilation alone does not prove runtime behavior.
