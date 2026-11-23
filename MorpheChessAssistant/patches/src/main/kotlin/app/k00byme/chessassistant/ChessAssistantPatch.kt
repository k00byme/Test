package app.k00byme.chessassistant

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
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
    description = "Shows three distinct Stockfish MultiPV lines in online games, bot games, and standalone analysis; optional AUTO remains available against bots.",
    default = true
) {
    compatibleWith(Constants.CHESS_COM_COMPATIBILITY)
    extendWith("extensions/extension.mpe")

    execute {
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

        // Game Review reports Chess.com's own per-move classification and mover color.
        val classificationFingerprint = Fingerprint(
            definingClass = "Lcom/chess/gamereview/v2/GameReviewActivity\$c;",
            name = "b0",
            returnType = "V",
            parameters = listOf(
                "Lcom/chess/compengine/AnalysisMoveClassification;",
                "Lcom/chess/entities/Color;"
            ),
            isStatic = false
        )
        val classificationMethod = classificationFingerprint.methodOrNull
            ?: throw classificationFingerprint.patchException()
        val classificationImplementation = classificationMethod.implementation
            ?: throw IllegalStateException("Game Review classification callback has no DEX implementation")
        val classificationParameterRegisters = classificationMethod.parameters.sumOf { parameter ->
            if (parameter.type == "J" || parameter.type == "D") 2 else 1
        }
        val callbackRegister = classificationImplementation.registerCount
            - classificationParameterRegisters - 1
        require(callbackRegister >= 0) {
            "Invalid callback register for GameReviewActivity classification hook"
        }
        val classificationHook = ImmutableMethodReference(
            EXTENSION_CLASS,
            "onOfficialClassification",
            listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
            "V"
        )
        classificationMethod.addInstruction(
            0,
            BuilderInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                callbackRegister,
                3,
                classificationHook
            )
        )

        // Intercept Chess.com's completed PlayerMoveFeedback result instead of
        // executing extension code from inside the app's data-class constructor.
        val nativeFeedbackFingerprint = Fingerprint(
            definingClass = "Lcom/chess/analysis/enginelocal/PositionAnalysis;",
            name = "c",
            returnType = "Lcom/chess/analysis/enginelocal/PositionAnalysis\$b;",
            parameters = listOf(
                "Lcom/chess/chessboard/variants/e;",
                "Ljava/util/List;"
            ),
            isStatic = true
        )
        val nativeFeedbackMethod = nativeFeedbackFingerprint.methodOrNull
            ?: throw nativeFeedbackFingerprint.patchException()
        val nativeFeedbackImplementation = nativeFeedbackMethod.implementation
            ?: throw IllegalStateException("PositionAnalysis.c has no DEX implementation")
        val nativeFeedbackHook = ImmutableMethodReference(
            EXTENSION_CLASS,
            "onNativeMoveFeedbackObject",
            listOf("Ljava/lang/Object;"),
            "V"
        )
        val nativeFeedbackReturns = nativeFeedbackImplementation.instructions
            .mapIndexedNotNull { index, instruction ->
                if (instruction.opcode == Opcode.RETURN_OBJECT) {
                    val returnRegister = (instruction as? OneRegisterInstruction)?.registerA
                        ?: throw IllegalStateException("PositionAnalysis.c has an invalid object return")
                    index to returnRegister
                } else {
                    null
                }
            }
        require(nativeFeedbackReturns.isNotEmpty()) {
            "PositionAnalysis.c has no object return instructions"
        }
        nativeFeedbackReturns.sortedByDescending { it.first }.forEach { (index, register) ->
            nativeFeedbackMethod.addInstruction(
                index,
                BuilderInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE,
                    register,
                    1,
                    nativeFeedbackHook
                )
            )
        }

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

        // Chessboard move-input / PGN pipeline, including every callsite in this method.
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
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/diagrams/puzzle/DiagramPuzzleViewModel;",
            "h3",
            "V",
            listOf("I", "Lcom/chess/chessboard/l;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/diagrams/puzzle/DiagramPuzzleViewModel;",
            "q0",
            "V",
            listOf("Z", "I", "Lcom/chess/chessboard/pgn/b;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/features/analysis/standalone/u;",
            "invoke",
            "Ljava/lang/Object;",
            listOf("Ljava/lang/Object;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/features/puzzles/battle/BattleProblemViewModel;",
            "h3",
            "V",
            listOf("I", "Lcom/chess/chessboard/l;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/features/puzzles/battle/BattleProblemViewModel;",
            "q0",
            "V",
            listOf("Z", "I", "Lcom/chess/chessboard/pgn/b;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/features/puzzles/utils/PuzzlesUtilsKt\$bindMoveFeedback\$1\$1\$a;",
            "emit",
            "Ljava/lang/Object;",
            listOf("Ljava/lang/Object;", "Lcom/google/android/gy0;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/features/reactnativeapps/o;",
            "l",
            "V",
            listOf("Ljava/lang/String;", "Ljava/lang/String;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/features/lessons/challenge/LessonChallengesViewModel;",
            "P4",
            "V",
            listOf("Lcom/chess/features/lessons/challenge/l;")
        )
        hookBoardMoveFeedbackCalls(
            "Lcom/chess/solo/practice/SoloChessboardStateHolderKt\$SoloGameChessboard\$2\$1;",
            "invokeSuspend",
            "Ljava/lang/Object;",
            listOf("Ljava/lang/Object;")
        )

    }
}
