# Web認証仕様書 (Edamame Security Dashboard)

**バージョン**: 1.3.1  
**最終更新**: 2025-07-23  
**対象システム**: Edamame NginxLog Security Analyzer Web Dashboard

## 概要

Edamame Security DashboardのWeb認証システムは、セッションベースの認証機能を提供し、NGINXログセキュリティ分析ダッシュボードへの安全なアクセスを実現します。Java 21の最新機能を活用し、セキュリティ強化とコード品質向上を図っています。

## 技術仕様

### 認証アーキテクチャ

```
ブラウザ → AuthenticationFilter → Controller → AuthenticationService
    ↓              ↓                   ↓               ↓
  Cookie      セッション検証        ビジネスロジック   セッション管理(DB)
```

### セッション管理（DB永続化）

- セッション情報はMySQLの`sessions`テーブルで一元管理
- セッション生成・検証・削除・クリーンアップはすべてDBで実施
- サーバープロセス間・複数サーバー間でセッションが共有可能
- メモリ上のactiveSessionsは廃止


#### セッション仕様
- **セッションID**: UUIDベース
- **有効期限**: 通常24時間、"ログインしたままにする"時は30日間
- **クリーンアップ**: 1時間ごとに期限切れセッションをDBから自動削除

#### セッションフロー
1. 認証成功時、sessionsテーブルにINSERT
2. 各リクエスト時、sessionsテーブルからsession_idでSELECTし有効期限を検証
3. 期限切れならDELETE
4. ログアウト時はDELETE

#### Cookie仕様
- **Cookie名**: `EDAMAME_SESSION`
- **パス**: `/` (全サイト有効)
- **属性**: `HttpOnly`
- **セキュリティ**: SameSite制約なし

---

## 認証フロー

### ログインフロー

1. **初回アクセス**: 未認証ユーザーは `/login` にリダイレクト
2. **認証要求**: ユーザー名・パスワードを POST `/login` で送信
3. **認証処理**: AuthenticationService でユーザー検証
4. **セッション生成**: 認証成功時、UUID ベースのセッションID生成
5. **Cookie設定**: `EDAMAME_SESSION` Cookie でセッションIDを保存
6. **ダッシュボード**: `/dashboard` にリダイレクト

### ログアウトフロー

1. **ログアウト要求**: ユーザーがログアウトボタンをクリック
2. **確認ダイアログ**: JavaScript でログアウト確認モーダル表示
3. **セッション削除**: LogoutController でセッション無効化
4. **Cookie削除**: 複数パターンでセッションCookie削除
5. **リダイレクト**: `/login?logout=success` にリダイレクト
6. **成功メッセージ**: ログイン画面でログアウト成功メッセージ表示

### セッション検証フロー

1. **リクエスト受信**: 保護されたリソースへのアクセス
2. **Cookie取得**: `EDAMAME_SESSION` Cookie からセッションID取得
3. **セッション検証**: AuthenticationService でセッション有効性確認
4. **アクセス制御**:
   - 有効: リクエスト通過
   - 無効: ログイン画面リダイレクト
5. **認証成功時の属性セット**: 認証済みの場合、AuthenticationFilterは`exchange.setAttribute("username", ユーザー名)`で必ずusername属性をセットする。コントローラーは`exchange.getAttribute("username")`で認証ユーザー名を取得し、未認証時の302リダイレクトループを防止する。

## セキュリティ仕様

### XSS対策強化

- **入力検証**: 全ての入力データをWebSecurityUtilsで検証
- **出力エスケープ**: HTMLエスケープを全箇所で適用
- **セキュリティヘッダー**: 統一されたセキュリティヘッダーを自動適用

### SQLインジェクション対策

- **検知機能**: `detectSQLInjection()`で危険なパターンを検知
- **サニタイズ処理**: 入力データの自動サニタイズ
- **ログ出力**: 攻撃試行の詳細ログ記録

