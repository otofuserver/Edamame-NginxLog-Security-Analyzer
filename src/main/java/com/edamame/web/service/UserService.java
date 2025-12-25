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
     * 新しいユーザーを作成する。内部で一時パスワードを生成して保存する。
     * @param username 作成するユーザー名
     * @param email メールアドレス
     * @param enabled 有効フラグ
     * @return 作成成功ならtrue
     */
    boolean createUser(String username, String email, boolean enabled);
}
