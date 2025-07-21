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
     * ダッシュボードHTMLテンプレートを作成（左側メニューフレーム対応）
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
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #f5f5f5; }
                    
                    /* フレームレイアウト */
                    .layout-container { 
                        display: flex; 
                        min-height: 100vh; 
                    }
                    
                    /* 左側メニューフレーム */
                    .sidebar {
                        width: 250px;
                        background: linear-gradient(180deg, #2c3e50 0%, #34495e 100%);
                        color: white;
                        position: fixed;
                        height: 100vh;
                        overflow-y: auto;
                        box-shadow: 2px 0 10px rgba(0,0,0,0.1);
                        display: flex;
                        flex-direction: column;
                    }
                    
                    .sidebar-header {
                        padding: 20px;
                        border-bottom: 1px solid rgba(255,255,255,0.1);
                        text-align: center;
                    }
                    
                    .sidebar-logo {
                        font-size: 1.2em;
                        font-weight: bold;
                        margin-bottom: 10px;
                    }
                    
                    .sidebar-status {
                        font-size: 0.9em;
                        opacity: 0.8;
                    }
                    
                    .sidebar-menu {
                        padding: 20px 0;
                        flex: 1;
                    }
                    
                    .menu-item {
                        display: block;
                        padding: 12px 20px;
                        color: white;
                        text-decoration: none;
                        transition: background 0.3s;
                        border-left: 3px solid transparent;
                    }
                    
                    .menu-item:hover, .menu-item.active {
                        background: rgba(255,255,255,0.1);
                        border-left-color: #3498db;
                    }
                    
                    .menu-icon {
                        margin-right: 10px;
                        width: 20px;
                        display: inline-block;
                    }
                    
                    /* ユーザー情報セクション */
                    .user-section {
                        margin-top: auto;
                        padding: 20px;
                        border-top: 1px solid rgba(255,255,255,0.1);
                        background: rgba(0,0,0,0.2);
                    }
                    
                    .user-info {
                        display: flex;
                        align-items: center;
                        margin-bottom: 15px;
                        padding: 10px;
                        background: rgba(255,255,255,0.05);
                        border-radius: 6px;
                    }
                    
                    .user-avatar {
                        width: 40px;
                        height: 40px;
                        background: linear-gradient(135deg, #3498db, #2980b9);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        margin-right: 10px;
                        font-weight: bold;
                        font-size: 1.2em;
                    }
                    
                    .user-details {
                        flex: 1;
                    }
                    
                    .user-name {
                        font-weight: bold;
                        font-size: 0.95em;
                        margin-bottom: 2px;
                    }
                    
                    .user-role {
                        font-size: 0.8em;
                        opacity: 0.7;
                    }
                    
                    .logout-btn {
                        width: 100%;
                        padding: 10px 15px;
                        background: linear-gradient(135deg, #e74c3c, #c0392b);
                        color: white;
                        border: none;
                        border-radius: 6px;
                        font-size: 0.9em;
                        font-weight: bold;
                        cursor: pointer;
                        transition: all 0.3s;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    }
                    
                    .logout-btn:hover {
                        background: linear-gradient(135deg, #c0392b, #a93226);
                        transform: translateY(-1px);
                        box-shadow: 0 4px 10px rgba(231, 76, 60, 0.3);
                    }
                    
                    .logout-icon {
                        margin-right: 8px;
                    }
                    
                    /* メインコンテンツエリア */
                    .main-content {
                        margin-left: 250px;
                        flex: 1;
                        padding: 20px;
                    }
                    
                    /* ヘッダー */
                    .header {
                        background: white;
                        padding: 15px 20px;
                        border-radius: 8px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                        margin-bottom: 20px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    
                    .header-title {
                        font-size: 1.5em;
                        color: #2c3e50;
                    }
                    
                    .header-info {
                        font-size: 0.9em;
                        color: #7f8c8d;
                    }
                    
                    /* サーバー統計グリッド */
                    .server-stats-section {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                        margin-bottom: 20px;
                        overflow: hidden;
                    }
                    
                    .section-header {
                        background: linear-gradient(135deg, #3498db, #2980b9);
                        color: white;
                        padding: 15px 20px;
                        font-size: 1.2em;
                        font-weight: bold;
                    }
                    
                    .server-stats-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
                        gap: 1px;
                        background: #ecf0f1;
                    }
                    
                    .server-stat-item {
                        background: white;
                        padding: 20px;
                        transition: transform 0.2s;
                    }
                    
                    .server-stat-item:hover {
                        transform: translateY(-2px);
                        box-shadow: 0 4px 15px rgba(0,0,0,0.1);
                    }
                    
                    .server-stat-header {
                        font-size: 1.1em;
                        font-weight: bold;
                        color: #2c3e50;
                        margin-bottom: 10px;
                        padding-bottom: 8px;
                        border-bottom: 2px solid #3498db;
                    }
                    
                    .server-stat-content {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 15px;
                        text-align: center;
                    }
                    
                    .stat-value-item {
                        background: #f8f9fa;
                        padding: 10px;
                        border-radius: 6px;
                        border: 1px solid #e9ecef;
                    }
                    
                    .stat-number {
                        font-size: 1.5em;
                        font-weight: bold;
                        color: #2c3e50;
                        display: block;
                    }
                    
                    .stat-label {
                        font-size: 0.8em;
                        color: #7f8c8d;
                        margin-top: 5px;
                    }
                    
                    .stat-access { border-left: 4px solid #27ae60; }
                    .stat-attack { border-left: 4px solid #e74c3c; }
                    .stat-block { border-left: 4px solid #f39c12; }
                    
                    /* その他のセクション */
                    .content-section {
                        background: white;
                        border-radius: 8px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                        margin-bottom: 20px;
                        overflow: hidden;
                    }
                    
                    .section-content {
                        padding: 20px;
                    }
                    
                    /* アラート一�� */
                    .alert-item {
                        border: 1px solid #e9ecef;
                        border-radius: 6px;
                        padding: 15px;
                        margin-bottom: 10px;
                        transition: all 0.2s;
                    }
                    
                    .alert-item:hover {
                        box-shadow: 0 4px 15px rgba(0,0,0,0.1);
                    }
                    
                    .alert-item.high {
                        border-left: 4px solid #e74c3c;
                        background: #fdf2f2;
                    }
                    
                    .alert-item.medium {
                        border-left: 4px solid #f39c12;
                        background: #fefaf2;
                    }
                    
                    .alert-item.low {
                        border-left: 4px solid #3498db;
                        background: #f2f8fd;
                    }
                    
                    .alert-header {
                        display: flex;
                        justify-content: space-between;
                        margin-bottom: 8px;
                        font-size: 0.9em;
                        color: #7f8c8d;
                    }
                    
                    .alert-content {
                        color: #2c3e50;
                    }
                    
                    /* モーダルダイアログスタイル */
                    .modal-overlay {
                        position: fixed;
                        top: 0;
                        left: 0;
                        width: 100%;
                        height: 100%;
                        background: rgba(0, 0, 0, 0.5);
                        display: none;
                        align-items: center;
                        justify-content: center;
                        z-index: 1000;
                    }
                    
                    .modal-content {
                        background: white;
                        padding: 30px;
                        border-radius: 10px;
                        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.3);
                        text-align: center;
                        max-width: 400px;
                        width: 90%;
                        animation: modalSlideIn 0.3s ease-out;
                    }
                    
                    @keyframes modalSlideIn {
                        from {
                            opacity: 0;
                            transform: translateY(-50px);
                        }
                        to {
                            opacity: 1;
                            transform: translateY(0);
                        }
                    }
                    
                    .modal-title {
                        font-size: 1.3em;
                        font-weight: bold;
                        color: #2c3e50;
                        margin-bottom: 15px;
                    }
                    
                    .modal-message {
                        color: #7f8c8d;
                        margin-bottom: 25px;
                        line-height: 1.5;
                    }
                    
                    .modal-buttons {
                        display: flex;
                        gap: 10px;
                        justify-content: center;
                    }
                    
                    .modal-btn {
                        padding: 10px 20px;
                        border: none;
                        border-radius: 6px;
                        font-weight: bold;
                        cursor: pointer;
                        transition: all 0.3s;
                        min-width: 100px;
                    }
                    
                    .modal-btn-confirm {
                        background: linear-gradient(135deg, #e74c3c, #c0392b);
                        color: white;
                    }
                    
                    .modal-btn-confirm:hover {
                        background: linear-gradient(135deg, #c0392b, #a93226);
                        transform: translateY(-1px);
                    }
                    
                    .modal-btn-cancel {
                        background: #95a5a6;
                        color: white;
                    }
                    
                    .modal-btn-cancel:hover {
                        background: #7f8c8d;
                        transform: translateY(-1px);
                    }
                    
                    /* レスポンシブ対応 */
                    @media (max-width: 768px) {
                        .sidebar {
                            transform: translateX(-100%);
                            transition: transform 0.3s;
                        }
                        
                        .sidebar.open {
                            transform: translateX(0);
                        }
                        
                        .main-content {
                            margin-left: 0;
                        }
                        
                        .server-stats-grid {
                            grid-template-columns: 1fr;
                        }
                        
                        .server-stat-content {
                            grid-template-columns: 1fr;
                        }
                    }
                    
                    /* 自動更新インジケーター */
                    .auto-refresh-indicator {
                        position: fixed;
                        top: 20px;
                        right: 20px;
                        background: #27ae60;
                        color: white;
                        padding: 8px 12px;
                        border-radius: 4px;
                        font-size: 0.8em;
                        z-index: 1000;
                    }
                </style>
            </head>
            <body>
                <div class="layout-container">
                    <!-- 左側メニューフレーム -->
                    <div class="sidebar">
                        <div class="sidebar-header">
                            <div class="sidebar-logo">🌱 Edamame</div>
                            <div class="sidebar-status">Security Dashboard</div>
                        </div>
                        
                        <div class="sidebar-menu">
                            <a href="/dashboard" class="menu-item active">
                                <span class="menu-icon">📊</span>ダッシュボード
                            </a>
                            <a href="/api/servers" class="menu-item">
                                <span class="menu-icon">🖥️</span>サーバー管理
                            </a>
                            <a href="/api/alerts" class="menu-item">
                                <span class="menu-icon">🚨</span>アラート
                            </a>
                            <a href="/api/reports" class="menu-item">
                                <span class="menu-icon">📈</span>レポート
                            </a>
                            <a href="/api/settings" class="menu-item">
                                <span class="menu-icon">⚙️</span>設定
                            </a>
                        </div>
                        
                        <!-- ユーザー情報とログアウトボタン -->
                        <div class="user-section">
                            <div class="user-info">
                                <div class="user-avatar">{{CURRENT_USER_INITIAL}}</div>
                                <div class="user-details">
                                    <div class="user-name">{{CURRENT_USER}}</div>
                                    <div class="user-role">管理者</div>
                                </div>
                            </div>
                            <button class="logout-btn" onclick="confirmLogout()">
                                <span class="logout-icon">🚪</span>ログアウト
                            </button>
                        </div>
                    </div>
                    
                    <!-- メインコンテンツエリア -->
                    <div class="main-content">
                        <!-- ヘッダー -->
                        <div class="header">
                            <div>
                                <div class="header-title">{{APP_TITLE}}</div>
                                <div class="header-info">{{APP_DESCRIPTION}} | 最終更新: {{CURRENT_TIME}}</div>
                            </div>
                            <div class="header-info">サーバー状態: {{SERVER_STATUS}}</div>
                        </div>
                        
                        <!-- サーバー統計セクション -->
                        <div class="server-stats-section">
                            <div class="section-header">📊 サーバー統計</div>
                            <div class="server-stats-grid">
                                {{SERVER_STATS}}
                            </div>
                        </div>
                        
                        <!-- 他のセクション -->
                        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 20px;">
                            <!-- 最新アラート -->
                            <div class="content-section">
                                <div class="section-header">🚨 最新アラート</div>
                                <div class="section-content">
                                    {{RECENT_ALERTS}}
                                </div>
                            </div>
                            
                            <!-- サーバー一覧 -->
                            <div class="content-section">
                                <div class="section-header">🖥️ サーバー一覧</div>
                                <div class="section-content">
                                    {{SERVER_LIST}}
                                </div>
                            </div>
                        </div>
                        
                        <!-- 攻撃タイプ統計 -->
                        <div class="content-section">
                            <div class="section-header">🎯 攻撃タイプ統計（今日）</div>
                            <div class="section-content">
                                {{ATTACK_TYPES}}
                            </div>
                        </div>
                    </div>
                </div>
                
                <!-- ログアウト確認モーダル -->
                <div id="logoutModal" class="modal-overlay">
                    <div class="modal-content">
                        <div class="modal-title">ログアウト確認</div>
                        <div class="modal-message">
                            本当にログアウトしますか？<br>
                            未保存の変更がある場合は失われます。
                        </div>
                        <div class="modal-buttons">
                            <button class="modal-btn modal-btn-confirm" onclick="executeLogout()">
                                ログアウト
                            </button>
                            <button class="modal-btn modal-btn-cancel" onclick="cancelLogout()">
                                キャンセル
                            </button>
                        </div>
                    </div>
                </div>
                
                <script>
                    // ログアウト確認ダイアログ
                    function confirmLogout() {
                        const modal = document.getElementById('logoutModal');
                        modal.style.display = 'flex';
                        document.body.style.overflow = 'hidden';
                    }
                    
                    function cancelLogout() {
                        const modal = document.getElementById('logoutModal');
                        modal.style.display = 'none';
                        document.body.style.overflow = 'auto';
                    }
                    
                    function executeLogout() {
                        // ログアウト処理を実行
                        const form = document.createElement('form');
                        form.method = 'POST';
                        form.action = '/logout';
                        document.body.appendChild(form);
                        form.submit();
                    }
                    
                    // モーダル外クリックで閉じる
                    document.getElementById('logoutModal').addEventListener('click', function(e) {
                        if (e.target === this) {
                            cancelLogout();
                        }
                    });
                    
                    // ESCキーでモーダルを閉じる
                    document.addEventListener('keydown', function(e) {
                        if (e.key === 'Escape') {
                            cancelLogout();
                        }
                    });
                    
                    {{#AUTO_REFRESH}}
                    // 自動更新
                    setInterval(function() {
                        location.reload();
                    }, {{REFRESH_INTERVAL}} * 1000);
                    {{/AUTO_REFRESH}}
                </script>
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
