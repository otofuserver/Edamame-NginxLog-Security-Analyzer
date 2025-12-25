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
     * 自動更新が有効かチェック
     * @return 自動更新が有効な場合true
     */
    public boolean isEnableAutoRefresh() {
        return true; // デフォルトで自動更新を有効
    }

    /**
     * 更新間隔を取得（秒）
     * @return 更新間隔
     */
    public int getRefreshInterval() {
        return 30; // 30秒間隔
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
                                <div class="sidebar-user-footer">
                                    <div class="user-avatar">
                                        <div class="avatar-circle">{{CURRENT_USER_INITIAL}}</div>
                                        <div class="user-name">{{CURRENT_USER}}</div>
                                    </div>
                                </div>
                                <small class="current-time">{{CURRENT_TIME}}</small>
                                <button id="logout-btn" class="sidebar-logout-btn logout-btn">ログアウト</button>
                            </div>
                        </aside>

                        <main id="main-content" class="right-content" role="main">
                            {{DASHBOARD_CONTENT}}
                        </main>
                     </div>

                     <footer class="dashboard-footer">
                         <small>© Edamame Security Analyzer</small>
                     </footer>
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
    private String getCssResource() {
        // 静的ファイル（/static/style.css）を利用するため、ここでは空文字を返す
        return "";
    }

    /**
     * JavaScriptリソースを取得
     * @return JavaScript内容
     */
    private String getJsResource() {
        // 静的ファイルを /static/script.js として配信するため、ここでは空文字を返す（サーバは静的リソースを直接提供する）
        return "";
    }

    /**
     * サイドバーメニューのHTMLを取得（管理者向けリンクを含む）
     * @return メニューHTML
     */
    public String getMenuHtml() {
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
