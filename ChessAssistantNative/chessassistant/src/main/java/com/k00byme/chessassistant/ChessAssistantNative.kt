package com.k00byme.chessassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONObject

// ===== ChessAssistantModels.kt =====
/** Modes in which engine suggestions are permitted. Competitive live games are deliberately excluded. */
enum class ChessScreenMode {
    ANALYSIS,
    POST_GAME_REVIEW,
    PUZZLE,
    LESSON,
    LIVE_GAME,
    UNKNOWN;

    internal fun permitsAnalysis(): Boolean = when (this) {
        ANALYSIS, POST_GAME_REVIEW, PUZZLE, LESSON -> true
        LIVE_GAME, UNKNOWN -> false
    }
}

data class BoardMetrics(
    /** Board rectangle in pixels, relative to the overlay's parent. */
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val flipped: Boolean
) {
    init {
        require(leftPx.isFinite() && topPx.isFinite()) { "Board origin must be finite" }
        require(widthPx.isFinite() && heightPx.isFinite() && widthPx > 0f && heightPx > 0f) {
            "Board dimensions must be positive and finite"
        }
    }
}

data class BoardSnapshot(
    val fen: String,
    val mode: ChessScreenMode,
    val metrics: BoardMetrics
)

data class EngineResult(
    /** Signed score returned by the engine service, or an M<n> mate notation. */
    val evaluation: String,
    /** UCI move, e.g. e2e4 or e7e8q. Null when the service returned no move. */
    val bestMoveUci: String?,
    val continuation: String?,
    val depth: Int
)

// ===== FenPosition.kt =====
/** Strict structural FEN validation. This does not prove that a position is legally reachable. */
data class FenPosition(
    val placement: String,
    val activeColor: Char,
    val castling: String,
    val enPassant: String,
    val halfmoveClock: Int,
    val fullmoveNumber: Int
) {
    companion object {
        private val pieceChars = setOf('p', 'n', 'b', 'r', 'q', 'k', 'P', 'N', 'B', 'R', 'Q', 'K')
        private val castlingChars = setOf('K', 'Q', 'k', 'q')

        fun parse(fen: String): FenPosition {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size == 6) { "FEN must contain exactly six fields" }

            val placement = fields[0]
            val ranks = placement.split('/')
            require(ranks.size == 8) { "FEN piece placement must contain eight ranks" }

            var whiteKings = 0
            var blackKings = 0
            for ((rankIndex, rank) in ranks.withIndex()) {
                var files = 0
                for (ch in rank) {
                    when {
                        ch in '1'..'8' -> files += ch.digitToInt()
                        ch in pieceChars -> {
                            files += 1
                            if (ch == 'K') whiteKings++
                            if (ch == 'k') blackKings++
                        }
                        else -> throw IllegalArgumentException("Invalid piece-placement character '$ch' on rank ${8 - rankIndex}")
                    }
                }
                require(files == 8) { "FEN rank ${8 - rankIndex} must describe exactly eight files" }
            }
            require(whiteKings == 1 && blackKings == 1) { "FEN must contain exactly one king of each color" }

            val activeColor = fields[1].singleOrNull()
                ?: throw IllegalArgumentException("Active-color field must be 'w' or 'b'")
            require(activeColor == 'w' || activeColor == 'b') { "Active-color field must be 'w' or 'b'" }

            val castling = fields[2]
            require(castling == "-" || (castling.all { it in castlingChars } && castling.toSet().size == castling.length)) {
                "Invalid castling-rights field"
            }

            val enPassant = fields[3]
            require(enPassant == "-" || Regex("^[a-h][36]$").matches(enPassant)) {
                "Invalid en-passant target square"
            }

            val halfmove = fields[4].toIntOrNull()
                ?: throw IllegalArgumentException("Halfmove clock must be a non-negative integer")
            require(halfmove >= 0) { "Halfmove clock must be non-negative" }

            val fullmove = fields[5].toIntOrNull()
                ?: throw IllegalArgumentException("Fullmove number must be a positive integer")
            require(fullmove >= 1) { "Fullmove number must be at least one" }

            return FenPosition(placement, activeColor, castling, enPassant, halfmove, fullmove)
        }
    }
}

// ===== UciMove.kt =====
data class UciSquare(val file: Int, val rank: Int) {
    init {
        require(file in 0..7 && rank in 1..8)
    }

