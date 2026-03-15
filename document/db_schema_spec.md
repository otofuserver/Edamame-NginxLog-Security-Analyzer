# DBスキーマ仕様書

## バージョン情報
- **db_schema_spec version**: v1.0.3
- **最終更新**: 2026-03-16
- **変更概要**: block_ip から trigger_source を廃止し、定義を整理。

## 概要
NGINXログ解析・ModSecurity連携に必要なデータベーステーブル構成を定義する。スキーマ同期は`DbSchema.syncAllTablesSchema`で自動適用され、保持期間はログ���を`DbDelete.runLogCleanupBatch`、ブロックIPを`DbDelete.runBlockIpCleanupBatch`がsettings値に従い削除する。

## 主な機能
- コアテーブル（access_log, modsec_alerts, url_registry など）の自動生成・差分同期
- 設定テーブル（settings）による保持期間・ホワイトリスト設定の集中管理
- ブロックIP管理テーブル（block_ip）による手動/自動ブロックの追跡と保持

## 挙動
- `DbSchema`が起動時に各テーブルを存在チェックし、不足カラムを追加・余剰カラムを削除・型差異を修正する。
- `DbDelete.runLogCleanupBatch`が`settings.log_retention_days`を用いて各ログ系テーブルを削除し、`DbDelete.runBlockIpCleanupBatch`が`settings.block_ip_retention_days`を用いて`block_ip`の期限切れ/解除済みレコードを更新・削除する（EXPIRED/REVOKEDかつupdated_atが閾値超過）。
- `DbInitialData`は`settings`にデフォルト値（log_retention_days=365, block_ip_retention_days=30）を投入する。

## 細かい指定された仕様
### settings テーブル
- 目的: アプリ全体の設定値を単一行で管理。
- カラム:
  - `id` INT PRIMARY KEY（常に1行のみ）
  - `whitelist_mode` BOOLEAN DEFAULT FALSE
  - `whitelist_ip` VARCHAR(370) DEFAULT ''
  - `log_retention_days` INT DEFAULT 365
  - `block_ip_retention_days` INT DEFAULT 30 — ブロックIP保持日数（0以上で有効、負数で無効化）
- 備考: ホワイトリストIPはカンマ区切りで保存する。

### block_ip テーブル
- 目的: 手動/自動のIPブロック履歴を保持し、期限または解除後も一定期間監査用に残す。
- カラム:
  - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
  - `ip_address` VARBINARY(16) NOT NULL — IPv4/IPv6単一IPを格納
  - `service_type` ENUM('MONITOR_BLOCK','APP_LOGIN','MANUAL') NOT NULL — 監視対象ブロック/アプリ不正ログイン/手動
  - `target_agent_name` VARCHAR(128) NULL — 監視対象ブロック時のエージェント名
  - `reason` VARCHAR(255) NOT NULL — 検知理由や手動メモ
  - `start_at` DATETIME NOT NULL — 登録時刻
  - `end_at` DATETIME NULL — NULLは無期限
  - `status` ENUM('ACTIVE','EXPIRED','REVOKED') NOT NULL DEFAULT 'ACTIVE'
  - `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
  - `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
  - `created_by` VARCHAR(64) NOT NULL
  - `updated_by` VARCHAR(64) NOT NULL
- 制約/索引: UNIQUE制約なし。必要に応じてアプリ側で重複登録を制御。ステータス・時刻に基づく削除を行うため`status`と`updated_at`にインデックスを付与することを推奨（将来検討）。
- 運用: 解除時は`status`を`EXPIRED`または`REVOKED`に更新し、`settings.block_ip_retention_days`を超えたものをバッチで削除。

## その他
- スキーマ変更時は必ず本ファイルのバージョン情報を更新し、`CHANGELOG.md`にも記載する。
- 本仕様はMySQL 8.xを前提とし、CHARSET/COLLATEはutf8mb4/utf8mb4_unicode_ciを使用する。

## 変更歴
- v1.0.3 (2026-03-16): block_ip の trigger_source カラムを廃止し、service_type を実装と整合。
- v1.0.2 (2026-03-11): block_ipクリーンアップを専用バッチに分離し仕様を更新。
- v1.0.1 (2026-02-20): block_ipテーブル追加、settingsにblock_ip_retention_days/created_at/updated_atを追加し、クリーンアップ仕様を明記。
- v1.0.0 (2026-02-11): ドキュメント新規作成（既存テーブル仕様の集約開始）。

