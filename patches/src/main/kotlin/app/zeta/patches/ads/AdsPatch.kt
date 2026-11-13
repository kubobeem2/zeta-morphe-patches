package app.zeta.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.zeta.patches.shared.AdMobLoadAdFingerprint
import app.zeta.patches.shared.AppLovinLoadAdFingerprint
import app.zeta.patches.shared.Constants.ZETA

private const val EXTENSION_CLASS = "Lapp/zeta/extension/ads/AdBlockHelper;"

@Suppress("unused")
val adsPatch = patch(
    name = "Block Ads",
    description = "Zeta アプリ内のバナー広告、インタースティシャル広告、リワード広告SDKのロードを遮断・無力化します。",
    default = true
) {
    compatibleWith(ZETA)

    extendWith("extensions/extension.mpe")

    execute {
        // AdMob loadAd を無力化 (先頭で return-void)
        AdMobLoadAdFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent()
        )

        // AppLovin loadAd を無力化
        AppLovinLoadAdFingerprint.method.addInstructions(
            0,
            """
                return-void
            """.trimIndent()
        )
    }
}
