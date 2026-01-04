# WebApplication

対象: `src/main/java/com/edamame/web/WebApplication.java`

## 概要
- Webフロントエンドの起動とルーティング、サービス初期化を担当するクラス。`HttpServer` を使って静的リソース・API・ダッシュボードのルーティングを行う。

## 主な機能
- サービス層（`AuthenticationService`, `DataService`）の初期化
- HTTPサーバ（`HttpServer`）の作成とスレッドプールの設定
- ルーティング設定（`/login`, `/logout`, `/static`, `/dashboard`, `/api/*`等）
- サーバの開始・停止・状態チェック

## 挙動
- `initialize()` でサービスを初期化し、`start()` でサーバを作成して `setupRoutes()` と `startServer()` を実行する。
- ルーティングは `AuthenticationFilter` を組み合わせて静的・動的コンテンツを保護する設計。

## 細かい指定された仕様
- デフォルトポートは `WEB_PORT` 環境変数（デフォルト 8080）。`WEB_BIND_ADDRESS` でバインドアドレスを指定可能。
- `/api/activate` は `ActivationController` にルーティングされ、`/api/me/*` などの一部は `UserManagementController` に直接割り当てられる。

## その他
- `start(int port)` と `start()` を提供して、テスト環境と本番環境の起動方法を使い分け可能。

## 変更履歴
- 2026-01-05: ドキュメント自動生成

## メソッド一覧
- `public boolean initialize()` - サービス初期化
- `public boolean start(int port)` - 指定ポートでサーバを起動
- `public boolean start()` - デフォルトポートで起動
- `public void stop()` - サーバ停止
- `public boolean isRunning()` - 起動状態のチェック
