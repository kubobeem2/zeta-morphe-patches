package app.zeta.patches.integrity

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.zeta.patches.shared.Constants.ZETA

/**
 * zeta 3.51.63 の実物を解析した結果（DEX 直読み）:
 *
 * - Play Integrity API の showDialog はこのアプリに存在しない。
 *   IntegrityManager（旧 play-core）は requestIntegrityToken しか持たず、
 *   呼び出し元は AppsFlyer（広告収益計測）のみ。つまり showDialog 狙いの
 *   パッチは最初から何にも当たっていなかった。1.2.1 / 1.2.2 が効かなかった理由。
 *
 * - ダイアログの正体は PairIP（商用ライセンス検証）のペイウォール。
 *   LicenseClient.processResponse はライセンスサーバーの応答コード 2
 *   (NOT_LICENSED) を受けると、バンドル内の PAYWALL_INTENT（PendingIntent）
 *   を取り出して startPaywallActivity に渡し、Play ストアの
 *   「このアプリを Play から入手」画面を開く。再署名・サイドロードした
 *   アプリはサーバー側の署名検証に必ず失敗するため、必ずここに来る。
 *   startPaywallActivity 内では 30 秒後のアプリ終了も予約される。
 *
 * 対策: startPaywallActivity を先頭で即 return-void し、
 * ペイウォール（Play ストア画面）の起動と終了予約の両方を止める。
 * 呼び出し元は processResponse の 1 箇所のみで、他に影響はない。
 */
object StartPaywallActivityFingerprint : Fingerprint(
    name = "startPaywallActivity",
    returnType = "V",
    parameters = listOf("Landroid/app/PendingIntent;")
)

/**
 * getInstallerPackageName(String) のフィンガープリント。
 * 注意: これは Android フレームワーク (PackageManager) のメソッドで、
 * アプリの DEX 内には定義が存在しない。アプリ側に同名のラッパーが
 * ある場合にのみ当たるベストエフォート。当たらなくても無害。
 */
object GetInstallerPackageNameFingerprint : Fingerprint(
    name = "getInstallerPackageName",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;")
)

/**
 * StartupLauncher.launch() のフィンガープリント。
 * PairIP の暗号化 VM の起動プログラム（"kwqoUTAVViJwNcsX"）を
 * VMRunner.invoke で走らせる入口。アプリ起動時の <clinit>（MainApplication /
 * CoreComponentFactory / AppComponentFactory）から呼ばれる。
 * Play Protect を OFF にしても画面が出続けることから、ペイウォールの
 * 残るトリガーはこの VM 内と推定される。launch() 自体を止めて
 * VM の起動プログラムが走らないようにする実験的対策。
 * クラスで絞る（同名 launch() の誤爆防止）。
 */
object StartupLauncherLaunchFingerprint : Fingerprint(
    name = "launch",
    returnType = "V",
    parameters = emptyList(),
    custom = { _, classDef -> classDef.type == "Lcom/pairip/StartupLauncher;" }
)

/**
 * PairIP のライセンスペイウォール（Google Play「このアプリを Play から入手」
 * 画面）を無力化するパッチ。再署名・サイドロードでライセンス検証に失敗しても
 * Play ストア画面を開かず、アプリをそのまま使えるようにする。
 */
@Suppress("unused")
val bypassPlayIntegrityPatch = patch(
    name = "Bypass PairIP License Paywall",
    description = "PairIP のライセンスペイウォール（Google Play「このアプリを Play から入手」画面）を無効化し、再署名・サイドロードしたアプリでもそのまま使えるようにします。",
    default = true
) {
    compatibleWith(ZETA)

    execute {
        // 1. LicenseClient.startPaywallActivity(...) を先頭で即リターン
        //    （ペイウォール Intent の起動と 30 秒後の終了予約を両方止める）
        try {
            StartPaywallActivityFingerprint.method.addInstructions(
                0,
                "return-void"
            )
        } catch (_: PatchException) {
            // 対象メソッドが存在しない場合はスキップ
        }

        // 2. getInstallerPackageName(String) の戻り値を "com.android.vending" に固定
        try {
            GetInstallerPackageNameFingerprint.method.addInstructions(
                0,
                """
                    const-string v0, "com.android.vending"
                    return-object v0
                """.trimIndent()
            )
        } catch (_: PatchException) {
            // 対象メソッドが存在しない場合はスキップ
        }

        // 3. StartupLauncher.launch() を先頭で即リターン
        //    （PairIP 暗号化 VM の起動プログラムを走らせない。
        //     Play Protect OFF でも画面が出るため、VM 内が残るトリガーと推定）
        try {
            StartupLauncherLaunchFingerprint.method.addInstructions(
                0,
                "return-void"
            )
        } catch (_: PatchException) {
            // 対象メソッドが存在しない場合はスキップ
        }
    }
}
