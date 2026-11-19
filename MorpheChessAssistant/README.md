# Chess Assistant — Morphe patch

Morphe patch bundle for **Chess.com Android 4.10.28-googleplay** (package `com.chess`).

## Supported game screens

- **Online games:** hooks the general `RealGameActivity` used by Chess.com's online game flow. This includes game formats that route through that screen, such as live games and other online game types supported by this app version.
- **Play vs Bots:** hooks the dedicated `BotGameActivityV2` used for Chess.com's bot games, including games started from the app's online/offline bot flow.
- **Standalone analysis:** hooks `StandaloneAnalysisActivityV2` for analysis and review.

The game-mode coverage is based on the activities and board classes recovered from Chess.com Android 4.10.28. Game formats using another, separate Activity not listed above are not confirmed by this build.

## Visual features

- Stockfish score/evaluation panel.
- Best-move arrow drawn over the native board.
- Blue highlight on the move's origin square and green highlight on its destination.
- Board orientation handling for flipped and normal views.
- Old arrows/highlights are cleared when the position changes or the board disappears.
- The extension never moves a piece, clicks a square, or sends a move to Chess.com.

## Engine connectivity and privacy

The current patch requests analysis from `https://stockfish.online/api/s/v2.php` over HTTPS at depth 12. The Stockfish service requires an internet connection for each new position. The patch does not include an on-device Stockfish engine.

The board FEN is sent to the external Stockfish Online service for analysis. The extension does not intentionally send a username, account token, game identifier, or other account data. Do not use it with private positions if you do not want their FEN sent to that service.

## Compatibility

Only Chess.com Android `4.10.28-googleplay` is declared as a supported target. The XAPK file type is declared because the app package is distributed as an XAPK split bundle. Other app versions are untested.

## Build

The GitHub Actions workflow **Build Morphe Chess Assistant** builds the `.mpp` file and uploads it as an artifact. To build locally with the Morphe toolchain installed, run:

```bash
gradle buildAndroid
```

The output is in `patches/build/libs/`.

## Verification status

The target APK structure was inspected. The dedicated bot activity, the general online-game activity, the native `ChessBoardView`, and the standard-position FEN accessor were recovered from version 4.10.28. The Morphe bundle must compile for the exact commit before distribution. Runtime rendering still needs confirmation on an Android device with this Chess.com version; successful compilation alone does not prove that the overlay renders correctly in every game format.
