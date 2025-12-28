package com.edamame.web.config;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Web設定クラス
 * Webアプリケーションの設定情報を管理
 */
public class WebConfig {

    // セッション設定
    public static final int SESSION_TIMEOUT_HOURS = 24;
    public static final int REMEMBER_ME_DAYS = 30;

    // サーバー設定
    public static final int DEFAULT_THREAD_POOL_SIZE = 10;

    // アプリケーション設定
    public static final String APP_NAME = "Edamame Security Analyzer";

    // （以前は補助メソッド参照用の未使用フィールドを置いていましたが、静的解析
    // の警告を避けるため不要なフィールドは削除しました。）

    /**
     * デフォルトコンストラクタ
     */
    public WebConfig() {
        // 設定の妥当性検証
        validateConfiguration();
    }

    /**
     * 設定の妥当性を検証
     */
    private void validateConfiguration() {
        if (SESSION_TIMEOUT_HOURS < 1 || SESSION_TIMEOUT_HOURS > 168) { // 1時間〜7日
            throw new IllegalStateException("セッションタイムアウトは1-168時間の範囲で設定してください");
        }

        if (REMEMBER_ME_DAYS < 1 || REMEMBER_ME_DAYS > 365) { // 1日〜1年
            throw new IllegalStateException("Remember Me期間は1-365日の範囲で設定してください");
        }

        if (DEFAULT_THREAD_POOL_SIZE < 1 || DEFAULT_THREAD_POOL_SIZE > 100) {
            throw new IllegalStateException("スレッドプールサイズは1-100の範囲で設定してください");
        }
    }

    /**
     * アプリケーションタイトルを取得
     * @return アプリケーションタイトル
     */
    public String getAppTitle() {
        return APP_NAME;
    }

    /**
     * アプリケーション説明を取得
     * @return アプリケーション説明
     */
    public String getAppDescription() {
        return "NGINXログセキュリティ分析ダッシュボード";
    }

    /**
     * HTMLテンプレートを取得
     * @param templateName テンプレート名
     * @return HTMLテンプレート
     */
    public String getTemplate(String templateName) {
        // "dashboard"と"error"以外は必ずgetDefaultTemplate()のみ返す（default分岐重複防止）
        return switch (templateName) {
            case "dashboard" -> getDashboardTemplate();
            case "error" -> getErrorTemplate();
            default -> getDefaultTemplate();
        };
    }

    /**
     * 静的リソースを取得
     * @param resourceName リソース名
     * @return リソース内容（見つからなければ null を返す）
     */
    public String getStaticResource(String resourceName) {
        // まずクラスパスの /static/ 配下を探す
        final String path = "/static/" + resourceName;
        try (InputStream is = WebConfig.class.getResourceAsStream(path)) {
            if (is == null) return null;
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 読み込み失敗はリソースなし扱い
            return null;
        }
    }

