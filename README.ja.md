# AEGIS（Java版）

| 項目 | 内容 |
| ---- | ---- |
| バージョン | 1.0.0 |
| 状態 | 作り直し版（旧実装と機能同等） |
| 稼働先 | JDK 21 のある環境（Windows / macOS / Linux） |
| 実装 | Java 21・標準ライブラリのみ・素の javac ビルド（Gradle/Maven なし） |

English: [README.md](README.md) / 設計: [DESIGN.md](DESIGN.md)

AEGIS（Java版）は Aegis ファミリーの防災情報クライアントです。地震・
緊急地震速報（EEW）・津波・気象警報・台風の情報をファミリーの配信ハブ
**A.A.S（A2S）** から受け取り、ブリーフィングと通知を行います。天気予報
（気象庁 / Open-Meteo）・NHK ニュース・雨雲レーダーは従来どおり直接取得
します。

## 機能

- **ブリーフィング（`now`）** — 設定地点の地震・EEW・津波・警報・台風・
  天気と主要ニュースを1メッセージに集約（📌 まとめ付き）。
- **常駐監視（`watch`）** — A2S をポーリング（地震20秒 / EEW 10秒 /
  天気600秒が既定）しつつ、`GET /v1/stream` の SSE 接続で低遅延に受信。
  通知は新規・更新のみ（重複排除、EEW の上方修正時の再通知ルールあり）。
  設定時刻（既定 07:00 / 21:00）にブリーフィングを自動送信。
- **Web ダッシュボード（`serve`）** — http://127.0.0.1:8765/
  （`/api/briefing` / `/api/history`、60秒自動更新）。
- **自然文 Q&A（`ask`）** — 「大宮の天気は？傘は必要？」のような質問に
  取得済みデータから回答。
- **通知** — コンソールと `data/alerts.log` は常時。Discord Webhook・
  汎用 Webhook（Event JSON）・デスクトップ通知（Windows トースト /
  macOS / Linux）は設定時。
- **履歴（`history`）** — `data/history.tsv` に保存（上限10,000行で
  ローテーション）。
- **自己診断（`test`）** — A2S 接続と各データソースを確認し、終了コード
  0/1 を返す。`test-notify` はテスト通知を全経路に送信。

## A2S 連携

- REST: `GET /v1/events`（`kind` / `since` / `limit`）、`GET /v1/status`
- SSE: `GET /v1/stream`（`Authorization: Bearer <トークン>`）。
  `ready` フレーム前に届いたイベントは再生（状態同期のみ・通知しない）。
  重複はキー + `updatedAt` で排除。再接続は 5秒→10秒→30秒→60秒の
  バックオフ。
- トークンは `config.json` の `a2s_url` / `a2s_token`、または環境変数
  `A2S_URL` / `A2S_TOKEN`（環境変数優先）**からのみ**読みます。ソース
  コードにトークンは書きません。
- A2S 未設定の場合、地震・EEW・津波・警報・台風の情報は出ません
  （天気・ニュース・レーダーは動作します）。

## クイックスタート

```bash
./build.sh            # フルビルド: コンパイル+単体テスト+aegis.jar生成（Windows は build.bat）
./run.sh unittest     # 単体テストのみ（352件・ネットワーク不要。build.sh でも実行）
./run.sh test         # 自己診断（各データソースへ実際に接続）
./run.sh test-notify  # テスト通知を1件送信
./run.sh now          # 今すぐブリーフィング
./run.sh watch        # 常駐監視（Ctrl+Cで停止）
./run.sh serve        # Webダッシュボード http://127.0.0.1:8765/
./run.sh ask "大宮の天気は？傘は必要？"
./run.sh history      # 通知履歴
```

ビルド済み jar があれば `java -jar aegis.jar now` でも起動できます。

## 設定（`config.json`）

Python版 AEGIS と同形式です。雛形は `config.example.json`。監視地点・
通知閾値（`notify_min_severity`）・`quake_min_scale`・ポーリング間隔・
ブリーフィング時刻・Webhook URL を変更できます。`config.json` は秘密情報
（A2S トークンや Webhook URL）を含むため Git 管理外です。

## テスト

単体テストは `src/test/java/aegis/test/`（`AegisTest` / `A2sTest`、
JUnit を使わない main 方式＝依存ゼロ）。`FixtureCheck` は実キャプチャ
JSON をフィクスチャ配置時に検証する手動ツールで、無い場合はスキップ
表示で正常終了します。

## ライセンス

MIT — [LICENSE](LICENSE) を参照。
