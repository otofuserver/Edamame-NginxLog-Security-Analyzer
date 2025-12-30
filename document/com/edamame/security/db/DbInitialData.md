# DbInitialData

対象: `src/main/java/com/edamame/security/db/DbInitialData.java`

## 概要
- アプリケーション起動時に必要な初期データ（settings, roles, users, action_tools, action_rules など）を DB に投入するユーティリティ。
- デフォルトの管理者ユーザーやロール、デフォルト設定値などを作成し、アプリケーションの初期状態を整える。

## 主な機能
- settings テーブルに初期設定を挿入（id=1 の固定レコード）
- roles テーブルの初期ロール挿入（admin/operator/viewer）
- users テーブルの初期管理者アカウント作成（BCrypt ハッシュ化）
- action_tools / action_rules の初期データ投入

## 挙動
- 各初期化関数はテーブルが空の場合にのみデータを挿入する（存在チェックを実施）。
- パスワードは BCrypt でハッシュ化して保存する実装（`BCryptPasswordEncoder` を使用）。
- ロールやユーザーの ID を取得して users_roles テーブルへ関連付けを行う。

## 細かい指定された仕様
- 初期管理者の素のパスワードはドキュメントに明記しない運用が望ましい（ただし初期状態では `admin/admin123` を設定するケースがソースにある）。
- 初期化は idempotent（繰り返し安全）に設計されている：既にデータが存在する場合は挿入をスキップする。

## メソッド一覧と機能（主なもの）
- `public static void initializeDefaultData(DbSession dbSession) throws SQLException` - 初期データ挿入のエントリポイント。
- `private static void initializeSettingsTable(DbSession dbSession) throws SQLException` - settings 初期化。
- `private static void initializeRolesTable(DbSession dbSession) throws SQLException` - roles 初期化。
- `private static void initializeUsersTable(DbSession dbSession) throws SQLException` - users 初期化（管理者作成、roles への割当て）。
- `private static void initializeActionToolsTable(DbSession dbSession) throws SQLException` - action_tools 初期化。
- `private static void initializeActionRulesTable(DbSession dbSession) throws SQLException` - action_rules 初期化。

## その他
- 初期データの変更（パスワードやデフォルトのルール追加）は `specification.txt` と `CHANGELOG.md` に記録すること。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成（ソースに基づく）

## コミットメッセージ例
- docs(db): DbInitialData の仕様書を追加

