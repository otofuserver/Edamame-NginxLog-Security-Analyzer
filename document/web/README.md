# Web パッケージ仕様書
最終更新: 2025-12-17
- 重要な API・エンドポイント・エラーケース・自動更新（auto-refresh）や CSP に関する注意点を明記しています。
- ドキュメントは日本語で記述されています。
作業者向けメモ

- コード変更と同時に必ず仕様書を更新してください。
- 仕様変更が発生した場合はこのフォルダ内の該当ファイルを更新し、先頭の「仕様書バージョン」をインクリメントしてください。
運用ルール

- service: サービス層（`service/*.md`）
- security: 認証/セキュリティ関連（`security/*.md`）
- controller: 各コントローラ毎の仕様書（`controller/*.md`）
- config: `config/WebConfig.md`, `config/WebConstants.md`
- アプリケーション実装: `WebApplication.md`
- Web 全体概要: `WebPackageSpec.md`

参考: `document/db/DbDelete.md` のフォーマットに準拠し、以下のドキュメントを含みます。

このフォルダは `src/main/java/com/edamame/web` 配下の Web アプリケーション実装（コントローラ、設定、セキュリティ、サービス）に対応する仕様書を集約します。
仕様書バージョン: 1.0.0


