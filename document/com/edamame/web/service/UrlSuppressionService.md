# UrlSuppressionService

対象: `src/main/java/com/edamame/web/service/UrlSuppressionService.java`

## 概要
- URL抑止ルール（正規表現・対象サーバー・有効状態など）の検索・登録・更新・削除を担うサービス層。
- ページング付き検索を提供し、UI の一覧表示と CRUD API から利用される。

## 主な機能
- 抑止ルールの検索（キーワード・サーバー・ソート・ページング対応）。
- 新規登録・更新・削除・有効/無効切替の永続化。
- ID での単一取得、サーバー名の正規化。

## 挙動
- 検索では許可されたソートキーのみ受け付け、未指定は `updated_at DESC` を使用。
- サーバーフィルタ指定時は対象サーバーに加え `server_name='all'` も含���て返す。
- ページングは 1 始まりで、size は最大 100 にクランプし、総件数と totalPages を返却。
- 新規・更新・削除・有効切替は `DbService` 経由で即時コミット。

## 細かい指定された仕様
- 正規化: サーバー名が空/未指定なら `all` に統一。
- ソートキー制限: `updated_at, created_at, server_name, url_pattern, is_enabled, last_access_at, drop_count` のみ許容。
- 検索結果は常に `id DESC` を第二ソートとして安定化。

## その他
- 例外発生時は `AppLogger` にエラーを記録し、呼び出し側で適宜レスポンス処理。

## 存在するメソッドと機能
- `public List<Map<String,Object>> search(String q, String serverName, String sort, String order)`: デフォルトページング(1/20)で検索。
- `public SearchResult search(String q, String serverName, String sort, String order, int page, int size)`: ページング付き検索結果を返す。
- `public int create(String serverName, String pattern, String description, boolean enabled, String username)`: 新規登録。
- `public int update(long id, String serverName, String pattern, String description, boolean enabled, String username)`: 既存更新。
- `public int delete(long id)`: 削除。
- `public int toggleEnabled(long id, boolean enabled, String username)`: 有効/無効切替。
- `public Map<String,Object> findById(long id)`: 単一取得。
- `private String normalizeServer(String serverName)`: サーバー名正規化。
- `private LocalDateTime toLocal(Timestamp ts)`: Timestamp から LocalDateTime 変換。
- `private long countTotal(Connection conn, String whereClause, List<Object> params)`: 件数取得。
- `public record SearchResult(...)`: 検索結果のDTO。

## 変更履歴
- 2026-01-20: ページング対応を含む仕様書を新規作成。
