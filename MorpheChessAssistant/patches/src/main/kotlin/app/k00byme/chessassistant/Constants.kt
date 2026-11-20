package app.k00byme.chessassistant

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val CHESS_COM_COMPATIBILITY = Compatibility(
        name = "Chess",
        packageName = "com.chess",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x81B64C,
        targets = listOf(
            AppTarget(
                version = "4.10.28-googleplay"
            )
        )
    )
}
