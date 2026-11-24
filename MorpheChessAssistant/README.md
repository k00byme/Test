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

The regular analysis requests up to five variations from Chess API over WebSocket and renders at most three. It also queries the Lichess Cloud Eval cache for up to five principal variations when that exact position is already cached. The Lichess endpoint is cache-only: an uncached position is a normal miss, not a reason to fabricate alternatives. Chess API HTTPS and Stockfish Online remain single-line/backup sources; neither is treated as proof that three alternatives exist. All valid candidate lines are deduplicated by their UCI first move and sorted using the side-to-move evaluation.

## Rare special assistance

A separate deeper pass uses depth 18 and up to four WebSocket variations. It checks forced tactical sequences, uniquely saving moves, decisive continuations, hidden winning lines, and material sacrifices with sufficient compensation. When no tactical condition matches, a distinct fourth line can still be surfaced as the purple alternative. The deep search runs asynchronously and does not block the ordinary green/yellow/red updates.

## Automatic play against bots

The bot-game screen has a separate **AUTO ON/OFF** control positioned outside the board. It starts disabled and can be toggled during a game.

When enabled, it waits for the human player's turn and selects the engine's best line most of the time, choosing the second line approximately once in five moves. Before each automatic move it waits a random **2 to 5 seconds**. It never selects a third or deliberately bad line. Automatic input is sent only to the native board view in `BotGameActivityV2`.

The player's color is read from exposed activity/board metadata when available, with board orientation as a fallback. The control is hidden if no free area exists around the board where it can be displayed without covering squares.

## Native Chess.com move feedback

Two live-game signals are preserved:

1. The board's native `MoveFeedback` constructor, which carries immediate states such as `CORRECT` and `INCORRECT`.
2. The return value of `PositionAnalysis.c`, which is `PositionAnalysis$b`. Its native `AnalysisMoveClassification` enum is read from field `d`, and the post-move position from field `a`.

The overlay uses Chess.com's original drawable resources for classifications such as `BRILLIANT`, `GREAT`, `BEST`, `EXCELLENT`, `GOOD`, `INACCURACY`, `MISTAKE`, `BLUNDER`, `MISS`, and `FORCED`. No feedback text panel is shown. The old Game Review, puzzle, lesson, and battle feedback hooks are not installed.

## Engine connectivity and privacy

Analysis sends the current FEN to external providers. Chess API uses HTTPS/WebSocket, Lichess is queried only for cached MultiPV positions, and Stockfish Online is a single-move fallback. The Lichess cache request is rate-limited locally, and an HTTP 404 means that the position is not cached. No on-device Stockfish engine is bundled. Internet access is required.

## Compatibility and build

Only Chess.com Android `4.10.28-googleplay` is declared as supported. The XAPK file type is declared because the app is distributed as an XAPK split bundle.

The GitHub Actions workflow **Build Morphe Chess Assistant** runs `gradle buildAndroid` and uploads the generated `.mpp` artifact. The output is in `patches/build/libs/`.

## Verification status

GitHub Actions compilation, artifact integrity, and DEX content checks can validate the package structure. They cannot prove that every external provider returns multiple candidates for every FEN, nor replace an in-device check of feedback timing, overlay rendering, candidate colors, and board-side behavior.
