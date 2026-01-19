# UrlSuppressionManager

対象: `src/main/java/com/edamame/security/suppression/UrlSuppressionManager.java`

## 概要
- URL抑止判定を担う管理クラス。サーバー名ごと（全体含む）の有効な抑止ルールを取得し、正規表現マッチで抑止対象を判定する。
- マッチした場合はアクセスログへの記録や各種集計へ載せず破棄するためのフックとして利用される。

## 主な機���
- 抑止判定: 与えられたフルURLが有効な抑止ルールに合致するか判定。
- ルール取得: DBの `url_suppressions` から有効ルールを読み込み、正規表現を事前コンパイル。
- ヒット記録: マッチ時に `last_access_at` と `drop_count` を更新し、監査用ログを出力。

## 挙動
- `shouldSuppress` 呼び出しで、サーバー名が空なら `all` を適用し、`url_pattern` 正規表現を大文字小文字無視で評価。
- マッチした最初のルールで true を返し、以降の評価は行わない。
- DBエラーやパターンコンパイル失敗はログに警告を出し、可能な範囲で処理継続。

## 細かい指定された仕様
- 取得対象は `is_enabled = TRUE` かつ `server_name` が対象サーバーまたは `all` のレコード。
- マッチ時に `last_access_at = NOW()` と `drop_count = drop_count + 1` を即時更新。
- 正規表現は `Pattern.CASE_INSENSITIVE` でコンパイル。

## その他
- DB接続は `DbService.getConnection()` を利用し、例外は `AppLogger` に警告/エラーとして記録。

## 存在するメソッドと機能
- `public static boolean shouldSuppress(String serverName, String fullUrl)`: 抑止判定とヒット更新を行い、抑止対象なら true を返��。
- `private static List<SuppressionRule> loadActiveRules(String serverName)`: 有効ルールを取得し正規表現をコンパイルして返す。
- `private static void markHit(long id)`: ヒットしたルールの `last_access_at`/`drop_count` を更新。

## 変更履歴
- 2026-01-20: クラス仕様書を新規作成（URL抑止ルール判定・記録）。
