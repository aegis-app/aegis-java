# DESIGN — AEGIS (Java) 作り直し設計

この文書は、旧実装（単一パッケージ `aegis`、v1.12.1）を責務別パッケージ
構成で書き直した際の対応表と判断記録を残すものです。**機能パリティが
絶対条件**であり、出力文面・イベント ID 規則・保存形式・設定形式は旧と
同一に保っています（旧 `data/` と `config.json` をそのまま引き継げます）。

## 構成対応表（旧 → 新）

| 旧（`aegis` パッケージ） | 新 | 備考 |
| --- | --- | --- |
| Json.java | aegis.core.Json | JSON パーサ/ライタ |
| Models.java | aegis.core.Event / Severity / Location / Scale | モデルを1クラス1ファイルに分割。Version は新規に分離 |
| Config.java | aegis.config.AppConfig | 設定形式は完全互換 |
| HttpFetch.java | aegis.net.Http | リトライ（ネットワーク/5xx、最大3回、1秒→3秒）・gzip 手動展開 |
| ProxySupport.java | aegis.net.ProxyEnv | 環境変数プロキシ（ローカルバイパス・Basic 認証） |
| A2s.java | aegis.a2s.A2sClient / A2sConvert / WarningTracker | REST クライアント、イベント変換、警報の世代管理を分離 |
| A2sStream.java | aegis.a2s.A2sStream | SSE 解析・再生ガード・重複排除・再接続バックオフ |
| Quakes.java | aegis.parse.QuakeParser | 複数電文マージ（種別ランク・40km 近接・深さセンチネル） |
| Eew.java | aegis.parse.EewParser | 予報/警報・scaleTo 99・到達予測（S波 3.5km/s フォールバック） |
| Tsunami.java | aegis.parse.TsunamiParser | グレード別区域テキスト（各4件＋ほかN区域） |
| JmaWeather.java | aegis.weather.JmaForecast / WeatherTables | 気象庁予報・週間/時刻テーブルの整形 |
| Sources.java | aegis.weather.OpenMeteo / aegis.radar.Radar | Open-Meteo（モデル明示・サフィックスキー）とレーダー |
| TyphoonRadar.java | aegis.radar.Radar | JMA ナウキャスト レーダー HTML・タイル計算 |
| NewsSource.java | aegis.news.NewsFeed | NHK RSS（cat0+cat1 統合・災害キーワード優先・最大8件） |
| Notify.java | aegis.notify.Notifiers | Discord/汎用 Webhook・デスクトップ通知 |
| Store.java | aegis.store.SeenStore | seen.tsv（30日剪定）/ history.tsv（10,000行→5,000行ローテーション） |
| Engine.java | aegis.app.Engine | ポーリング・通知判定・定時ブリーフィング |
| Assistant.java | aegis.app.Assistant | 自然文 Q&A |
| Main.java | aegis.app.Main / aegis.app.Dashboard | CLI と Web ダッシュボードを分離 |
| AegisTest.java | aegis.test.AegisTest / A2sTest / TestKit | 検証範囲は同等（判断記録4） |
| FixtureCheck.java | aegis.test.FixtureCheck | 実キャプチャ検証の手動ツール（判断記録6） |

## A2S 公開契約への従い方

- REST `GET /v1/events`（kind/since/limit）と `GET /v1/status`、SSE
  `GET /v1/stream`（Bearer トークン）を使用。
- SSE の `ready` フレーム前に届いたイベントは再生として状態同期のみに
  使い、通知しない。到着済み判定はキー + `updatedAt`。
  再接続バックオフは 5秒→10秒→30秒→60秒。
- トークンは `config.json`（`a2s_url`/`a2s_token`）または環境変数
  `A2S_URL`/`A2S_TOKEN`（優先）からのみ読む。コード内蔵はしない。

## 判断記録

1. **パッケージ分割**: 旧の単一パッケージ（19ファイル）を責務別11
   パッケージ（27ファイル）に再編した。見通しとテスト容易性のためで、
   機能・出力・保存形式は変更していない。テスト対象メソッドはテストが
   別パッケージになったため public にしている（各クラスに明記）。
2. **バージョン振り直し**: 作り直しに伴い 1.0.0 とした（旧は v1.12.1）。
   `aegis.core.Version` が単一の定義箇所。
3. **依存ゼロ・素の javac を維持**: Gradle/Maven は導入しない。旧運用
   （build.sh/build.bat の一括ビルド、オフライン再現、CI の軽さ）を
   そのまま継続できるため。CI は依存解決なしで完結する。
4. **テスト方式と件数**: JUnit は導入せず main 方式を維持（判断3と整合）。
   新テストは 352 件（AegisTest 142 + A2sTest 210）で、旧の 456 件より
   少ない。旧テストにあった重複した文字列検証を代表ケースへ集約した
   ためで、検証範囲（A2S 変換・SSE・地震マージ・警報世代・天気・
   ニュース・レーダー・エンジン・通知・ダッシュボード）は旧と同等に
   保っている。
5. **stations.tsv の保持**: `data/stations.tsv`（4,361地点）と
   `tools/generate_stations.py` は現行コード未使用だが、旧リポジトリと
   同じくデータ資産として保持する。
6. **FixtureCheck の改善**: フィクスチャ不在時に例外で落ちず、スキップ
   を表示して正常終了するようにした。手動ツール・CI 対象外という
   位置づけは旧と同じ。
7. **警報の世代管理**: A2S の警報イベントはコード単位で届くため、
   `WarningTracker` が officeCode + コードの出現/消滅から世代
   （generation）を進め、旧実装の eventId 規則
   `warn-<officeCode>-<code>-g<generation>` を再現する。
8. **時刻依存テストの固定時刻化**: A2sTest の Open-Meteo 時間別テーブル
   検証は、当初フィクスチャ時刻を `LocalDateTime.now()`（次の正時）から
   組み立てていたため、3時間ブロックの割り当てが開始時刻の3での剰余に
   依存し、実行時刻の1/3でのみ合格する時刻爆弾になっていた（14時台は
   合格・15時台は失敗で発覚）。基準時刻を3時間区間の境界である
   2026-11-28 15:00 に固定し、期待値もそこから導く形に直した。実装側の
   `OpenMeteo.hourlyRows(hourly, now)` はもともと時刻を引数で受ける
   設計のため変更なし。A.A.S 再構築でも旧テストに実電文フィクスチャが
   EventStore の72時間保持ガードを越える同種の時刻爆弾があった経緯が
   あり、時刻が絡むテストは「相対フィクスチャ（now−Δで自己整合）」か
   「固定時刻」のどちらかに統一する。

## 未再現・未検証

- **未再現の機能はない。** 旧実装の全機能（CLI 7コマンド、ブリーフィング、
  常駐監視、SSE、ダッシュボード、Q&A、全通知経路、履歴、自己診断）を
  再実装した。
- **実ネットワーク検証は未実施**: この VM から実 A2S・気象庁等への直接
  接続は制約があり、ライブ取得の確認はできていない（旧実装もこの VM
  では未検証だった）。単体テスト 352 件と jar のスモーク確認
  （`version` / `history`）で代替した。実環境での確認は利用者側の
  ビルド・実行時に行う。
