# PageResult

対象: `src/main/java/com/edamame/web/dto/PageResult.java`

## 概要
- ページネーション結果を表す DTO。総件数・現在ページ・ページサイズ・アイテム一覧を保持するジェネリッククラス。

## フィールド
- `long total`
- `int page`
- `int size`
- `List<T> items`

## 挙動
- Jackson 用のデフォルトコンストラクタを実装しているためシリアライズ／デシリアライズに適合する。
- ページング処理の結果を API レスポンスや UI 層でそのまま利用できる形で提供する。

## メソッド一覧
- デフォルトコンストラクタ / 引数付きコンストラクタ
- getter/setter 一式（`getTotal`/`setTotal`, `getPage`/`setPage`, `getSize`/`setSize`, `getItems`/`setItems`）

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(web): PageResult の仕様書を追加

