# WebSecurityUtils

対象: `src/main/java/com/edamame/web/security/WebSecurityUtils.java`

## 概要
- Web 向けのセキュリティユーティリティ集。XSS 検知/エスケープ、JSON/URL/ファイル名のサニタイズ、SQL インジェクション簡易検知、CSRF トークン生成など、入力の安全化に役立つ軽量関数群を提供する。

## 主な機能
- `detectXSS`, `escapeHtml`：XSS 検知と HTML エスケープ
- `detectSQLInjection`：SQL インジェクションの簡易検知
- `detectPathTraversal`, `sanitizeFilename`：パストラバーサル対策とファイル名サニタイズ
- `isSafeString`：英数字ハイフン等に限定した安全文字列チェック
- `generateCSRFToken`：UUID ベースの CSRF トークン生成
- `getSecurityHeaders`：推奨 HTTP セキュリティヘッダのマップ返却

## 挙動
- XSS 検知は正規表現パターン群（script/iframe/object/embed, javascript:, event ハンドラ等）を走査して検出する。エスケープは & < > ' " / を HTML エンティティに置換する。
- SQL インジェクション検知は典型的なペイロードの部分一致チェックを行う（簡易検知）。実際の防御は PreparedStatement を利用する方針を明示する。
- サニタイズ関数は改行やタブを空白に置換し長すぎる URL は切り詰める等の表示目的の安全化を行う。

## 細かい指定された仕様
- XSS パターン検知は軽量・保守性優先で正規表現群を用いる（完全検出を保証しない）。
- `escapeHtml` と `escapeJson` はそれぞれ HTML/JSON 表示用に最小限のエスケープを提供する。
- `generateCSRFToken` は UUID を用いるため乱数衝突のリスクは極めて低いが、必要に応じて HMAC などの強化が可能。
- `getSecurityHeaders` で返す Content-Security-Policy は簡易推奨値を含むため、実運用ではサービスに合わせた細かな調整が必要。

## 主なメソッド（既存）
- `public static boolean detectXSS(String input)`
- `public static String escapeHtml(String input)`
- `public static boolean detectSQLInjection(String input)`
- `public static boolean detectPathTraversal(String input)`
- `public static boolean isSafeString(String input)`
- `public static boolean isValidSessionId(String sessionId)`
- `public static String generateCSRFToken()`
- `public static String sanitizeInput(String input)`
- `public static String sanitizeFilename(String filename)`
- `public static String sanitizeUrlForDisplay(String url)`
- `public static String escapeJson(String input)`
- `public static java.util.Map<String,String> getSecurityHeaders()`
- `public static java.util.Map<String,String> parseQueryParams(String query)`

## その他
- 実運用では単一のユーティリティクラスに多数の責務を詰め込むのではなく、用途別に分割（XSS/CSRF/HTTPHeaders/Validation）してテストを充実させることを推奨する。

## 変更履歴
- 1.0.0 - 2025-12-30: ドキュメント作成

## コミットメッセージ例
- docs(web): WebSecurityUtils の仕様書を統一フォーマットへ変換
