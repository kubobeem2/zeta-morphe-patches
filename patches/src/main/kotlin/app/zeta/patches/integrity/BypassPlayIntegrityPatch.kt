package app.zeta.patches.integrity

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.zeta.patches.shared.Constants.ZETA

/**
 * zeta 3.51.63 の実物を解析した結果（DEX 直読み＋ネイティブ直読み）:
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
 * - さらにネイティブ側にも別経路がある。libpairipcore.so 内の関数 0x6bfc4 が
 *   `.rodata` の XOR 暗号化 blob を復号して
 *   "com.google.android.gms.play.integrity.autoprotect.LOG_TELEMETRY"
 *   を取り出し、VM 経由で GMS に integrity / telemetry 呼び出しを行う。
 *   Java 側だけ潰しても画面が残ったのは、こっちが別経路で生きているため。
 *   0x6bfc4 内の FLAG 1 分岐（0x6c09c）は通常のクリーンアップへの分岐で、
 *   無条件に飛ばせば autoprotect 呼び出し自体が丸ごとスキップされる。
 *
 * - さらに v1.4.1 で起動直後にクラッシュすることが判明。logcat の
 *   tombstone によると libpairipcore.so の 0x59310 で SIGSEGV。
 *   0x5925c の FNV-1a integrity verifier が原因。Loop 1 で Morphe の
 *   DEX 編集を検出して不一致になり、Loop 2 がバッファを越えて
 *   PROT_NONE のガードページを読みに行って死ぬ。0x592d8 の分岐を
 *   無条件化すれば常に desc[0x18]「primary region matched」を返し、
 *   Loop 2 は走らない。
 *
 * - さらに Java 側の SignatureCheck.verifyIntegrity は Morphe の署名を
 *   検出すると SignatureTamperedException を投げる（v1.2.4 で確認）。
 *   DEX 側も NOP して二重に塞ぐ。
 *
 * 対策（1つのパッチで全部）:
 *   1. startPaywallActivity を先頭で即 return-void（ペイウォール Intent の
 *      起動と 30 秒後の終了予約を両方止める）
 *   2. getInstallerPackageName の戻り値を "com.android.vending" に固定
 *   3. LicenseClient.checkLicense を先頭で即 return-void（ライセンス
 *      サービスへの接続自体を止める）
 *   4. libpairipcore.so の 0x6c09c を cbnz から b に書き換え
 *      （autoprotect 呼び出しをスキップ）
 *   5. libpairipcore.so の 0x592d8 を b.ne から b に書き換え
 *      （integrity verifier を迂回し、常に「一致」を返す）
 *   6. SignatureCheck.verifyIntegrity を先頭で即 return-void
 *      （署名チェックの例外を止める）
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
 * LicenseClient.checkLicense(Context) のフィンガープリント。
 * ライセンスチェックの入口（PUBLIC STATIC）。ここを先頭で即リターン
 * すればライセンスサービスへの接続自体が行われず、応答も来ないため
 * ペイウォールもエラーダイアログも出ない。startPaywallActivity の
 * NOP（1.2.3）だけでは画面が残ったため、Java 側の残留寄与を排除する
 * 安い実験として追加。VM の autoprotect とは別経路。
 */
object CheckLicenseFingerprint : Fingerprint(
    name = "checkLicense",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    custom = { _, classDef -> classDef.type == "Lcom/pairip/licensecheck/LicenseClient;" }
)

/**
 * SignatureCheck.verifyIntegrity(Context) のフィンガープリント。
 * Morphe の署名を検出すると SignatureTamperedException を投げる
 * （v1.2.4 で確認）。ここを先頭で即リターンすれば署名チェック自体が
 * 走らない。ネイティブの integrity verifier 迂回（手順 5）との二重化。
 */
object VerifyIntegrityFingerprint : Fingerprint(
    name = "verifyIntegrity",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    custom = { _, classDef -> classDef.type == "Lcom/pairip/SignatureCheck;" }
)

/**
 * PairIP のライセンスペイウォール（Google Play「このアプリを Play から入手」
 * 画面）を無力化するパッチ。再署名・サイドロードでライセンス検証に失敗しても
 * Play ストア画面を開かず、アプリをそのまま使えるようにする。
 * Java のライセンスチェックと署名チェック、ネイティブの Play Integrity
 * autoprotect 呼び出しと integrity verifier の両方を止める。
 */
