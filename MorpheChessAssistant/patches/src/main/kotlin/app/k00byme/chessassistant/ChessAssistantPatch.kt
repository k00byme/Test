package app.k00byme.chessassistant

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
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

        // Native move feedback from Chess.com's shared local PositionAnalysis pipeline.
        // PositionAnalysis$b is PlayerMoveFeedback(position, score, lines, classification, ...).
        val nativeFeedbackFingerprint = Fingerprint(
            definingClass = "Lcom/chess/analysis/enginelocal/PositionAnalysis\\$b;",
            name = "<init>",
            returnType = "V",
            parameters = listOf(
                "Lcom/chess/chessboard/variants/e;",
                "Lcom/chess/entities/Score;",
                "Ljava/util/List;",
                "Lcom/chess/compengine/AnalysisMoveClassification;",
                "I",
                "Lcom/chess/analysis/enginelocal/PositionAnalysis\\$c;"
            ),
            isStatic = false
        )
        val nativeFeedbackMethod = nativeFeedbackFingerprint.methodOrNull
            ?: throw nativeFeedbackFingerprint.patchException()
        val nativeFeedbackImplementation = nativeFeedbackMethod.implementation
            ?: throw IllegalStateException("PlayerMoveFeedback constructor has no DEX implementation")
        val nativeFeedbackParameterRegisters = nativeFeedbackMethod.parameters.sumOf { parameter ->
            if (parameter.type == "J" || parameter.type == "D") 2 else 1
        }
        val nativeFeedbackStartRegister = nativeFeedbackImplementation.registerCount
            - nativeFeedbackParameterRegisters
        require(nativeFeedbackStartRegister >= 0) {
            "Invalid parameter register for PlayerMoveFeedback constructor"
        }
        val nativeFeedbackHook = ImmutableMethodReference(
            EXTENSION_CLASS,
            "onNativeMoveFeedback",
            listOf(
                "Ljava/lang/Object;",
                "Ljava/lang/Object;",
                "Ljava/lang/Object;",
                "Ljava/lang/Object;",
                "I",
                "Ljava/lang/Object;"
            ),
            "V"
        )
        nativeFeedbackMethod.addInstruction(
            0,
            BuilderInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                nativeFeedbackStartRegister,
                6,
                nativeFeedbackHook
            )
        )
    }
}