    /**
     * ダッシュボードHTMLテンプレートを取得
     * @return ダッシュボードHTMLテンプレート
     */
    private String getDashboardTemplate() {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>{{APP_TITLE}} - {{APP_DESCRIPTION}}</title>
                <link rel="stylesheet" href="/static/style.css">
                <!-- CSSは外部リソース /static/style.css に収め、CSPのstyle-src 'self' に従う -->
                 {{SECURITY_HEADERS}}
             </head>
             <body>
                 <div class="dashboard-container">
                     <div class="app-body">
                        <aside class="sidebar left-fixed" aria-label="メインメニュー">
                            <div class="sidebar-brand">
                                <strong>{{APP_TITLE}}</strong>
                            </div>

                            <nav class="sidebar-nav">
                                {{MENU_HTML}}
                            </nav>

                            <div class="sidebar-footer">
                                <!-- ユーザー情報は下部に配置（時刻・ログアウトの上） -->
                                <div class="sidebar-user-footer" id="sidebar-user" data-current-user="{{CURRENT_USER}}">
                                    <div class="user-avatar" id="sidebar-user-button" role="button" aria-haspopup="true" aria-expanded="false">
                                        <div class="avatar-circle">{{CURRENT_USER_INITIAL}}</div>
                                        <div class="user-name">{{CURRENT_USER}}</div>
                                    </div>
                                    <!-- ミニメニュー（非表示） -->
                                    <div class="sidebar-mini-menu" id="sidebar-mini-menu" style="display:none; position:absolute; left:1rem; bottom:3.5rem; z-index:1500;">
                                        <div class="mini-menu-card">
                                            <button type="button" class="mini-menu-item" id="mini-change-password">パスワードの変更</button>
                                            <button type="button" class="mini-menu-item" id="mini-profile">プロフィール</button>
                                        </div>
                                    </div>
                                </div>
                                <!-- 時計とログアウトは別行で表示 -->
                                <div class="sidebar-footer-extras" style="margin-top:.5rem; display:flex; flex-direction:column; gap:.25rem;">
                                    <div style="text-align:left;"><small class="current-time">{{CURRENT_TIME}}</small></div>
                                    <div><button id="logout-btn" class="sidebar-logout-btn logout-btn">ログアウト</button></div>
                                </div>
                             </div>
                         </aside>

                        <main id="main-content" data-view="{{CURRENT_VIEW}}" class="right-content" role="main">
                            {{DASHBOARD_CONTENT}}
                        </main>
                     </div>

                     <footer class="dashboard-footer">
                         <small>© Edamame Security Analyzer</small>
                     </footer>
                 </div>

                 <!-- プロフィール編集モーダル（自己用） -->
                 <div id="profile-modal" class="modal" aria-hidden="true" role="dialog" style="display:none;">
                   <div class="modal-backdrop"></div>
                   <div class="modal-content" role="document">
                     <header class="modal-header"><h3>プロフィール編集</h3></header>
                     <main class="modal-body">
                       <form id="profile-form">
                         <!-- 名前は固定（読み取り専用） -->
                         <div class="form-row"><label>名前</label><input type="text" id="profile-name" readonly /></div>
                         <!-- メール（縦レイアウト）：ラベルは上、入力と編集ボタンは同一行に横並び -->
                         <div class="form-row">
                           <label>メール</label>
                           <div style="display:flex;gap:.5rem;align-items:center;">
                             <input type="email" id="profile-email" readonly style="flex:1;min-width:0;padding:.45rem .5rem;border:1px solid #d7dbe2;border-radius:6px;" />
                             <button type="button" id="profile-edit-btn" style="flex:0 0 auto;white-space:nowrap;">編集</button>
                           </div>
                         </div>
                         <!-- ログイン記録の見出し（ログがある場合に表示） -->
                         <div id="profile-logins-header" style="margin-top:.5rem;font-size:0.9em;color:#444;display:none;">ログイン記録（最新20件）</div>
                         <!-- ログイン記録を表で表示（JS側で行を生成） -->
                         <div id="profile-logins" style="margin-top:.25rem;max-height:220px;overflow:auto;font-size:0.9em;display:none;"></div>
                       </form>
                       <div id="profile-error" style="color:#b00020; display:none; margin-top:.5rem;"></div>
                     </main>
                     <footer class="modal-footer">
                       <!-- 下部の保存ボタンは廃止。編集ボタンで保存するフローを使用 -->
                       <button id="profile-close" type="button">閉じる</button>
                     </footer>
                   </div>
                 </div>

                <!-- パスワード変更モーダル（自己用） -->
                <div id="password-modal" class="modal" aria-hidden="true" role="dialog" style="display:none;">
                  <div class="modal-backdrop"></div>
                  <div class="modal-content" role="document">
                    <header class="modal-header"><h3>パスワードの変更</h3></header>
                    <main class="modal-body">
                      <form id="password-form">
                        <div class="form-row"><label for="new-password">新しいパスワード</label><input type="password" id="new-password" /></div>
                        <div class="form-row"><label for="confirm-password">確認</label><input type="password" id="confirm-password" /></div>
                        <!-- マスク解除チェック -->
                        <div class="form-row" style="margin-top:.5rem;"><label><input type="checkbox" id="password-unmask" /> パスワードを表示</label></div>
                        <!-- 固定ポリシー表示（編集不可） -->
                        <div class="form-row" style="font-size:0.9em;color:#666;margin-top:.5rem;">
                          パスワードは以下の条件を満たす必要があります: 最低8文字, 英字（A-Zまたはa-z）1文字以上, 数字（0-9）1文字以上, 許可記号のうち1文字以上を含む。<br>
                          許可記号: !@#$%&*()-_
                        </div>
                        <div id="password-error" style="color:#b00020; display:none; margin-top:.5rem;"></div>
                      </form>
                    </main>
                    <footer class="modal-footer">
                      <button id="password-save" type="button">変更</button>
                      <button id="password-close" type="button">閉じる</button>
                    </footer>
                  </div>
                </div>

                 {{AUTO_REFRESH_SCRIPT}}
                 <script src="/static/script.js"></script>
             </body>
            </html>
            """;
    }

    /**
     * エラーページHTMLテンプレートを取得
     * @return エラーページHTMLテンプレート
     */
    private String getErrorTemplate() {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>エラー - {{APP_TITLE}}</title>
                <style>
                    body {
                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        height: 100vh;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        margin: 0;
                    }
                    .error-container {
                        background: white;
                        padding: 2rem;
                        border-radius: 10px;
                        box-shadow: 0 10px 25px rgba(0,0,0,0.1);
                        text-align: center;
                        max-width: 500px;
                    }
                    .error-code {
                        font-size: 3rem;
                        font-weight: bold;
                        color: #667eea;
                        margin-bottom: 1rem;
                    }
                    .error-message {
                        font-size: 1.2rem;
                        color: #666;
                        margin-bottom: 2rem;
                    }
                    .back-link {
                        display: inline-block;
                        padding: 0.75rem 1.5rem;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                        text-decoration: none;
                        border-radius: 5px;
                        transition: transform 0.2s;
                    }
                    .back-link:hover {
                        transform: translateY(-2px);
                    }
                </style>
            </head>
            <body>
                <div class="error-container">
                    <div class="error-code">{{ERROR_CODE}}</div>
                    <div class="error-message">{{ERROR_MESSAGE}}</div>
                    <a href="/main" class="back-link">ダッシュボードに戻る</a>
                </div>
            </body>
            </html>
            """;
    }

