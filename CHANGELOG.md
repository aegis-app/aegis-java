# CHANGELOG

## v1.0.0（2026-11-28）

### 追加
- 旧実装（v1.12.1、単一パッケージ構成）を責務別パッケージ構成
  （aegis.core / config / net / a2s / parse / weather / news / radar /
  notify / store / app）で全面再実装し、バージョンを 1.0.0 に振り直した
  初版。機能・出力文面・保存形式・設定形式は旧実装と同等（詳細は
  DESIGN.md の対応表と判断記録）。
- 単体テスト 352 件（AegisTest 142 件 + A2sTest 210 件、ネットワーク不要）。
- 統一開発手順（Issue → ブランチ → PR → Rebase and merge）の土台として
  .github テンプレート・CONTRIBUTING.md・LICENSE・CODE_OF_CONDUCT.md・
  SECURITY.md・CI（JDK 21 + ./build.sh）を整備。