### パストラバーサル対策

- **ファイル名検証**: `sanitizeFilename()`で安全化
- **パス検証**: `../`などの危険なパターンを除去
- **アクセス制御**: 許可されたリソースのみアクセス可能

### パスワード管理

- **ハッシュ化**: BCrypt アルゴリズム
- **ソルト**: BCrypt内蔵の自動ソルト生成
- **検証**: `BCryptPasswordEncoder.matches()` で比較

### デフォルトアカウント

- **ユーザー名**: `admin`
- **初期パスワード**: `admin123`
- **ロール**: `administrator`
- **注意**: 本番環境では必ずパスワード変更

## Web設定管理

### WebConfigクラス仕様

#### 設定定数

```java
// セッション設定
public static final String SESSION_COOKIE_NAME = "EDAMAME_SESSION";
public static final int SESSION_TIMEOUT_HOURS = 24;
public static final int REMEMBER_ME_DAYS = 30;

// サーバー設定
public static final int DEFAULT_THREAD_POOL_SIZE = 10;

// アプリケーション設定
public static final String APP_NAME = "Edamame Security Analyzer";
```

#### HTMLテンプレート機能

- **ダッシュボード**: レスポンシブデザインのメイン画面
- **エラーページ**: 統一されたエラー表示画面
- **CSS/JavaScript**: 内蔵の静的リソース提供

#### 拡張switch文の活用

Java 21の機能を活用し、パフォーマンス向上とコード簡潔化を実現：

```java
public String getTemplate(String templateName) {
    return switch (templateName) {
        case "dashboard" -> getDashboardTemplate();
        case "error" -> getErrorTemplate();
        default -> getDefaultTemplate();
    };
}
```

## データベース仕様の参照について

- 認証・セッション管理に関するDBテーブル構造は、すべて`db_schema_spec.md`に集約されています。
- usersテーブル・sessionsテーブルの詳細は`document/db_schema_spec.md`を参照してください。
#### 認証クエリ例

```sql
-- ユーザー認証
SELECT password_hash FROM users
WHERE username = ? AND is_active = TRUE;

-- 最終ログイン時刻更新
UPDATE users SET last_login = CURRENT_TIMESTAMP
WHERE username = ?;

-- セッション有効性検証
SELECT * FROM sessions
WHERE session_id = ? AND expires_at > NOW();

-- セッション削除（ログアウト時/期限切れ時）
DELETE FROM sessions WHERE session_id = ?;
```

---

## エラーハンドリング

### 認証エラー

- **無効な認証情報**: ログイン画面にエラーメッセージ表示
- **セッション期限切れ**: 自動的にログイン画面リダイレクト
- **Cookie不正**: セッション無効化後ログイン画面リダイレクト

### セキュリティエラー

- **XSS攻撃検知**: リクエストをブロックし、詳細ログ出力
- **SQLインジェクション検知**: 攻撃をブロックし、セキュリティログ記録
- **パストラバーサル検知**: ファイルアクセスを拒否し、警告ログ出力

### システムエラー

- **データベース接続エラー**: エラーページ表示
- **セッション管理エラー**: ログ出力後継続動作
- **Cookie処理エラー**: デバッグログ出力

## ログ出力仕様

### ログレベル

- **INFO**: ログイン成功、ログアウト完了
- **WARN**: 認証失敗、セッション期限切れ
- **ERROR**: システムエラー、データベースエラー
- **SECURITY**: XSS・SQLi・パストラバーサル攻撃検知
- **DEBUG**: セッション作成・削除、Cookie処理詳細

### ログ形式例

```
[2025-07-23 19:54:24][INFO] ユーザー認証成功: admin
[2025-07-23 19:54:24][DEBUG] セッション作成: admin (rememberMe: false)
[2025-07-23 19:54:24][SECURITY] XSS攻撃検知 - ユーザー: admin
[2025-07-23 19:54:24][INFO] ログアウト成功: admin (SessionID: b7e23adf...)
```

