# ApiController

対象: `src/main/java/com/edamame/web/controller/ApiController.java`

## 概要
- REST 風の簡易 API エンドポイントを提供するハンドラ。AJAX クライアント向けに統計、アラート、サーバ一覧、攻撃タイプ、ヘルスチェック、フラグメントなどを JSON/HTML で返す。API 側での認証・セキュリティ検証を行う。

## 主な機能
- `/api/stats`, `/api/alerts`, `/api/servers`, `/api/attack-types`, `/api/health`, `/api/fragment/*` の GET ハンドリング
- 認証チェック（Cookie の sessionId を検証）
- API 用のセキュリティヘッダと CORS 設定適用
- 入力（path, query, headers）に対する XSS/SQL インジェクション検査

## 挙動
- セッションが無効な場合は 401 を JSON で返す（リダイレクトは行わない）。
- `validateApiRequest` で User-Agent / Referer / path / query のサニタイズと検査を行い、不正な場合は 400 を返す。
- 出力は Jackson を用いてシリアライズされる。LocalDateTime 対応モジュールが登録されている。

## 細かい指定された仕様
- CORS ヘッダは最小限に設定（Allow-Origin: *、Allow-Methods: GET, OPTIONS）。運用では限定ドメインへの制限を推奨。
- `handleFragmentApi` は `/api/fragment/{name}` を想定し、`FragmentService` を使って HTML 断片を返す。
- `parseSecureIntParameter` は `limit` パラメータを安全に解析し、デフォルト 20 件を返す。

## メソッド一覧と機能（主なもの）
- `public ApiController(DataService dataService, AuthenticationService authService)` - コンストラクタ
- `public void handle(HttpExchange exchange)` - エントリポイント
- `private void handleApi(HttpExchange exchange)` - ルーティングと共通検証
- `private void applyApiSecurityHeaders(HttpExchange exchange)` - API 用ヘッダ設定
- `private boolean validateApiRequest(HttpExchange exchange)` - 入力の検査
- `private void handleStatsApi(HttpExchange exchange)` - 統計 API
- `private void handleAlertsApi(HttpExchange exchange)` - アラート API
- `private void handleServersApi(HttpExchange exchange)` - サーバ一覧 API
- `private void handleAttackTypesApi(HttpExchange exchange)` - 攻撃タイプ API
- `private void handleHealthApi(HttpExchange exchange)` - ヘルスチェック API
- `private void handleFragmentApi(HttpExchange exchange)` - フラグメント取得 API
- `private void sendJsonError(HttpExchange exchange, int statusCode, String message)` - JSON エラー応答

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(web): ApiController の仕様書を追加

