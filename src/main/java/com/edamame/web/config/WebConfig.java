package com.edamame.web.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Web設定管理クラス
 * Webアプリケーションの設定値とテンプレート管理を担当
 */
public class WebConfig {

    private final BiConsumer<String, String> logFunction;
    private final Map<String, String> templates = new HashMap<>();
    private final Map<String, String> staticResources = new HashMap<>();

    // Web設定値
    private String appTitle = "Edamame Security Dashboard";
    private String appDescription = "NGINXログセキュリティ分析ダッシュボード";
    private int refreshInterval = 30; // 秒
    private boolean enableAutoRefresh = true;

    /**
     * コンストラクタ
     * @param logFunction ログ出力関数
     */
    public WebConfig(BiConsumer<String, String> logFunction) {
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        initializeTemplates();
        initializeStaticResources();
    }

    /**
     * HTMLテンプレートを初期化
     */
    private void initializeTemplates() {
        // ダッシュボードテンプレート
        templates.put("dashboard", createDashboardTemplate());

        // エラーページテンプレート
        templates.put("error", createErrorTemplate());

        logFunction.accept("HTMLテンプレート初期化完了", "DEBUG");
    }

    /**
     * 静的リソースを初期化
     */
    private void initializeStaticResources() {
        // CSS
        staticResources.put("dashboard.css", createDashboardCSS());

        // JavaScript
        staticResources.put("dashboard.js", createDashboardJS());

        logFunction.accept("静的リソース初期化完了", "DEBUG");
    }

