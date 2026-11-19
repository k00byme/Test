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
    description = "Shows a Stockfish move arrow, source/destination highlights and evaluation in online games, bot games and standalone analysis.",
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

        // Main activity used by Chess.com's online human games and game formats.
        hookActivity("Lcom/chess/realchess/ui/game/RealGameActivity;")
    }
}
