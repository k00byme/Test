# Chess Assistant — Morphe patch

Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package `com.chess`).

## Supported screens

- **Play vs Bots:** `BotGameActivityV2`. The optional AUTO control is available only here.
- **Standalone analysis:** `StandaloneAnalysisActivityV2`.
- **Human live games:** not hooked by this build.

## Move assistance

- Stockfish evaluation panel.
- Up to two MultiPV candidate moves: the engine's top line and its second line.
- Green arrow for the top line; yellow arrow for the second line.
- Blue origin-square highlight and destination highlight matching each arrow.
- No third / worst-move suggestion, badges, or text labels.
- Board orientation handling and stale-overlay clearing.

The MultiPV request now asks for two lines instead of five, uses a lower depth and a shorter thinking budget, returns when both distinct first moves arrive, and polls the board more frequently. This is intended to reduce latency; actual timing still depends on network conditions and the external service.

## Automatic play against bots

The bot-game screen has a separate **AUTO ON/OFF** control positioned outside the board. It starts disabled and can be toggled during a game.

When enabled, it waits for the human player's turn and selects the engine's best line most of the time, choosing the second line approximately once in five moves. It never selects a third or deliberately bad line. Automatic input is sent only to the native board view in `BotGameActivityV2`; it is not injected into live games against human opponents.

The player's color is read from exposed activity/board metadata when available, with board orientation as a fallback. The control is hidden if no free area exists around the board where it can be displayed without covering squares.

## Engine connectivity and privacy

The extension uses `https://chess-api.com/v1` over WebSocket for two MultiPV lines. If that service fails completely, it falls back to `https://stockfish.online/api/s/v2.php`, which provides one best move. An internet connection is required; there is no on-device Stockfish engine. The current FEN is sent to the external analysis provider.

## Compatibility and build

Only Chess.com Android `4.10.28-googleplay` is declared as supported. The XAPK file type is declared because the app is distributed as an XAPK split bundle.

The GitHub Actions workflow **Build Morphe Chess Assistant** runs `gradle buildAndroid` and uploads the generated `.mpp` artifact. The output is in `patches/build/libs/`.

## Verification status

The patch source is compiled by GitHub Actions. Automatic touch delivery, control placement, candidate timing, and bot-side color detection still require runtime confirmation on an Android device. Successful compilation alone does not prove that the app accepts the injected touch events in every bot-game layout.