    /**
     * ダッシュボードHTMLテンプレートを作成
     * @return HTMLテンプレート文字列
     */
    private String createDashboardTemplate() {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>{{APP_TITLE}}</title>
                <link rel="stylesheet" href="/css/dashboard.css">
                <link rel="icon" type="image/x-icon" href="/favicon.ico">
            </head>
            <body>
                <header class="header">
                    <div class="container">
                        <h1 class="logo">🛡️ {{APP_TITLE}}</h1>
                        <nav class="nav">
                            <span class="status">{{SERVER_STATUS}}</span>
                            <span class="time">{{CURRENT_TIME}}</span>
                        </nav>
                    </div>
                </header>

                <main class="main">
                    <div class="container">
                        <!-- 統計サマリー -->
                        <section class="stats-grid">
                            <div class="stat-card">
                                <h3>総アクセス数</h3>
                                <div class="stat-value">{{TOTAL_ACCESS}}</div>
                            </div>
                            <div class="stat-card alert">
                                <h3>攻撃検知数</h3>
                                <div class="stat-value">{{TOTAL_ATTACKS}}</div>
                            </div>
                            <div class="stat-card">
                                <h3>ModSecブロック</h3>
                                <div class="stat-value">{{MODSEC_BLOCKS}}</div>
                            </div>
                            <div class="stat-card">
                                <h3>監視サーバー数</h3>
                                <div class="stat-value">{{ACTIVE_SERVERS}}</div>
                            </div>
                        </section>

                        <!-- 最新アラート -->
                        <section class="alerts-section">
                            <h2>最新セキュリティアラート</h2>
                            <div class="alerts-container">
                                {{RECENT_ALERTS}}
                            </div>
                        </section>

                        <!-- サーバー状況 -->
                        <section class="servers-section">
                            <h2>サーバー監視状況</h2>
                            <div class="servers-container">
                                {{SERVER_LIST}}
                            </div>
                        </section>

                        <!-- 攻撃タイプ統計 -->
                        <section class="attack-types-section">
                            <h2>攻撃タイプ別統計</h2>
                            <div class="attack-types-container">
                                {{ATTACK_TYPES}}
                            </div>
                        </section>
                    </div>
                </main>

                <footer class="footer">
                    <div class="container">
                        <p>{{APP_DESCRIPTION}} | バージョン: {{APP_VERSION}}</p>
                        {{#AUTO_REFRESH}}
                        <p>自動更新: {{REFRESH_INTERVAL}}秒間隔</p>
                        {{/AUTO_REFRESH}}
                    </div>
                </footer>

                <script src="/js/dashboard.js"></script>
                {{#AUTO_REFRESH}}
                <script>
                    startAutoRefresh({{REFRESH_INTERVAL}} * 1000);
                </script>
                {{/AUTO_REFRESH}}
            </body>
            </html>
            """;
    }

    /**
     * エラーページテンプレートを作成
     * @return HTMLテンプレート文字列
     */
    private String createErrorTemplate() {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>エラー - {{APP_TITLE}}</title>
                <link rel="stylesheet" href="/css/dashboard.css">
            </head>
            <body>
                <div class="container">
                    <div class="error-page">
                        <h1>🚨 エラーが発生しました</h1>
                        <div class="error-details">
                            <h2>{{ERROR_CODE}}</h2>
                            <p>{{ERROR_MESSAGE}}</p>
                        </div>
                        <div class="error-actions">
                            <a href="/dashboard" class="btn">ダッシュボードに戻る</a>
                        </div>
                    </div>
                </div>
            </body>
            </html>
            """;
    }

    /**
     * ダッシュボードCSSを作成
     * @return CSS文字列
     */
    private String createDashboardCSS() {
        return """
            /* Edamame Security Dashboard CSS */
            * {
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }

            body {
                font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: #333;
                min-height: 100vh;
            }

            .container {
                max-width: 1200px;
                margin: 0 auto;
                padding: 0 20px;
            }

            /* Header */
            .header {
                background: rgba(255, 255, 255, 0.95);
                backdrop-filter: blur(10px);
                box-shadow: 0 2px 20px rgba(0, 0, 0, 0.1);
                margin-bottom: 30px;
            }

            .header .container {
                display: flex;
                justify-content: space-between;
                align-items: center;
                padding: 15px 20px;
            }

            .logo {
                font-size: 24px;
                font-weight: bold;
                color: #2c3e50;
            }

            .nav {
                display: flex;
                gap: 20px;
                align-items: center;
            }

            .status {
                padding: 5px 15px;
                background: #27ae60;
                color: white;
                border-radius: 15px;
                font-size: 14px;
            }

            .time {
                font-size: 14px;
                color: #7f8c8d;
            }

            /* Stats Grid */
            .stats-grid {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
                gap: 20px;
                margin-bottom: 40px;
            }

            .stat-card {
                background: white;
                padding: 25px;
                border-radius: 15px;
                box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
                text-align: center;
                transition: transform 0.3s ease;
            }

            .stat-card:hover {
                transform: translateY(-5px);
            }

            .stat-card.alert {
                background: linear-gradient(135deg, #ff6b6b, #ee5a52);
                color: white;
            }

            .stat-card h3 {
                font-size: 16px;
                margin-bottom: 15px;
                opacity: 0.8;
            }

            .stat-value {
                font-size: 36px;
                font-weight: bold;
                line-height: 1;
            }

            /* Sections */
            .alerts-section,
            .servers-section,
            .attack-types-section {
                background: white;
                padding: 30px;
                border-radius: 15px;
                box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
                margin-bottom: 30px;
            }

            .alerts-section h2,
            .servers-section h2,
            .attack-types-section h2 {
                font-size: 22px;
                margin-bottom: 20px;
                color: #2c3e50;
                border-bottom: 2px solid #3498db;
                padding-bottom: 10px;
            }

            /* Alert Cards */
            .alert-item {
                background: #fff5f5;
                border-left: 4px solid #e53e3e;
                padding: 15px;
                margin-bottom: 10px;
                border-radius: 0 8px 8px 0;
            }

            .alert-item.high {
                border-left-color: #e53e3e;
                background: #fff5f5;
            }

            .alert-item.medium {
                border-left-color: #f6ad55;
                background: #fffaf0;
            }

            .alert-item.low {
                border-left-color: #48bb78;
                background: #f0fff4;
            }

            /* Server Cards */
            .server-item {
                display: flex;
                justify-content: space-between;
                align-items: center;
                padding: 15px;
                background: #f8f9fa;
                border-radius: 8px;
                margin-bottom: 10px;
            }

            .server-status {
                padding: 5px 12px;
                border-radius: 12px;
                font-size: 12px;
                font-weight: bold;
            }

            .server-status.online {
                background: #d4edda;
                color: #155724;
            }

            .server-status.offline {
                background: #f8d7da;
                color: #721c24;
            }

            /* Attack Types */
            .attack-type-item {
                display: flex;
                justify-content: space-between;
                align-items: center;
                padding: 10px 0;
                border-bottom: 1px solid #eee;
            }

            .attack-type-item:last-child {
                border-bottom: none;
            }

            .attack-count {
                background: #e3f2fd;
                color: #1976d2;
                padding: 5px 10px;
                border-radius: 15px;
                font-weight: bold;
            }

            /* Footer */
            .footer {
                background: rgba(255, 255, 255, 0.9);
                text-align: center;
                padding: 20px;
                margin-top: 40px;
                color: #7f8c8d;
                font-size: 14px;
            }

            /* Error Page */
            .error-page {
                text-align: center;
                padding: 100px 20px;
                background: white;
                border-radius: 15px;
                margin: 50px auto;
                max-width: 500px;
            }

            .error-details h2 {
                font-size: 48px;
                color: #e53e3e;
                margin-bottom: 20px;
            }

            .btn {
                display: inline-block;
                padding: 12px 25px;
                background: #3498db;
                color: white;
                text-decoration: none;
                border-radius: 8px;
                margin-top: 20px;
                transition: background 0.3s ease;
            }

            .btn:hover {
                background: #2980b9;
            }

            /* レスポンシブ */
            @media (max-width: 768px) {
                .stats-grid {
                    grid-template-columns: 1fr;
                }
                
                .header .container {
                    flex-direction: column;
                    gap: 10px;
                }
                
                .nav {
                    flex-direction: column;
                    gap: 10px;
                }
            }
            """;
    }

    /**
     * ダッシュボードJavaScriptを作成
     * @return JavaScript文字列
     */
    private String createDashboardJS() {
        return """
            // Edamame Security Dashboard JavaScript
            
            // 自動更新機能
            let autoRefreshTimer = null;
            
            function startAutoRefresh(interval) {
                if (autoRefreshTimer) {
                    clearInterval(autoRefreshTimer);
                }
                
                autoRefreshTimer = setInterval(() => {
                    location.reload();
                }, interval);
                
                console.log('自動更新開始: ' + (interval / 1000) + '秒間隔');
            }
            
            function stopAutoRefresh() {
                if (autoRefreshTimer) {
                    clearInterval(autoRefreshTimer);
                    autoRefreshTimer = null;
                    console.log('自動更新停止');
                }
            }
            
            // API呼び出し関数
            async function fetchApiData(endpoint) {
                try {
                    const response = await fetch('/api/' + endpoint);
                    if (!response.ok) {
                        throw new Error('API呼び出しエラー: ' + response.status);
                    }
                    return await response.json();
                } catch (error) {
                    console.error('API呼び出し失敗:', error);
                    return null;
                }
            }
            
            // 統計データを更新（AJAX版）
            async function updateStats() {
                const stats = await fetchApiData('stats');
                if (stats) {
                    updateStatElements(stats);
                }
            }
            
            function updateStatElements(stats) {
                const elements = {
                    totalAccess: document.querySelector('.stat-card:nth-child(1) .stat-value'),
                    totalAttacks: document.querySelector('.stat-card:nth-child(2) .stat-value'),
                    modsecBlocks: document.querySelector('.stat-card:nth-child(3) .stat-value'),
                    activeServers: document.querySelector('.stat-card:nth-child(4) .stat-value')
                };
                
                if (elements.totalAccess) elements.totalAccess.textContent = stats.totalAccess || '0';
                if (elements.totalAttacks) elements.totalAttacks.textContent = stats.totalAttacks || '0';
                if (elements.modsecBlocks) elements.modsecBlocks.textContent = stats.modsecBlocks || '0';
                if (elements.activeServers) elements.activeServers.textContent = stats.activeServers || '0';
            }
            
            // エラーハンドリング
            window.addEventListener('error', (event) => {
                console.error('JavaScript エラー:', event.error);
            });
            
            // ページ読み込み完了時の処理
            document.addEventListener('DOMContentLoaded', () => {
                console.log('Edamame Security Dashboard 初期化完了');
                
                // 現在時刻の更新
                updateCurrentTime();
                setInterval(updateCurrentTime, 1000);
            });
            
            function updateCurrentTime() {
                const timeElement = document.querySelector('.time');
                if (timeElement) {
                    const now = new Date();
                    timeElement.textContent = now.toLocaleString('ja-JP');
                }
            }
            """;
    }

    /**
     * テンプレートを取得
     * @param templateName テンプレート名
     * @return テンプレート文字列
     */
    public String getTemplate(String templateName) {
        return templates.getOrDefault(templateName, "");
    }

    /**
     * 静的リソースを取得
     * @param resourceName リソース名
     * @return リソース文字列
     */
    public String getStaticResource(String resourceName) {
        return staticResources.getOrDefault(resourceName, "");
    }

    // ゲッター・セッター
    public String getAppTitle() { return appTitle; }
    public void setAppTitle(String appTitle) { this.appTitle = appTitle; }

    public String getAppDescription() { return appDescription; }
    public void setAppDescription(String appDescription) { this.appDescription = appDescription; }

    public int getRefreshInterval() { return refreshInterval; }
    public void setRefreshInterval(int refreshInterval) { this.refreshInterval = refreshInterval; }

    public boolean isEnableAutoRefresh() { return enableAutoRefresh; }
    public void setEnableAutoRefresh(boolean enableAutoRefresh) { this.enableAutoRefresh = enableAutoRefresh; }
}
