# script.js

対象: `src/main/resources/static/script.js`

## 概要
- フロントエンドの軽量ブートストラップ兼ユーティリティスクリプト。モジュールの逐次ロード、クライアントナビゲーション、時計表示、デバッグユーティリティ等を提供する。

## 主な機能
- モジュールの順次読み込み（loadScript / loadScriptsSequential）
- クライアントサイドの断片（fragment）取得と挿入（navigateTo）
- UI 初期化フック（DOMContentLoaded 時）
- クライアント時計（startClock）とデバッグ出力（dbg）

## 挙動
- DOMContentLoaded イベントでコアモジュール（sidebar_mini_menu.js, profile_modal.js, password_modal.js, logout.js）を順に読み込み、必要な初期化関数を呼ぶ。
- 指定された view（dashboard, users, template 等）に対して /api/fragment/<view> を取得して #main-content に挿入する。users ビューの場合は user_list.js, user_modal.js を動的に読み込む。
- 内部の loadScriptsSequential は各スクリプトを同期的に読み込み順序を保証する。エラーは console.warn / dbg で出力される。

## 細かい指定された仕様
- スクリプトのロードはブラウザの CSP を考慮して行い、可能な限り inline スクリプトや inline イベントハンドラは避ける（addEventListener を使用）。
- navigateTo はレスポンスの Content-Type を見て HTML なら DOM 挿入、JSON なら整形して表示する。401 は /login へリダイレクトする。
- startClock は重複起動検出を行い、1 秒ごとに .current-time 要素を更新する。

## その他
- フロントエンドの構成変更（API パス変更やフラグメント追加）時は script.js の routeMap と依存スクリプトの読み込み箇所を同時に更新すること。
- テストとしては、静的な DOM を用意して loadScriptsSequential と navigateTo の動作を確認することを推奨。

## 主な関数一覧（参考）
- loadScript(url)
- loadScriptsSequential(urls)
- navigateTo(view, push)
- startClock()
- dbg(...args)

## 変更履歴
- 1.0.0 - 2025-12-29: 統一フォーマットでドキュメント化

## コミットメッセージ例
- docs(web): script.js の仕様書を統一フォーマットへ変換
