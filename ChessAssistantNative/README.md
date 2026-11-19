# Chess Assistant Native — Android integration module

A small Kotlin/Android module that adapts the features in the supplied userscript to a native Android host: Stockfish evaluation, a best-move label, an evaluation meter, board-square highlights, and a move arrow.

## Important status

This repository is a **native source module**, not a rebuilt Chess.com APK. The uploaded XAPK is a compiled application: its React Native bundle is Hermes bytecode and its board is implemented with Android-side chessboard classes. The original userscript expects a browser DOM (`chess-board`, `.piece`, `cg-board`), so it cannot be inserted into that APK as-is.

To make a real patched APK, the module must be wired into the app's board screen and receive its current FEN, orientation, and board rectangle. Those host-side source files are not present in the uploaded XAPK. This module therefore provides the integration layer, but does not pretend that the binary has already been patched or tested.

## Safety / intended use

Engine suggestions are enabled only for screens explicitly identified by the host as `ANALYSIS`, `POST_GAME_REVIEW`, `PUZZLE`, or `LESSON`. `LIVE_GAME` and `UNKNOWN` are rejected. The module only displays suggestions; it never moves a piece or sends a move to the game server.

The host must pass the correct screen mode. Do not label a competitive live game as an analysis screen.

## Features

- Native Android overlay panel with score, best move, minimize/restore, and visibility switches.
- UCI best-move parsing, including promotion moves.
- Board orientation support for white- and black-side views.
- Board-square highlights and a drawn arrow.
- FEN structural validation (eight ranks, eight files per rank, side to move, castling, en-passant square, and move counters).
- Bounded in-memory result cache and network timeouts.
- Analysis requests ignored in live-game/unknown modes and stale results ignored after the position changes.

## Engine service and data

`StockfishOnlineClient` calls `https://stockfish.online/api/s/v2.php` over HTTPS, matching the service used by the submitted script. The FEN is sent to that third-party service for analysis. This is **not an on-device Stockfish binary**. The external service can be unavailable, change its API, or apply its own limits. No win percentage is inferred from the score because score perspective can depend on the engine protocol/API.

## Build

Open the project root in Android Studio with Android SDK 35 installed, or build the library with Gradle:

```bash
gradle :chessassistant:assembleRelease
```

The build was not run in this environment because no Android SDK/Gradle installation is available here. The FEN/UCI core inside `ChessAssistantNative.kt` was compiled and smoke-tested independently; Android UI and network integration still require the Android toolchain.

## Host integration

The app's native analysis/training screen must provide a `FrameLayout` covering the board area. Add the overlay above the board, then call the controller whenever the position or orientation changes.

```kotlin
val overlay = ChessAssistantOverlayView(context)
boardContainer.addView(
    overlay,
    FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )
)

val assistant = ChessAssistantController(overlay)

// Call this from the host's analysis/training screen when the board changes.
assistant.onBoardSnapshot(
    BoardSnapshot(
        fen = currentFen,
        mode = ChessScreenMode.ANALYSIS,
        metrics = BoardMetrics(
            leftPx = boardLeft,
            topPx = boardTop,
            widthPx = boardWidth,
            heightPx = boardHeight,
            flipped = boardIsFlipped
        )
    )
)

// Call when leaving the screen.
assistant.close()
```

The rectangle must be measured in pixels relative to the same parent that contains `overlay`. The host must source `currentFen` from its actual game/analysis model, not reconstruct it from incomplete piece-placement data. A full FEN includes side to move, castling rights, en-passant target, halfmove clock, and fullmove number.

## Project layout

- `ChessAssistantNative.kt` — screen-mode gate, FEN/UCI core, Stockfish client, native overlay, and stale-result protection.

## Tests

Unit tests cover FEN validation and UCI move parsing. They can be run from Android Studio or with `gradle :chessassistant:test` once Gradle and the Android toolchain are installed.