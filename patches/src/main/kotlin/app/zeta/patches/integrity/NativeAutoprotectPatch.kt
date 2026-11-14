package app.zeta.patches.integrity

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.resourceFile
import app.zeta.patches.shared.Constants.ZETA

/**
 * zeta 3.51.63 のネイティブ解析結果（libpairipcore.so 直読み）:
 *
 * - ダイアログの正体は PairIP の Play Integrity autoprotect。
 *   ネイティブ関数 0x6bfc4 が `.rodata` の XOR 暗号化 blob を復号して
 *   "com.google.android.gms.play.integrity.autoprotect.LOG_TELEMETRY"
 *   を取り出し、VM 経由で GMS に integrity / telemetry 呼び出しを行う。
 *   GMS 側の判定が Play ストアの「このアプリを Play から入手」画面を出す。
 *   Java 側の LicenseClient（startPaywallActivity の NOP、1.2.3）だけでは
 *   画面が残ったのは、こっちが別経路で生きているため。
 *
 * - 0x6bfc4 内の 2 つのフラグ分岐は、どちらも通常のクリーンアップ／
 *   エピローグへの分岐で、アボートではない。非ゼロ＝前段の失敗で、
 *   GMS 作業をスキップして正常終了する。つまり FLAG 1 を無条件に
 *   飛ばせば、autoprotect 呼び出し自体が丸ごとスキップされる。
 *
 * パッチ: 0x6bfc4 内の FLAG 1 分岐を無条件分岐に置き換える。
 *   ファイルオフセット == 仮想アドレス 0x6c09c（.text）
 *   変更前: cbnz w8, 0x6d7f4  (word 0x3500bac8, LE bytes c8 ba 00 35)
 *   変更後: b    0x6d7f4     (word 0x140005d6, LE bytes d6 05 00 14)
 * 4 バイトのみ。既存のエラー分岐を再利用するため、スタック／レジスタ
 * には触れず、関数は正常終了する。1.2.4 の教訓（VM 起動プログラムの
 * NOP はアプリをクラッシュさせる）とは違い、VM プログラムは削らない。
 *
 * 対象バイト列は .so 全体（608,896 バイト）で 0x6c09c に 1 箇所だけ
 * 存在することが確認済みのため、バイト列検索＋オフセット照合で当てる。
 * 一致しなければ PatchException で失敗させる（別バージョンの .so に
 *  blind patch しない）。
 */
@Suppress("unused")
val bypassPairipAutoprotectNativePatch = rawResourcePatch(
    name = "Bypass PairIP Autoprotect (native)",
    description = "PairIP の Play Integrity autoprotect 呼び出しを libpairipcore.so 内で無効化し、Play ストアの「このアプリを Play から入手」画面が出ないようにします（ネイティブ .so パッチ）。",
    default = true
) {
    compatibleWith(ZETA)

    execute {
        val soPath = "lib/arm64-v8a/libpairipcore.so"
        val soFile = resourceFile(soPath)
        if (!soFile.exists()) {
            throw PatchException(
                "libpairipcore.so が見つかりません ($soPath)。" +
                    "arm64-v8a のライブラリを含む APK でパッチしてください。"
            )
        }

        val bytes = soFile.readBytes()

        // cbnz w8, 0x6d7f4 (word 0x3500bac8, little-endian)
        val original = byteArrayOf(0xc8.toByte(), 0xba.toByte(), 0x00, 0x35)
        // b 0x6d7f4 (word 0x140005d6, little-endian)
        val patched = byteArrayOf(0xd6.toByte(), 0x05, 0x00, 0x14)
        val expectedOffset = 0x6c09c

        // すでにパッチ済みなら何もしない（冪等）。
        if (bytes.size > expectedOffset + 4 &&
            bytes.copyOfRange(expectedOffset, expectedOffset + 4).contentEquals(patched)
        ) {
            return@execute
        }

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
        soFile.writeBytes(bytes)
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
