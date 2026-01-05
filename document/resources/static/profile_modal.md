# profile_modal.js

対象: `src/main/resources/static/profile_modal.js`

## 概要
- サイドメニューのプロフィールモーダルを制御するクライアントサイド JavaScript モジュール。ユーザー自身の表示・編集（ユーザー名、メール）と最近のログイン情報の表示を提供する。さらに、新しいメールアドレス変更時の所有者確認（6桁確認コード送信と検証）UIを内蔵する。

## 主な機能
- プロフィールモーダルの開閉と初期化（`ProfileModal.open(showLogins)`）
- ユーザー名・メールの表示および編集モード切替
- クライアント側のメール入力許可文字チェック（英数字と @ . _ % + - 等）と貼り付け時の不正文字除去
- メール形式の最終チェック（正規表現: `/^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/`）
- メール変更時にサーバへ PUT /api/me/profile を送信し、サーバが検証フロー開始を応答した場合は確認コード入力モーダルを表示して POST /api/me/email-change/verify へ検証リクエストを行う

## 挙動
- open(showLogins) を呼ぶと `/api/me/profile` (GET) を呼び、返却 JSON からユーザー情報とログイン履歴を取得してモーダルに反映する。
- メール編集ボタンは2段階（編集 -> 保存）。保存時にクライアント側で正規表現チェックを行い、通れば `/api/me/profile` に PUT 送信する。
- サーバがレスポンスで `{ verificationRequired: true, requestId: <id> }` を返した場合、クライアントは内部モーダルを表示してユーザーに6桁コード入力を促す。入力後、`POST /api/me/email-change/verify` に JSON ボディ `{ requestId, code }` を送信し、成功すれば UI のメール値を確定する。
- モーダルは DOM ベースで生成し、ブラウザのポップアップブロッカーに依存しない。表示できない環境ではフォールバックで window.prompt を使用する。

## 細かい指定された仕様
- 許可文字チェック: 入力中に正規表現 `/^[A-Za-z0-9@._+%\-]*$/` にマッチしない文字は自動除去してユーザーに警告表示。
- 最終のメール形式チェックには RFC 完全準拠ではないが実用的な正規表現 `/^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/` を使用する。
- 確認コード入力モーダル
  - 数字6桁のみ許可（`/^\d{6}$/`で検証）
  - モバイル向けに input に `autocomplete="one-time-code"` と `inputmode="numeric"` を設定
  - モーダルは document.documentElement に追加して stacking-context の影響を減らす
  - フォールバック: モーダルが可視化できない場合 100ms 後に prompt を表示
- サーバ通信
  - PUT /api/me/profile: JSON { name, email }（認証は Cookie セッションを利用）
  - POST /api/me/email-change/request: （UI 観点では PUT /api/me/profile がリクエストを作る。サーバ側で request を作成する API が存在する）
  - POST /api/me/email-change/verify: JSON { requestId, code }
- エラーハンドリング
  - 400/401/403/500 等はレスポンスの本文をユーザーに表示する（profile-error 要素）
  - 通信エラー時は profile-error にメッセージ表示

## その他
- 実装上、console.debug の開発ログは除去されている（production 向け）。開発時に再度必要なら一時的にログを入れてデバッグ可。
- UI の改善余地: 確認コード入力を専用モーダルに統合済みだが、再送ボタン・残り有効時間表示・リトライ制御（attempts）などの UX 改善が推奨される。

## 変更履歴
- 2026-01-05 - v1.0.0: 新規作成。モーダルベースの確認コード UI とフォールバック機能を実装。

## コミットメッセージ例
- docs(web): profile_modal.js の仕様書を追加
- feat(ui): profile_modal.js にメール確認コードモーダルを実装