    /**
     * デフォルトHTMLテンプレートを取得
     * @return デフォルトHTMLテンプレート
     */
    private String getDefaultTemplate() {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>{{APP_TITLE}}</title>
            </head>
            <body>
                <h1>{{APP_TITLE}}</h1>
                <p>{{CONTENT}}</p>
            </body>
            </html>
            """;
    }

    /**
     * CSSリソースを取得
     * @return CSS内容
     */
    // Jacksonやテンプレート生成等で将来利用される可能性があるため
    // 現状は静的ファイル(/static/style.css)を直接配信しており、この補助メソッドは未使用だが削除しない。
    @SuppressWarnings("unused")
    private static String getCssResource() {
        // 静的ファイル（/static/style.css）を利用するため、ここでは空文字を返す
        return "";
    }

    /**
     * JavaScriptリソースを取得
     * @return JavaScript内容
     */
    // 補助メソッド: 静的スクリプト配信方針のため現在未使用。将来的なテンプレート内埋め込み等で使用予定。
    @SuppressWarnings("unused")
    private static String getJsResource() {
        // 静的ファイルを /static/script.js として配信するため、ここでは空文字を返す（サーバは静的リソースを直接提供する）
        return "";
    }

    /**
     * サイドバーメニューのHTMLを取得（管理者向けリンクを含む）
     * @return メニューHTML
     */
    // 現状はコントローラ側でアクセス制御を行うためこのメソッドはテンプレート用の補助として残す。
    @SuppressWarnings("unused")
    public static String getMenuHtml() {
         // ここでは表示用の静的HTMLを返す（アクセス制御はコントローラ側で行うこと）
         return """
             <ul>
                 <li><a class="nav-link" href="/main?view=dashboard">ダッシュボード</a></li>
                 <li><a class="nav-link" href="/main?view=test">サンプル</a></li>
                 <!-- ユーザー管理は管理者のみ閲覧可能。サーバー側でロールチェックしてください -->
                 <li><a class="nav-link" href="/main?view=users">ユーザー管理</a></li>
             </ul>
             """;
     }

}
