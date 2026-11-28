package app.k00byme.chessassistant

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val EXTENSION_CLASS = "Lapp/k00byme/chessassistant/extension/ChessAssistantPatch;"

@Suppress("unused")
val chessAssistantPatch = patch(
    name = "Stockfish board assistant",
    description = "Stockfish 19 analysis with green best-move and purple deep-search arrows, exact Coach Icons move badges, and optional AUTO against bots.",
    default = true
) {
    compatibleWith(Constants.CHESS_COM_COMPATIBILITY)
    extendWith("extensions/extension.mpe")

    execute {
        // Copy the Stockfish 19 runtime into the target APK as ordinary assets.
        // The extension .mpe intentionally remains DEX-only; native code and the
        // NNUE network are shipped by this parent .mpp patch.
        fun copyBundledResource(bundlePath: String, apkPath: String) {
            val loader = Class.forName(
                "app.k00byme.chessassistant.ChessAssistantPatchKt"
            ).classLoader
            val input = loader.getResourceAsStream(bundlePath)
                ?: throw IllegalStateException("Stockfish resource missing from patch bundle: $bundlePath")
            val output = resourceFile(apkPath, false)
            output.parentFile?.mkdirs()
            input.use { source ->
                output.outputStream().use { target -> source.copyTo(target) }
            }
            require(output.isFile && output.length() > 0L) {
                "Could not add Stockfish resource to the APK: $apkPath"
            }
        }

        copyBundledResource(
            "native-stockfish/arm64-v8a/libchesswow_stockfish.so",
            "assets/stockfish/native/arm64-v8a/libchesswow_stockfish.so"
        )
        copyBundledResource(
            "native-stockfish/armeabi-v7a/libchesswow_stockfish.so",
            "assets/stockfish/native/armeabi-v7a/libchesswow_stockfish.so"
        )
        copyBundledResource(
            "native-stockfish/nn-1a298aa575a0.nnue",
            "assets/stockfish/nn-1a298aa575a0.nnue"
        )
        copyBundledResource(
            "native-stockfish/COPYING",
            "assets/stockfish/COPYING"
        )
        copyBundledResource(
            "native-stockfish/AUTHORS",
            "assets/stockfish/AUTHORS"
        )
        copyBundledResource(
            "native-stockfish/STOCKFISH-SOURCE.txt",
            "assets/stockfish/STOCKFISH-SOURCE.txt"
        )

        // Ship the exact SVG-derived Coach Icons artwork/color table as APK assets.
        // The extension loads only these named resources; no APK drawable guessing.
        val coachIconNames = listOf(
            "brilliant", "great", "best", "excellent", "good", "book",
            "inaccuracy", "mistake", "miss", "blunder", "forced"
        )
        coachIconNames.forEach { iconName ->
            copyBundledResource(
                "coach-icons/$iconName.xml",
                "assets/coach-icons/$iconName.xml"
            )
        }
        copyBundledResource(
            "coach-icons/ATTRIBUTION.txt",
            "assets/coach-icons/ATTRIBUTION.txt"
        )

        fun hookActivity(className: String) {
            val fingerprint = Fingerprint(
                definingClass = className,
                name = "onCreate",
                returnType = "V",
                parameters = listOf("Landroid/os/Bundle;"),
                isStatic = false
            )
            val method = fingerprint.methodOrNull ?: throw fingerprint.patchException()
            val implementation = method.implementation
                ?: throw IllegalStateException("onCreate has no DEX implementation: $className")

            // p0 is the Activity instance. Build the invoke instruction directly,
            // avoiding the inline Smali parser used by addInstructions(String).
            val parameterRegisters = method.parameters.sumOf { parameter ->
                if (parameter.type == "J" || parameter.type == "D") 2 else 1
            }
            val activityRegister = implementation.registerCount - parameterRegisters - 1
            require(activityRegister >= 0) {
                "Invalid Activity register for $className.onCreate"
            }

            val installMethod = ImmutableMethodReference(
                EXTENSION_CLASS,
                "install",
                listOf("Landroid/app/Activity;"),
                "V"
            )
            method.addInstruction(
                0,
                BuilderInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE,
                    activityRegister,
                    1,
                    installMethod
                )
            )
        }

        // Standalone analysis / post-game review.
        hookActivity("Lcom/chess/features/analysis/standalonev2/StandaloneAnalysisActivityV2;")

        // Dedicated Chess.com Play-vs-Bots activity.
        hookActivity("Lcom/chess/features/versusbots/game/BotGameActivityV2;")

        // Real online games. Keep the same visual analysis overlay enabled here.
        hookActivity("Lcom/chess/realchess/ui/game/RealGameActivity;")

        // Intercept board MoveFeedback immediately after each constructor call.
        // No extension code runs inside Chess.com's constructors or static initialization.
        val boardMoveFeedbackHook = ImmutableMethodReference(
            EXTENSION_CLASS,
            "onBoardMoveFeedback",
            listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            "V"
        )
        val boardMoveFeedbackClass = "Lcom/chess/chessboard/vm/movesinput/c0;"
        val boardMoveFeedbackParameters = listOf(
            "Lcom/chess/chessboard/l;",
            "Lcom/chess/entities/FeedbackType;"
        )

        fun hookBoardMoveFeedbackCalls(
            className: String,
            methodName: String,
            returnType: String,
            parameters: List<String>
        ) {
            val fingerprint = Fingerprint(
                definingClass = className,
                name = methodName,
                returnType = returnType,
                parameters = parameters,
                isStatic = false
            )
            val method = fingerprint.methodOrNull ?: throw fingerprint.patchException()
            val implementation = method.implementation
                ?: throw IllegalStateException("$className->$methodName has no DEX implementation")

            val callSites = implementation.instructions.mapIndexedNotNull { index, instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                if ((instruction.opcode == Opcode.INVOKE_DIRECT
                            || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE)
                    && reference != null
                    && reference.definingClass == boardMoveFeedbackClass
                    && reference.name == "<init>"
                    && reference.returnType == "V"
                    && reference.parameterTypes.map { it.toString() } == boardMoveFeedbackParameters
                ) {
                    index to instruction
                } else {
                    null
                }
            }
            require(callSites.isNotEmpty()) {
                "No board MoveFeedback constructor call found in $className->$methodName"
            }

            callSites.sortedByDescending { it.first }.forEach { (index, instruction) ->
                val hookInstruction = when (instruction) {
                    is FiveRegisterInstruction -> {
                        require(instruction.registerCount == 3) {
                            "Unexpected board MoveFeedback argument count in $className->$methodName"
                        }
                        BuilderInstruction35c(
                            Opcode.INVOKE_STATIC,
                            2,
                            instruction.registerD,
                            instruction.registerE,
                            0,
                            0,
                            0,
                            boardMoveFeedbackHook
                        )
                    }
                    is RegisterRangeInstruction -> {
                        require(instruction.registerCount == 3) {
                            "Unexpected board MoveFeedback range in $className->$methodName"
                        }
                        BuilderInstruction3rc(
                            Opcode.INVOKE_STATIC_RANGE,
                            instruction.startRegister + 1,
                            2,
                            boardMoveFeedbackHook
                        )
                    }
                    else -> throw IllegalStateException(
                        "Unsupported board MoveFeedback invoke format in $className->$methodName"
                    )
                }
                method.addInstruction(index + 1, hookInstruction)
            }
        }

                // Live chessboard PGN / move-input feedback for active games.
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/chessboard/vm/variants/pgn/a;",
            "f",
            "V",
            listOf(
                "Lcom/chess/chessboard/l;",
                "Lcom/chess/chessboard/vm/movesinput/MoveVerification;",
                "Z"
            )
        )
        
        // Current live move classifications are returned as PositionAnalysis$b.
        // Instrument each object return (including null branches safely) and pass
        // the native record to the extension without touching Game Review or puzzles.
        val positionAnalysisHook = ImmutableMethodReference(
            EXTENSION_CLASS,
            "onNativeMoveFeedbackObject",
            listOf("Ljava/lang/Object;"),
            "V"
        )
        val positionAnalysisFingerprint = Fingerprint(
            definingClass = "Lcom/chess/analysis/enginelocal/PositionAnalysis;",
            name = "c",
            returnType = "Lcom/chess/analysis/enginelocal/PositionAnalysis\$b;",
            parameters = listOf(
                "Lcom/chess/chessboard/variants/e;",
                "Ljava/util/List;"
            ),
            isStatic = true
        )
        val positionAnalysisMethod = positionAnalysisFingerprint.methodOrNull
            ?: throw positionAnalysisFingerprint.patchException()
        val positionAnalysisImplementation = positionAnalysisMethod.implementation
            ?: throw IllegalStateException("PositionAnalysis.c has no DEX implementation")

        val positionAnalysisReturns = positionAnalysisImplementation.instructions.mapIndexedNotNull {
                index, instruction ->
            if (instruction.opcode == Opcode.RETURN_OBJECT) {
                val returnInstruction = instruction as? OneRegisterInstruction
                    ?: throw IllegalStateException("PositionAnalysis.c has an invalid object return")
                index to returnInstruction.registerA
            } else {
                null
            }
        }
        require(positionAnalysisReturns.isNotEmpty()) {
            "PositionAnalysis.c has no object return instructions"
        }

        positionAnalysisReturns.sortedByDescending { it.first }.forEach { (index, register) ->
            positionAnalysisMethod.addInstruction(
                index,
                BuilderInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE,
                    register,
                    1,
                    positionAnalysisHook
                )
            )
        }
    }
}
