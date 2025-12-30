# DataService
- docs(web): DataService の仕様書を追加
## コミットメッセージ例

- 1.0.0 - 2025-12-30: 新規作成（実装に基づく仕様書）
## 変更履歴

- 出力する文字列は HTML 表示等でサニタイズして使用する。
- 大量データを扱うクエリはパフォーマンスに注意し、必要であればインデックス追加や集計テーブル（マテリアライズドビュー）を検討する。
## 注意事項 / 運用

- SQL 実行時に SQLException が発生した場合は AppLogger にエラーを記録し、安全なデフォルト（0 や空配列）を返す。
## エラーハンドリング

- `public Map<String,Object> getApiStats()` - API 用の軽量統計を返す。
- `public List<Map<String,Object>> getAttackTypeStats()` - URL レジストリから攻撃タイプ別の集計を取得する。
- `public List<Map<String,Object>> getServerStats()` - サーバー統計データを取得する。
- `public List<Map<String,Object>> getServerList()` - サーバーの一覧と当日のアクセス数を取得する。
- `public List<Map<String,Object>> getRecentAlerts(int limit)` - 最近のアラートを取得する。
- `public Map<String,Object> getDashboardStats()` - ダッシュボードで必要な集計をまとめて取得する。
## メソッド一覧と機能（主なもの）

- 攻撃の深刻度判定は `determineSeverityLevel` で攻撃タイプ・ModSecurity severity に基づいて行う。
- サーバーステータス判定（`determineServerStatus`）は最終ログ受信時刻と現在時刻の差で判定（30分以内を online とする）。
- `getRecentAlerts` は最大取得件数をパラメータで指定し、access_time 等を LocalDateTime に変換して返す。
- DB 接続は `DbService.getConnection()` を利用する（例: PreparedStatement を使用して SQL インジェクション対策）。
## 細かい指定された仕様

- 日時は `formatDateTime` で `yyyy-MM-dd HH:mm:ss` の文字列へ変換して返却する。
- 集計系クエリは COUNT / JOIN / GROUP BY を利用し、例外時はログ出力してデフォルト値（0 / 空リスト）を返す。
- JDBC を用いて SQL を直接発行し、ResultSet を Map / List に変換して返す。
## 挙動

- API 用の簡易統計（`getApiStats`）
- 攻撃タイプ別統計取得（`getAttackTypeStats`）
- サーバー一覧取得（`getServerList` / `getServerStats`）
- 最近のアラート取得（`getRecentAlerts`）
- ダッシュボード統計データ取得（`getDashboardStats`）
## 主な機能

- ダッシュボード表示や API で利用する統計情報（アクセス数、攻撃数、サーバー一覧、最新アラート等）を提供する。
- Web フロントエンド向けの集計・表示用データを DB から取得して整形するサービスクラス。
## 概要

対象: `src/main/java/com/edamame/web/service/DataService.java`