    companion object {
        fun parse(value: String): UciSquare {
            require(value.length == 2 && value[0] in 'a'..'h' && value[1] in '1'..'8') {
                "Invalid UCI square: $value"
            }
            return UciSquare(value[0] - 'a', value[1].digitToInt())
        }
    }
}

data class UciMove(
    val from: UciSquare,
    val to: UciSquare,
    val promotion: Char? = null
) {
    companion object {
        fun parse(value: String): UciMove {
            require(value.length == 4 || value.length == 5) { "UCI move must have four or five characters" }
            val promotion = if (value.length == 5) value[4] else null
            require(promotion == null || promotion in "qrbn") { "Invalid UCI promotion piece" }
            return UciMove(UciSquare.parse(value.substring(0, 2)), UciSquare.parse(value.substring(2, 4)), promotion)
        }
    }
}

// ===== StockfishOnlineClient.kt =====
/**
 * Small client for the Stockfish Online endpoint used by the supplied userscript.
 * The FEN is sent to the remote service over HTTPS; this is not an on-device engine.
 */
class StockfishOnlineClient(
    private val endpoint: String = "https://stockfish.online/api/s/v2.php",
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val cacheCapacity: Int = 96
) : Closeable {
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, EngineResult>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EngineResult>?): Boolean {
            return size > cacheCapacity
        }
    }

    fun analyze(fen: String, depth: Int = 12, callback: (Result<EngineResult>) -> Unit) {
        val validatedFen = try {
            FenPosition.parse(fen)
            fen.trim().replace(Regex("\\s+"), " ")
        } catch (error: Throwable) {
            callback(Result.failure(error))
            return
        }
        val safeDepth = depth.coerceIn(1, 20)
        val cacheKey = "$validatedFen|$safeDepth"

        synchronized(cacheLock) {
            cache[cacheKey]?.let {
                callback(Result.success(it))
                return
            }
        }

        executor.execute {
            val result = runCatching { request(cacheKey.substringBeforeLast('|'), safeDepth) }
            result.onSuccess { value ->
                synchronized(cacheLock) { cache[cacheKey] = value }
            }
            callback(result)
        }
    }

    private fun request(fen: String, depth: Int): EngineResult {
        val encodedFen = URLEncoder.encode(fen, StandardCharsets.UTF_8.name())
        val url = URL("$endpoint?fen=$encodedFen&depth=$depth")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChessAssistantNative/1.0")
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            check(status in 200..299) { "Stockfish service returned HTTP $status" }
            val json = JSONObject(body)
            check(json.optBoolean("success", false)) {
                json.optString("error", "Stockfish service did not return a successful analysis")
            }

            val score = if (json.has("mate") && !json.isNull("mate")) {
                "M${json.get("mate")}"
            } else {
                val raw = json.opt("evaluation")
                require(raw is Number || raw is String) { "Response has no numeric evaluation" }
                raw.toString()
            }

            val bestMoveText = json.optString("bestmove", "").trim()
            val tokens = bestMoveText.split(Regex("\\s+")).filter(String::isNotBlank)
            val bestMove = when {
                tokens.size >= 2 && tokens[0].equals("bestmove", ignoreCase = true) -> tokens[1]
                tokens.size == 1 && Regex("^[a-h][1-8][a-h][1-8][qrbn]?$", RegexOption.IGNORE_CASE).matches(tokens[0]) -> tokens[0]
                else -> null
            }
            if (bestMove != null) UciMove.parse(bestMove)

            return EngineResult(
                evaluation = score,
                bestMoveUci = bestMove,
                continuation = json.optString("continuation", "").ifBlank { null },
                depth = depth
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        executor.shutdownNow()
        synchronized(cacheLock) { cache.clear() }
    }
}