@Suppress("unused")
val bypassPlayIntegrityPatch = patch(
    name = "Bypass PairIP License Paywall",
    description = "PairIP のライセンスペイウォール（Google Play「このアプリを Play から入手」画面）を無効化し、再署名・サイドロードしたアプリでもそのまま使えるようにします。Java のライセンスチェックと署名チェック、ネイティブの Play Integrity autoprotect 呼び出しと integrity verifier を止めます。",
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

        // 3. LicenseClient.checkLicense(Context) を先頭で即リターン
        //    （ライセンスサービスへの接続自体を止め、Java 側の寄与を排除）
        try {
            CheckLicenseFingerprint.method.addInstructions(
                0,
                "return-void"
            )
        } catch (_: PatchException) {
            // 対象メソッドが存在しない場合はスキップ
        }

        // 4. libpairipcore.so の autoprotect 分岐を無条件分岐に置き換え
        //    0x6c09c: cbnz w8, 0x6d7f4 (c8 ba 00 35) -> b 0x6d7f4 (d6 05 00 14)
        //    （GMS への autoprotect 呼び出しを丸ごとスキップ）
        // 5. libpairipcore.so の integrity-verifier 分岐を無条件分岐に置き換え
        //    0x592d8: b.ne 0x592e8 (81 00 00 54) -> b 0x592dc (01 00 00 14)
        //    （Loop 2 を迂回し、常に desc[0x18]「primary region matched」を返す）
        //    手順 4・5 は同じ .so の同じバイト列を共有するため、最後に一度だけ書き戻す。
        val soPath = "lib/arm64-v8a/libpairipcore.so"
        val soFile = resourceFile(soPath)
        if (!soFile.exists()) {
            throw PatchException(
                "libpairipcore.so が見つかりません ($soPath)。" +
                    "arm64-v8a のライブラリを含む APK でパッチしてください。"
            )
        }

        val bytes = soFile.readBytes()
        var modified = false

        // 手順 4
        // cbnz w8, 0x6d7f4 (word 0x3500bac8, little-endian)
        val original = byteArrayOf(0xc8.toByte(), 0xba.toByte(), 0x00, 0x35)
        // b 0x6d7f4 (word 0x140005d6, little-endian)
        val patched = byteArrayOf(0xd6.toByte(), 0x05, 0x00, 0x14)
        val expectedOffset = 0x6c09c

        // すでにパッチ済みならこの手順だけスキップ（冪等）。
        if (!(bytes.size > expectedOffset + 4 &&
                bytes.copyOfRange(expectedOffset, expectedOffset + 4).contentEquals(patched))
        ) {
            // バイト列を検索し、0x6c09c に 1 箇所だけ存在することを確認する。
            val occurrences = mutableListOf<Int>()
            var index = bytes.indexOfSubarray(original)
            while (index >= 0) {
                occurrences.add(index)
                index = bytes.indexOfSubarray(original, index + 1)
            }
            if (occurrences.size != 1 || occurrences[0] != expectedOffset) {
                throw PatchException(
                    "libpairipcore.so の autoprotect 分岐が見つかりません。" +
                        "想定オフセット 0x6c09c のバイト列が ${occurrences.size} 箇所見つかりました " +
                        "(${occurrences.joinToString { "0x" + it.toString(16) }})。" +
                        "対応する libpairipcore.so のバージョンが違います。"
                )
            }

            patched.copyInto(bytes, expectedOffset)
            modified = true
        }

        // 手順 5
        // b.ne 0x592e8 (word 0x54000081, little-endian)
        // b 0x592dc (word 0x14000001, little-endian)
        val verifierPatched = byteArrayOf(0x01, 0x00, 0x00, 0x14)
        val verifierOffset = 0x592d8
        // 12 バイトのアンカー（cmp x11, x13 + b.ne 0x592e8 + mov w8, #0x18）。
        // 4 バイトの b.ne だけでは .so 内に 34 箇所あるため、アンカーで一意に特定する。
        val verifierAnchor = byteArrayOf(
            0x7f, 0x01, 0x0d, 0xeb.toByte(),
            0x81.toByte(), 0x00, 0x00, 0x54,
            0x08, 0x03, 0x80.toByte(), 0x52
        )
        val verifierAnchorOffset = 0x592d4

        // すでにパッチ済みならこの手順だけスキップ（冪等）。
        // （パッチ済みだとアンカー内の b.ne が書き換わっているため、先に確認する）
        if (!(bytes.size > verifierOffset + 4 &&
                bytes.copyOfRange(verifierOffset, verifierOffset + 4).contentEquals(verifierPatched))
        ) {
            // アンカーを検索し、0x592d4 に 1 箇所だけ存在することを確認する。
            val anchorOccurrences = mutableListOf<Int>()
            var anchorIndex = bytes.indexOfSubarray(verifierAnchor)
            while (anchorIndex >= 0) {
                anchorOccurrences.add(anchorIndex)
                anchorIndex = bytes.indexOfSubarray(verifierAnchor, anchorIndex + 1)
            }
            if (anchorOccurrences.size != 1 || anchorOccurrences[0] != verifierAnchorOffset) {
                throw PatchException(
                    "libpairipcore.so の integrity-verifier 分岐が見つかりません。" +
                        "想定オフセット 0x592d4 のアンカーが ${anchorOccurrences.size} 箇所見つかりました " +
                        "(${anchorOccurrences.joinToString { "0x" + it.toString(16) }})。" +
                        "対応する libpairipcore.so のバージョンが違います。"
                )
            }

            verifierPatched.copyInto(bytes, verifierOffset)
            modified = true
        }

        if (modified) {
            soFile.writeBytes(bytes)
        }

        // 6. SignatureCheck.verifyIntegrity(Context) を先頭で即リターン
        //    （Morphe の署名を検出したときの SignatureTamperedException を止める）
        try {
            VerifyIntegrityFingerprint.method.addInstructions(
                0,
                "return-void"
            )
        } catch (_: PatchException) {
            // 対象メソッドが存在しない場合はスキップ
        }
    }
}

/** [ByteArray] 内で [pattern] が最初に現れるオフセットを返す。見つからなければ -1。 */
private fun ByteArray.indexOfSubarray(pattern: ByteArray, fromIndex: Int = 0): Int {
    if (pattern.isEmpty() || size < pattern.size) return -1
    outer@ for (i in fromIndex..size - pattern.size) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }
        return i
    }
    return -1
}
