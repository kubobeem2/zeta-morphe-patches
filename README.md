# zeta-morphe-patches

[Morphe](https://github.com/MorpheApp) パッチフレームワークを使用した、AIチャットアプリ **『zeta: AI Chat, Story, Play』** (`com.scatterlab.messenger`) 向けのカスタムパッチリポジトリです。

---

## 🌟 搭載パッチ一覧

<!-- PATCHES_START -->
| パッチ名 | 説明 | 既定値 |
| :--- | :--- | :---: |
| **Block Ads** (`adsPatch`) | バナー広告、インタースティシャル広告、リワード広告SDKのロード・表示を遮断します。 | `true` |
| **Unlock Client Restrictions** (`unlockLimitsPatch`) | クライアント側での入力文字数制限（maxLength）の拡張および送信待機時間の短縮を行います。 | `true` |
| **LLM Tokens Per Second Monitor** (`tpsPatch`) | チャット応答時のトークン生成速度（t/s）をリアルタイム計算し、HUDバッジに表示します。 | `true` |
| **Hermes Persistent Memory** (`hermesMemoryPatch`) | キャラクター別・ユーザー別の記憶（`USER.md` / `MEMORY.md` 相当）をローカル保持し、送信メッセージへ注入します。画面上HUDでいつでも **ON / OFF** の切り替えと設定編集が可能です。 | `true` |
<!-- PATCHES_END -->

---

## 🧠 Hermes Persistent Memory について

画面上に表示されるフローティングHUD（`⚡ 0.0 t/s | Mem: ON`）をタップすることで、設定ダイアログが表示されます：
- **Hermes Memory ON/OFF スイッチ**: 一時的に記憶注入を無効化したい場合にワンタップで切替可能
- **User Profile (USER.md)**: AIに覚えておいてほしいユーザー情報や口調の好みを直接編集・保存可能
- **キャラ別記憶**: アプリ内部ストレージ (`/data/data/com.scatterlab.messenger/files/hermes/`) に保存され、対話ごとに蓄積

---

## ⚡ LLM Tokens Per Second (t/s) モニター

チャットストリーミング受信時に、リアルタイムでトークン生成速度（t/s）を計算して画面上に表示します：
- 瞬間速度（Instant t/s）と平均速度（Avg t/s）
- 生成総トークン数（CJK / 英語のBPE推定）
- 生成所要時間（秒）

---

## 🚀 ビルドとリリース

本リポジトリは GitHub Actions (`.github/workflows/release.yml`) による自動セマンティックリリースに対応しています。
`kubobeem2/zeta-morphe-patches` として GitHub に push すると、CI 上でパッチバイナリ（`.mpp` および `.mpe`）が自動ビルドされ、GitHub Releases に公開されます。

### 手動ビルド
```bash
./gradlew build
```
MorpheApp の GitHub Packages へのアクセス権限を持つ `GITHUB_TOKEN` が設定されている環境でビルドを実行できます。
