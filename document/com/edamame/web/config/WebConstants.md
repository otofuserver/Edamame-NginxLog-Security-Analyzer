# WebConstants
- docs(web): WebConstants の仕様書を追加
## コミットメッセージ例

- 1.0.0 - 2025-12-31: ドキュメント作成
## 変更履歴

- `public static String extractSessionId(String cookieHeader)` - Cookie ヘッダから sessionId を抽出する。
- `public static String createClearSessionCookieValue()` - セッション Cookie をクリアするヘッダ値を生成する。
- `public static String createSessionCookieValue(String sessionId, boolean rememberMe)` - セッション Cookie の値を生成する。
## メソッド一覧と機能

- `createSessionCookieValue` は Path と HttpOnly を常に付与し、ブラウザでのセキュリティを確保する簡易実装である。実運用では Secure 属性や SameSite の付与も検討すること。
- `REMEMBER_ME_MAX_AGE` は 30 日分の秒数（30*24*60*60）として定義されている。
- Cookie 名は `EDAMAME_SESSION` に固定されている。
## 細かい指定された仕様

- `extractSessionId(cookieHeader)` は Cookie ヘッダ文字列を分割し、`EDAMAME_SESSION=` で始まるクッキーを探してその値を返す。見つからない場合は null を返す。
- `createClearSessionCookieValue()` は Max-Age=0 を付与し、ブラウザから Cookie を削除させる値を生成する。
- `createSessionCookieValue(sessionId, rememberMe)` は `EDAMAME_SESSION` の形式で Cookie 文字列を生成し、Path と HttpOnly を付与する。rememberMe が true の場合は Max-Age を追加する。
## 挙動

- セッション Cookie の生成/クリア用ユーティリティ（`createSessionCookieValue`, `createClearSessionCookieValue`）
- Cookie ヘッダからセッション ID を抽出するユーティリティ（`extractSessionId`）
- ログイン/ログアウト関連の定数（パス、リダイレクト先）
- Remember-me の有効期限定義（秒）
- セッション Cookie 名・パス・HttpOnly 設定の定義
## 主な機能

- Web アプリケーションで共通に使う定数をまとめたユーティリティクラス。セッション Cookie 名やパス、リダイレクト先、Cookie の属性などを管理する。
## 概要

対象: `src/main/java/com/edamame/web/config/WebConstants.java`