// ===== ChessAssistantOverlayView.kt =====
/** Native view overlay. The host must place it in the same coordinate space as the board. */
class ChessAssistantOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val drawingLayer = DrawingLayer(context)
    private val scoreText = text("Score: —", 17f, Color.WHITE, true)
    private val moveText = text("Best move: —", 14f, Color.rgb(74, 222, 128), true)
    private val statusText = text("Available in analysis and training", 11f, Color.LTGRAY, false)
    private val arrowsSwitch = Switch(context).apply {
        text = "Move arrow"
        setTextColor(Color.WHITE)
        isChecked = true
    }
    private val evalSwitch = Switch(context).apply {
        text = "Eval meter"
        setTextColor(Color.WHITE)
        isChecked = true
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.argb(238, 15, 23, 42))
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), Color.argb(70, 255, 255, 255))
        }
        elevation = dp(10).toFloat()
    }
    private val pill = Button(context).apply {
        text = "♟  Chess analysis"
        visibility = GONE
        isAllCaps = false
        setOnClickListener {
            panel.visibility = VISIBLE
            visibility = GONE
        }
    }

    private var snapshot: BoardSnapshot? = null
    private var engineResult: EngineResult? = null
    private var showArrows = true
    private var showEval = true

    init {
        clipChildren = false
        clipToPadding = false
        addView(drawingLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.addView(text("♟  Chess Assistant", 14f, Color.rgb(56, 189, 248), true))
        panel.addView(scoreText, verticalMarginParams(top = 8))
        panel.addView(moveText, verticalMarginParams(top = 5))
        panel.addView(statusText, verticalMarginParams(top = 5))
        panel.addView(arrowsSwitch, verticalMarginParams(top = 7))
        panel.addView(evalSwitch, verticalMarginParams(top = 2))
        val minimize = Button(context).apply {
            text = "Minimize"
            isAllCaps = false
            setOnClickListener {
                panel.visibility = GONE
                pill.visibility = VISIBLE
            }
        }
        panel.addView(minimize, verticalMarginParams(top = 5))
        addView(panel, LayoutParams(dp(280), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })

        arrowsSwitch.setOnCheckedChangeListener { _, checked ->
            showArrows = checked
            drawingLayer.invalidate()
        }
        evalSwitch.setOnCheckedChangeListener { _, checked ->
            showEval = checked
            drawingLayer.invalidate()
        }
    }

    fun showLoading(board: BoardSnapshot) {
        snapshot = board
        engineResult = null
        panel.visibility = VISIBLE
        pill.visibility = GONE
        scoreText.text = "Score: calculating…"
        moveText.text = "Best move: —"
        statusText.text = "Engine analysis in progress"
        drawingLayer.invalidate()
    }

    fun showResult(board: BoardSnapshot, result: EngineResult) {
        snapshot = board
        engineResult = result
        scoreText.text = "Score: ${formatScore(result.evaluation)}"
        moveText.text = "Best move: ${result.bestMoveUci ?: "not available"}"
        statusText.text = "Stockfish • depth ${result.depth}"
        drawingLayer.invalidate()
    }

    fun showUnavailable(message: String) {
        snapshot = null
        engineResult = null
        scoreText.text = "Score: —"
        moveText.text = "Best move: —"
        statusText.text = message
        drawingLayer.invalidate()
    }

    fun clear() = showUnavailable("Available only in analysis/training")

    private inner class DrawingLayer(ctx: Context) : View(ctx) {
        private val fromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 56, 189, 248) }
        private val toPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 34, 197, 94) }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 197, 94)
            style = Paint.Style.STROKE
            strokeWidth = dp(5).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 197, 94)
            style = Paint.Style.FILL
        }
        private val barBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 15, 23, 42) }
        private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 189, 248) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val board = snapshot ?: return
            val result = engineResult
            val rect = RectF(
                board.metrics.leftPx,
                board.metrics.topPx,
                board.metrics.leftPx + board.metrics.widthPx,
                board.metrics.topPx + board.metrics.heightPx
            )

            if (showEval) drawMeter(canvas, rect, result?.evaluation)
            if (showArrows && result?.bestMoveUci != null) {
                val move = runCatching { UciMove.parse(result.bestMoveUci) }.getOrNull() ?: return
                val squareW = rect.width() / 8f
                val squareH = rect.height() / 8f
                val from = centerFor(move.from, board.metrics.flipped, rect, squareW, squareH)
                val to = centerFor(move.to, board.metrics.flipped, rect, squareW, squareH)
                canvas.drawRect(squareRect(from.first, from.second, squareW, squareH), fromPaint)
                canvas.drawRect(squareRect(to.first, to.second, squareW, squareH), toPaint)
                canvas.drawLine(from.first, from.second, to.first, to.second, linePaint)
                drawArrowHead(canvas, from.first, from.second, to.first, to.second)
            }
        }

        private fun drawMeter(canvas: Canvas, board: RectF, rawScore: String?) {
            val width = dp(9).toFloat()
            val gap = dp(5).toFloat()
            val left = board.left - width - gap
            val meter = RectF(left, board.top, left + width, board.bottom)
            canvas.drawRoundRect(meter, dp(4).toFloat(), dp(4).toFloat(), barBackground)
            val value = scoreValue(rawScore)
            val normalized = (0.5f + 0.45f * (value / (1f + kotlin.math.abs(value)))).coerceIn(0.05f, 0.95f)
            val fillTop = meter.bottom - meter.height() * normalized
            canvas.drawRoundRect(RectF(meter.left, fillTop, meter.right, meter.bottom), dp(4).toFloat(), dp(4).toFloat(), barFill)
        }

        private fun centerFor(square: UciSquare, flipped: Boolean, board: RectF, squareW: Float, squareH: Float): Pair<Float, Float> {
            val file = if (flipped) 7 - square.file else square.file
            val rankRow = if (flipped) square.rank - 1 else 8 - square.rank
            return Pair(board.left + (file + 0.5f) * squareW, board.top + (rankRow + 0.5f) * squareH)
        }

        private fun squareRect(cx: Float, cy: Float, squareW: Float, squareH: Float): RectF =
            RectF(cx - squareW / 2f, cy - squareH / 2f, cx + squareW / 2f, cy + squareH / 2f)

        private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
            val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            val length = dp(15).toFloat()
            val spread = Math.toRadians(28.0)
            val path = Path().apply {
                moveTo(x2, y2)
                lineTo(
                    (x2 - length * cos(angle - spread)).toFloat(),
                    (y2 - length * sin(angle - spread)).toFloat()
                )
                lineTo(
                    (x2 - length * cos(angle + spread)).toFloat(),
                    (y2 - length * sin(angle + spread)).toFloat()
                )
                close()
            }
            canvas.drawPath(path, arrowFill)
        }

        private fun scoreValue(raw: String?): Float {
            if (raw.isNullOrBlank()) return 0f
            val number = raw.removePrefix("+").toFloatOrNull()
            if (number != null) return number
            if (raw.startsWith("M")) return if (raw.drop(1).startsWith("-")) -10f else 10f
            return 0f
        }
    }

    private fun formatScore(raw: String): String {
        val value = raw.toFloatOrNull()
        return if (value != null) String.format(Locale.US, "%+.2f", value) else raw
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun verticalMarginParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

// ===== ChessAssistantController.kt =====
/**
 * Connects the host screen's FEN/orientation data to the native overlay.
 * The host must report a trusted screen mode; LIVE_GAME and UNKNOWN are rejected.
 */
class ChessAssistantController(
    private val overlay: ChessAssistantOverlayView,
    private val engine: StockfishOnlineClient = StockfishOnlineClient(),
    private val depth: Int = 12
) : Closeable {
    @Volatile private var currentFen: String = ""
    @Volatile private var lastResult: EngineResult? = null
    @Volatile private var closed = false

    fun onBoardSnapshot(snapshot: BoardSnapshot) {
        if (closed) return

        if (!snapshot.mode.permitsAnalysis()) {
            currentFen = ""
            lastResult = null
            overlay.post { if (!closed) overlay.showUnavailable("Suggestions disabled during live games") }
            return
        }

        val normalizedFen = try {
            FenPosition.parse(snapshot.fen)
            snapshot.fen.trim().replace(Regex("\\s+"), " ")
        } catch (error: Throwable) {
            currentFen = ""
            lastResult = null
            overlay.post {
                if (!closed) overlay.showUnavailable("Invalid board position: ${error.message ?: "bad FEN"}")
            }
            return
        }

        if (normalizedFen == currentFen) {
            val existing = lastResult
            overlay.post {
                if (!closed && currentFen == normalizedFen) {
                    if (existing != null) overlay.showResult(snapshot, existing) else overlay.showLoading(snapshot)
                }
            }
            return
        }

        currentFen = normalizedFen
        lastResult = null
        overlay.post {
            if (!closed && currentFen == normalizedFen) overlay.showLoading(snapshot)
        }

        engine.analyze(normalizedFen, depth) { result ->
            overlay.post {
                if (closed || currentFen != normalizedFen) return@post
                result.fold(
                    onSuccess = {
                        lastResult = it
                        overlay.showResult(snapshot, it)
                    },
                    onFailure = { error ->
                        lastResult = null
                        overlay.showUnavailable("Engine unavailable: ${error.message ?: "request failed"}")
                    }
                )
            }
        }
    }

    override fun close() {
        closed = true
        currentFen = ""
        lastResult = null
        engine.close()
    }
}