## API エンドポイント

### 認証関連

| エンドポイント | メソッド | 機能 | 認証要否 |
|--------------|---------|------|----------|
| `/login` | GET | ログイン画面表示 | 不要 |
| `/login` | POST | ログイン処理 | 不要 |
| `/logout` | GET/POST | ログアウト処理 | 不要 |
| `/dashboard` | GET | ダッシュボード | 必要 |
| `/api/*` | * | API機能 | 必要 |
| `/static/*` | GET | 静的リソース | 必要 |

### レスポンス仕様

- **ログイン成功**: 302/303 リダイレクト → `/dashboard`
- **ログアウト成功**: 302/303 リダイレクト → `/login?logout=success`
- **認証失敗**: 200 OK (エラーメッセージ付きHTML)
- **未認証アクセス**: 302 リダイレクト → `/login`

## コード品質向上

### Java 21機能の活用

- **拡張switch文**: パフォーマンス向上とコード簡潔化
- **テキストブロック**: HTMLテンプレートの可読性向上
- **record型**: 不変データクラスの安全性向上（今後導入予定）

### 警告・エラー対策

- **未使用メソッド削除**: コードベースのクリーンアップ
- **固定値パラメーター最適化**: メソッド設計の改善
- **テキストブロック最適化**: 末尾空白文字の除去

## トラブルシューティング

### よくある問題

1. **ログアウト後にダッシュボードに戻る**
   - 原因: Cookie削除不完全、セッション管理の不整合
   - 解決: Cookie名統一、複数パスでの削除処理

2. **セッションが勝手に期限切れになる**
   - 原因: システム時刻の不整合、クリーンアップ処理の誤動作
   - 解決: 時刻同期確認、ログでセッション状態確認

3. **セキュリティ検知が過剰反応する**
   - 原因: WebSecurityUtilsの検知パターンが厳格すぎる
   - 解決: パターンの調整、ホワイトリスト機能の追加

### デバッグ手順

1. **ブラウザ開発者ツール**:
   - Console: `[LOGOUT DEBUG]` ログ確認
   - Network: リダイレクト状況確認
   - Application: Cookie状態確認

2. **サーバーログ**:
   - セッション作成・削除ログ
   - セキュリティ検知ログ
   - エラー詳細情報

## 今後の拡張予定

### セキュリティ強化

- **HTTPS強制**: Clear-Site-Data ヘッダー対応
- **CSRF対策**: トークンベース認証
- **ブルートフォース対策**: ログイン試行回数制限

### 機能拡張

- **多要素認証**: TOTP対応
- **ロールベースアクセス**: 細かい権限制御
- **シングルサインオン**: SAML/OAuth2 連携

### パフォーマンス向上

- **静的リソース**: CDN配信対応
- **テンプレートキャッシュ**: HTMLテンプレートの高速化

---

## バージョン履歴

| バージョン | 日付 | 変更内容 |
|-----------|------|----------|
| 1.0.0 | 2025-07-20 | 初版作成 |
| 1.1.0 | 2025-07-23 | ログアウト機能修正、Cookie名統一 |
| 1.2.0 | 2025-07-23 | リファクタリング完了、セキュリティ強化、Java 21機能活用 |
| 1.3.0 | 2025-07-23 | セッション管理仕様をDB永続化方式に全面更新 |
| 1.3.1 | 2025-07-23 | 認証成功時にAuthenticationFilterがexchangeへusername属性を必ずセットする仕様を明記し、302リダイレクトループ防止策を仕様書に追記 |
| 1.3.2 | 2025-12-17 | 認証処理の耐障害性改善（DB接続切断時のリトライ処理、SQLException詳細ログ化、insertLoginHistoryの堅牢化） |

---

**注意**: この仕様書は実装と連動しています。認証機能を変更する際は本仕様書も同時に更新してください。
