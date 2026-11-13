package app.zeta.patches.integrity

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.zeta.patches.shared.Constants.ZETA
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Play Integrity API の showDialog (ダイアログ呼び出し) のフィンガープリント。
 *
 * 以前の指定（戻り値 Task + 引数 (Activity, int) のみ）は曖昧すぎて、
 * DEX 内の同シグネチャの別メソッドに当たるか、そもそも当たらないため
 * ダイアログが消えなかった。name で絞り、さらに抽象メソッド（IntegrityManager
 * インターフェース側の宣言）を除外する。抽象メソッドに addInstructions すると
 * implementation が null で NPE になりパッチ全体が死ぬため、除外が必須。
 */
object IntegrityShowDialogFingerprint : Fingerprint(
    name = "showDialog",
    returnType = "Lcom/google/android/gms/tasks/Task;",
    parameters = listOf("Landroid/app/Activity;", "I"),
    custom = { method, _ ->
        (method.accessFlags and AccessFlags.ABSTRACT.value) == 0
    }
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
