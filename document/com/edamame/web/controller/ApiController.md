# ApiController

対象: `src/main/java/com/edamame/web/controller/ApiController.java`

## 概要
- REST 風の簡易 API エンドポイントを提供するハンドラ。AJAX クライアント向けに統計、アラート、サーバ一覧、攻撃タイプ、ヘルスチェック、フラグメントなどを JSON/HTML で返す。API 側での認証・セキュリティ検証を行う。

## 主な機能
- `/api/stats`, `/api/alerts`, `/api/servers`, `/api/attack-types`, `/api/health`, `/api/fragment/*` の GET ハンドリング
- 認証チェック（Cookie の sessionId を検証）
- API 用のセキュリティヘッダと CORS 設定適用
- 入力（path, query, headers）に対する XSS/SQL インジェクション検査
- 管理者権限が必要なサーバー操作 API（disable/enable）を提供

## 挙動
- セッションが無効な場合は 401 を JSON で返す（リダイレクトは行わない）。
- `validateApiRequest` で User-Agent / Referer / path / query のサニタイズと検査を行い、不正な場合は 400 を返す。
- 出力は Jackson を用いてシリアライズされる。LocalDateTime 対応モジュールが登録されている。
- サーバー操作（POST /api/servers/{id}/{action}）では session を検証し、`UserServiceImpl.isAdmin(username)` による権限判定を行う（非管理者は 403 を返す）。

## 細かい指定された仕様
- CORS ヘッダは最小限に設定（Allow-Origin: *、Allow-Methods: GET, OPTIONS, POST）。運用では限定ドメインへの制限を推奨。
- `handleFragmentApi` は `/api/fragment/{name}` を想定し、`FragmentService` を使って HTML 断片を返す。
- `parseSecureIntParameter` は `limit` パラメータを安全に解析し、デフォルト 20 件を返す。
- サーバー操作の許可対象は `disable` と `enable` のみ。`schedule_add` は廃止され、API 側でも受け付けなくなった。

## メソッド一覧と機能（主なもの）
- `public ApiController(DataService dataService, AuthenticationService authService)` - コンストラクタ
- `public void handle(HttpExchange exchange)` - エントリポイント
- `private void handleApi(HttpExchange exchange)` - ルーティングと共通検証
- `private void applyApiSecurityHeaders(HttpExchange exchange)` - API 用ヘッダ設定
- `private boolean validateApiRequest(HttpExchange exchange)` - 入力の検査
- `private void handleStatsApi(HttpExchange exchange)` - 統計 API
- `private void handleAlertsApi(HttpExchange exchange)` - アラート API
- `private void handleServersApi(HttpExchange exchange)` - サーバ一覧 API
- `private void handleServersPostApi(HttpExchange exchange)` - サーバ操作（disable/enable）POST ハンドラ（管理者チェックあり）
- `private void handleAttackTypesApi(HttpExchange exchange)` - 攻撃タイプ API
- `private void handleHealthApi(HttpExchange exchange)` - ヘルスチェック API
- `private void handleFragmentApi(HttpExchange exchange)` - フラグメント取得 API
- `private void sendJsonError(HttpExchange exchange, int statusCode, String message)` - JSON エラー応答

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成
- 2026-01-09: `schedule_add` アクションを削除（フロントの "後で追加" 廃止に合わせて）
- 2026-01-09: サーバー操作 POST ハンドラに管理者チェックを復活（非管理者は 403 を返す）

## コミットメッセージ例
- docs(web): ApiController の仕様書を更新（schedule_add 廃止、admin チェック復活）
