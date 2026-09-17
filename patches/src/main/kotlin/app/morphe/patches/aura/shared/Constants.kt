package app.morphe.patches.aura.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_AURA = Compatibility(
        name = "Aura",
        packageName = "com.facebook.aura",
        apkFileType = ApkFileType.APK_REQUIRED,
        targets = listOf(
            AppTarget(
                version = "7.0.0.25",
                minSdk = 29,
                isExperimental = true
            ),
            AppTarget(
                version = "6.0.0.48.164",
                minSdk = 29,
                isExperimental = true
            ),
        )
    )
}
