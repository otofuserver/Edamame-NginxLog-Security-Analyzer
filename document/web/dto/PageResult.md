# PageResult<T>
- `src/main/java/com/edamame/web/dto/UserDto.java`
- `src/main/java/com/edamame/web/controller/UserManagementController.java`（`searchUsers` の戻り値の構造）
## 関連

- ページ数やサイズが不正な値（0や負）でもサーバ側で正規化して返すケースを確認する
- 検索結果が 0 件のとき、`total=0`、`items=[]` となること
## テストケース

- `/api/users` のレスポンスなど、ページングを伴う一覧 API の結果を統一形式で返すために利用される。
## 用途

- `List<T> items` – ページ内のアイテム一覧
- `int size` – 1ページあたりの件数
- `int page` – 現在のページ番号（1始まり）
- `long total` – 全件数
## フィールド（想定）

`PageResult` はページング結果を表す汎用 DTO（Data Transfer Object）です。検索API等からクライアントへ返され、合計件数・現在ページ・ページサイズ・アイテム一覧を含みます。
## 概要

ファイル: `src/main/java/com/edamame/web/dto/PageResult.java`


