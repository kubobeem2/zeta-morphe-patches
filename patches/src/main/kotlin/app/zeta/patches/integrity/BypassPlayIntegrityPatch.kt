package app.zeta.patches.integrity

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.zeta.patches.shared.Constants.ZETA

/**
 * Play Integrity API の showDialog (ダイアログ呼び出し) のフィンガープリント
 */
object IntegrityShowDialogFingerprint : Fingerprint(
    returnType = "Lcom/google/android/gms/tasks/Task;",
    parameters = listOf("Landroid/app/Activity;", "I")
)

/**
 * getInstallerPackageName(String) のフィンガープリント
 */
object GetInstallerPackageNameFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
    custom = { method ->
        method.name == "getInstallerPackageName"
    }
)

/**
 * Play Integrity API による「Google Playから入手してください」ダイアログおよび
 * getInstallerPackageName によるストア判定を無力化するパッチ
 */
@Suppress("unused")
val bypassPlayIntegrityPatch = patch(
    name = "Bypass Play Integrity & Installer Check",
    description = "Play Integrity API のダイアログ表示（Google Playから入手してください）およびインストーラー検証を無効化し、強制終了を防ぎます。",
    default = true
) {
    compatibleWith(ZETA)

    execute {
        // 1. showDialog(...) の先頭で Tasks.forResult(0) を即座にリターン
        try {
            IntegrityShowDialogFingerprint.method.addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                    move-result-object v0
                    invoke-static {v0}, Lcom/google/android/gms/tasks/Tasks;->forResult(Ljava/lang/Object;)Lcom/google/android/gms/tasks/Task;
                    move-result-object v0
                    return-object v0
                """.trimIndent()
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
    }
}
