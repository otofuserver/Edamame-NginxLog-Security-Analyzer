# ActivationController

対象: `src/main/java/com/edamame/web/controller/ActivationController.java`

## 概要
- アクティベーション用エンドポイント `/api/activate` を提供するコントローラ。GETリクエストでトークンを受け取り、`UserService.activateUserByToken` を呼び出してユーザーを有効化する。成功/失敗に応じて簡易HTMLページへリダイレクト（20秒後にログインページへ遷移）する。

## 主な機能
- `GET /api/activate?token=...` を処理し、トークン検証とユーザー有効化を行う
- 環境変数 `WEB_BASE_URL`, `USE_SUBDIR`, `WEB_SUBDIR` を参照してログインページのURLを決定
- 成功/失敗を示す簡易HTMLを返し、20秒後にログインページへリダイレクト

## 挙動
- クエリパラメータ `token` が存在しない場合は 400 を返す（JSON）。
- `UserService.activateUserByToken(token)` の戻り値が true の場合は成功ページを表示し、false の場合は失敗ページを表示する。どちらも20秒後にログインページへ遷移する。
- 成功時は対象ユーザーへ有効化完了メールを送信する実装が `UserService.activateUserByToken` 側にある。

## 細かい指定された仕様
- `WEB_BASE_URL` が未指定の場合は `http://localhost:8080` を使用する。
- `WEB_SUBDIR` は正規化して先頭にスラッシュ、末尾は無い形で扱う（例: `/sub`）。
- 表示用URLは `USE_SUBDIR` フラグによりサブディレクトリ版かルート版を選択する。

## その他
- HTML 内の表示文言は日本語で簡潔に記述され、ログインURLへのリンクも含まれる。

## 変更履歴
- 2026-01-05: 新規追加（未コミットの ActivationController 実装に基づき生成）
