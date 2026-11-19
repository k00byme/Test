# Chess Assistant — Morphe patch

Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package `com.chess`).

## Supported game screens

- **Online games:** hooks the general `RealGameActivity` used by Chess.com's online game flow. This includes formats that route through that screen, such as live games and other online game types supported by this app version.
- **Play vs Bots:** hooks the dedicated `BotGameActivityV2` used for Chess.com's bot games, including games started from the app's online/offline bot flow.
- **Standalone analysis:** hooks `StandaloneAnalysisActivityV2` for analysis and review.

The game-mode coverage is based on the activities and board classes recovered from Chess.com Android 4.10.28. Game formats using another, separate Activity not listed above are not confirmed by this build.

## Visual features

- Stockfish score/evaluation panel.
- Up to three candidate moves from a MultiPV analysis: **best**, **good/medium**, and **least favorable**.
- Three colored arrows drawn over the native board, with text badges that identify each move and show its evaluation.
- The least favorable candidate is labeled **RUIM** only when its evaluation is at least 1.20 pawns worse than the best line, or it allows a forced mate against the side to move. Otherwise it is labeled **MENOS BOA** to avoid misclassifying a reasonable move.
- Blue highlight on the best move's origin square and green highlight on its destination.
- Board orientation handling for flipped and normal views.
- Old arrows/highlights are cleared when the position changes or the board disappears.
- The extension never moves a piece, clicks a square, or sends a move to Chess.com.

## Engine connectivity and privacy

The extension first sends the board FEN to `https://chess-api.com/v1` over HTTPS, requesting up to five MultiPV variations at depth 12. It uses the first, second and least favorable of the returned candidate lines for the three labels. If that service is unavailable, it falls back to `https://stockfish.online/api/s/v2.php`, which provides a single best move rather than MultiPV alternatives.

An internet connection is required for each new position. The patch does not include an on-device Stockfish engine. The FEN position is sent to the external analysis provider; do not use it with private positions if you do not want their FEN sent to those services.

## Compatibility

Only Chess.com Android `4.10.28-googleplay` is declared as a supported target. The XAPK file type is declared because the app package is distributed as an XAPK split bundle. Other app versions are untested.

## Build

The GitHub Actions workflow **Build Morphe Chess Assistant** builds the `.mpp` file and uploads it as an artifact. To build locally with the Morphe toolchain installed, run:

```bash
gradle buildAndroid
```

The output is in `patches/build/libs/`.

## Verification status

The target APK structure was inspected, and the patch bundle was compiled successfully by GitHub Actions. The generated `.mpp` archive passed an integrity check. Runtime rendering and live MultiPV responses still need confirmation on an Android device with this Chess.com version; successful compilation alone does not prove that the overlay renders correctly or that the external API returns multiple candidate lines for every position.
