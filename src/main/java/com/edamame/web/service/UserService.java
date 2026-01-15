package com.edamame.web.service;

import com.edamame.web.dto.UserDto;
import com.edamame.web.dto.PageResult;
import java.util.Optional;

/**
 * ユーザー関連のビジネスロジックインターフェース
 */
public interface UserService {
    /**
     * 検索語でユーザーを検索（ワイルドカードを含めた検索）
     * @param q 検索語（ユーザー名・メール等）
     * @param page 1ベースのページ番号
     * @param size ページサイズ
     * @return ページング結果（total, page, size, items）
     */
    PageResult<UserDto> searchUsers(String q, int page, int size);

    /**
     * 指定ユーザーを取得
     * @param username ユーザー名
     * @return ユーザー情報
     */
    Optional<UserDto> findByUsername(String username);

    /**
     * 指定ユーザーがadminロールを持つかを返す
     * @param username ユーザー名
     * @return adminならtrue
     */
    boolean isAdmin(String username);

    /**
     * ユーザー情報を更新する
     * @param username 対象ユーザー名
     * @param email 更新後メールアドレス
     * @param enabled 有効フラグ
     * @return 更新成功ならtrue
     */
    boolean updateUser(String username, String email, boolean enabled);

    /**
     * ユーザーを削除する
     * @param username 削除対象のユーザー名
     * @return 削除成功ならtrue
     */
    boolean deleteUser(String username);

    /**
     * 全ロール名の一覧を取得
     * @return ロール名リスト
     */
    java.util.List<String> listAllRoles();

    /**
     * 指定ユーザーが持つロールの一覧を取得
     * @param username ユーザー名
     * @return ロール名リスト
     */
    java.util.List<String> getRolesForUser(String username);

    /**
     * ユーザーにロールを追加する
     * @param username ユーザー名
     * @param role ロール名
     * @return 成功ならtrue
     */
    boolean addRoleToUser(String username, String role);

    /**
     * ユーザーからロールを削除する
     * @param username ユーザー名
     * @param role ロール名
     * @return 成功ならtrue
     */
    boolean removeRoleFromUser(String username, String role);

    /**
     * 新規ユーザーを作成する（ユーザーネームの重複は DuplicateResourceException を発生させる）
     * @param username ユーザー名
     * @param email メールアドレス
     * @param enabled 有効フラグ
     * @return 成功ならtrue
     */
    boolean createUser(String username, String email, boolean enabled);

    /**
     * 新規ユーザーを作成し、初期パスワードを生成、必要に応じてアクティベーション用メールを送信する。
     * メール送信先はユーザーのメールアドレスに送る。生成した平文パスワードを返す（失敗時はnull）。
     * @param username ユーザー名
     * @param email メールアドレス
     * @param enabled 有効フラグ（false の場合は activation token を生成してメール内のリンクで有効化する）
     * @return 生成した平文パスワード（失敗時は null）
     */
    String createUserWithActivation(String username, String email, boolean enabled);

    /**
     * アクティベーショントークンを検証し、該当ユーザーを有効化する
     * @param token アクティベーショントークン
     * @return 有効化成功ならtrue
     */
    boolean activateUserByToken(String token);

    /**
     * 指定ユーザーのパスワードをリセット（平文を与える）
     * メソッド内でハッシュ化してDBに保存する
     * @param username ユーザー名
     * @param plainPassword 新しい平文パスワード
     * @return 成功ならtrue
     */
    boolean resetPassword(String username, String plainPassword);

    /**
     * 指定ユーザーのパスワードをサーバ側で生成してリセットする。
     * 生成した平文パスワードを返す（クライアントには一度だけ表示する用途）。
     * @param username ユーザー名
     * @return 生成した平文パスワード（失敗時は null）
     */
    String generateAndResetPassword(String username);

    /**
     * 指定ユーザーのログイン履歴を取得する（最新の N 件）
     * @param username ユーザー名
     * @param limit 取得最大件数
     * @return ログイン履歴（ISO日時文字列または空のリスト）
     */
    java.util.List<java.util.Map<String, String>> getLoginHistory(String username, int limit);

    /**
     * 指定ユーザーに未使用かつ期限切れのアクティベーショントークンが存在するかを返す
     * @param username ユーザー名
     * @return 存在する場合 true
     */
    boolean hasExpiredUnusedActivationToken(String username);

    /**
     * 指定ユーザー宛にアクティベーションメールを再送する（新しいトークンを発行して保存し送信する）
     * @param username ユーザー名
     * @return 送信成功なら true
     */
    boolean resendActivationEmail(String username);

    /**
     * メールアドレス変更リクエストを作成し、6桁コードを新しいメール宛に送信する。
     * サーバ側でコードはハッシュ化して保存され、有効期限が設定される。
     * @param username リクエストを行うユーザー名
     * @param newEmail 新しいメールアドレス
     * @param requestIp 要求元IP（ログ用）
     * @return 作成されたリクエストID（失敗時は -1）
     */
    long requestEmailChange(String username, String newEmail, String requestIp);

    /**
     * メール変更リクエストの6桁コードを検証し、成功したらユーザーのメールアドレスを更新する
     * @param username 検証を行うユーザー名
     * @param requestId リクエストID
     * @param code ユーザーに送信された6桁の数字コード
     * @return 検証成功かつ更新成功なら true
     */
    boolean verifyEmailChange(String username, long requestId, String code);

    /**
     * 指定ユーザーがロールを保持しているか（対象ロールとそれを継承する上位ロールを含めて判定）
     * @param username ユーザー名
     * @param roleName 判定対象のロール名
     * @return 保持していればtrue
     */
    boolean hasRoleIncludingHigher(String username, String roleName);
}
