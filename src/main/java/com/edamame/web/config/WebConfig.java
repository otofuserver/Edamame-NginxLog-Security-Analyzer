package com.edamame.web.config;

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
     * @return リソース内容
     */
    public String getStaticResource(String resourceName) {
        return switch (resourceName) {
            case "style.css" -> getCssResource();
            case "script.js" -> getJsResource();
            case "favicon.ico" -> ""; // 空の場合は404を返す
            default -> null; // 見つからない場合
        };
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
        return """
            /* Edamame Security Analyzer - Dashboard Styles */
            
            * {
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }
            
            body {
                font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                background: #f5f7fa;
                color: #333;
                line-height: 1.6;
            }
            
            .dashboard-container {
                min-height: 100vh;
                display: flex;
                flex-direction: column;
            }
            
            .dashboard-header {
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: white;
                padding: 1rem 2rem;
                display: flex;
                justify-content: space-between;
                align-items: center;
                box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            }
            
            .dashboard-header h1 {
                font-size: 1.8rem;
                font-weight: 600;
            }
            
            .header-actions {
                display: flex;
                align-items: center;
                gap: 1rem;
            }
            
            .user-info {
                font-size: 0.9rem;
                opacity: 0.9;
            }
            
            .logout-btn {
                background: rgba(255,255,255,0.2);
                color: white;
                border: 1px solid rgba(255,255,255,0.3);
                padding: 0.5rem 1rem;
                border-radius: 5px;
                cursor: pointer;
                transition: background 0.3s;
            }
            
            .logout-btn:hover {
                background: rgba(255,255,255,0.3);
            }

            /* 新レイアウト: サイドバー + メイン */
            .app-body {
                display: flex;
                align-items: stretch;
                gap: 1rem;
                padding: 1.5rem;
            }

            .sidebar {
                width: 240px;
                flex: 0 0 240px;
                background: #ffffff;
                border-radius: 8px;
                box-shadow: 0 2px 8px rgba(0,0,0,0.06);
                padding: 1rem;
                height: calc(100vh - 120px);
                overflow: auto;
                display: flex;
                flex-direction: column;
             }

            .sidebar-brand {
                font-size: 1.05rem;
                margin-bottom: 0.75rem;
                display: block;
            }

            .sidebar-nav ul {
                list-style: none;
            }

            .sidebar-nav li {
                margin-bottom: 0.5rem;
            }

            .sidebar-nav a.nav-link {
                display: block;
                padding: 0.5rem 0.75rem;
                color: #333;
                text-decoration: none;
                border-radius: 6px;
                transition: background 0.15s;
            }

            .sidebar-nav a.nav-link:hover, .sidebar-nav a.nav-link.active {
                 background: linear-gradient(90deg, #eef2ff, #f5f7ff);
                 color: #222;
             }

            /* サイドバー内のユーザー情報 */
            .sidebar-user {
                margin: 0.75rem 0 0.5rem 0;
            }

            .user-avatar {
                display: inline-flex;
                align-items: center;
                gap: 0.5rem;
            }

            .avatar-circle {
                width: 36px;
                height: 36px;
                border-radius: 50%;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                display: flex;
                align-items: center;
                justify-content: center;
                color: white;
                font-weight: 600;
                font-size: 0.95rem;
            }

            .user-name {
                font-weight: 600;
                font-size: 0.95rem;
            }
            
             .sidebar-footer{
                 margin-top: 1rem;
                 font-size: 0.85rem;
                 color: #666;
                 margin-top: auto; /* フッターを下に固定 */
                 display: flex;
                 flex-direction: column;
                 gap: 0.5rem;
                 align-items: flex-start;
             }

            /* サイドバー用のログアウトボタン */
            .sidebar-logout-btn.logout-btn {
                background: rgba(0,0,0,0.05);
                color: #333;
                border: 1px solid rgba(0,0,0,0.08);
                padding: 0.4rem 0.8rem;
            }

            #main-content.right-content {
                flex: 1 1 auto;
                padding: 1rem;
                min-height: calc(100vh - 120px);
            }

            /* 既存のカード等のスタイルを残す */
            .card {
                background: white;
                border-radius: 10px;
                padding: 1.5rem;
                box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                transition: transform 0.2s;
            }
            
            .card:hover {
                transform: translateY(-2px);
            }

            /* ステータスインジケータ */
            .status-indicator {
                display: inline-block;
                width: 10px;
                height: 10px;
                border-radius: 50%;
                margin-right: 0.5rem;
            }

            .status-indicator.online {
                background: #27ae60;
            }

            .status-indicator.offline {
                background: #e74c3c;
            }

            .status-indicator.warning {
                background: #f39c12;
            }

            /* レスポンシブ */
            @media (max-width: 900px) {
                .app-body { flex-direction: column; padding: 1rem; }
                .sidebar { width: 100%; flex: none; height: auto; }
                #main-content.right-content { min-height: auto; }
            }
            """;
    }

    /**
     * JavaScriptリソースを取得
     * @return JavaScript内容
     */
    private String getJsResource() {
        return """
            // Edamame Security Analyzer - Dashboard Scripts
            
            // ログアウト確認ダイアログ
            function confirmLogout() {
                if (confirm('ログアウトしますか？')) {
                    // ログアウト処理を実行
                    fetch('/logout', {
                        method: 'POST',
                        credentials: 'same-origin'
                    }).then(response => {
                        if (response.ok || response.redirected) {
                            window.location.href = '/login?logout=success';
                        } else {
                            alert('ログアウトに失敗しました。再試行してください。');
                        }
                    }).catch(error => {
                        console.error('ログアウトエラー:', error);
                        // ネットワークエラーの場合も強制リダイレクト
                        window.location.href = '/logout';
                    });
                }
            }
            
            // フラグメント単位の自動更新管理
            // key: fragment element (DOM), value: interval id
            const fragmentAutoRefreshMap = new Map();
            
            function startFragmentAutoRefresh(fragmentEl, intervalSeconds, view) {
                stopFragmentAutoRefresh(fragmentEl);
                if (!fragmentEl || !intervalSeconds || intervalSeconds <= 0) return;
                const id = setInterval(() => {
                    // 再フェッチして該当 view を更新（push= false: history を操作しない）
                    navigateTo(view, false);
                }, intervalSeconds * 1000);
                fragmentAutoRefreshMap.set(fragmentEl, id);
            }
            
            function stopFragmentAutoRefresh(fragmentEl) {
                const id = fragmentAutoRefreshMap.get(fragmentEl);
                if (id) {
                    clearInterval(id);
                    fragmentAutoRefreshMap.delete(fragmentEl);
                }
            }
            
            // 自動更新機能
            let autoRefreshInterval;
            
            function startAutoRefresh(intervalSeconds) {
                if (intervalSeconds > 0) {
                    autoRefreshInterval = setInterval(() => {
                        location.reload();
                    }, intervalSeconds * 1000);
                }
            }
            
            function stopAutoRefresh() {
                if (autoRefreshInterval) {
                    clearInterval(autoRefreshInterval);
                }
            }
            
            // シンプルなAJAXナビゲーション（fetch + pushState）
            function navigateTo(view, push) {
                const main = document.getElementById('main-content');
                if (!main) return;

                // 簡易ルーティング: view -> APIパス
                // view 名は 'dashboard' / 'template' で統一する（URLは /main?view=dashboard）
                const routeMap = {
                    'dashboard': '/api/fragment/dashboard',
                    'template': '/api/fragment/test'
                };

                let apiPath = routeMap[view] || ('/api/fragment/' + encodeURIComponent(view));
                // 相対パスが混入している場合を防ぐ: 必ず先頭にスラッシュを付与して絶対パスとする
                if (!apiPath.startsWith('/')) {
                    apiPath = '/' + apiPath;
                }

                 // ローディング表示
                 main.innerHTML = '<div class="card"><p>読み込み中...</p></div>';

                 fetch(apiPath, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'text/html, application/json' } })
                    .then(response => {
                        if (response.status === 401) {
                            // 未認証の場合はログインページへリダイレクト
                            window.location.href = '/login';
                            throw new Error('Unauthorized');
                        }
                        if (!response.ok) throw new Error('Network response was not ok');
                        const contentType = response.headers.get('content-type') || '';
                        if (contentType.includes('text/html')) {
                            return response.text().then(html => ({ html }));
                        }
                        return response.json().then(json => ({ json }));
                    })
                     .then(result => {
                          if (result.html) {
                              main.innerHTML = result.html;
                            // フラグメントに data-auto-refresh 属性があればそれに従う
                            const fragRoot = main.querySelector('.fragment-root');
                            // 先に既存のフラグメント自動更新を全て停止してから新しいものを開始する
                            stopAllFragmentAutoRefresh();
                            if (fragRoot) {
                                const auto = parseInt(fragRoot.getAttribute('data-auto-refresh') || '0', 10);
                                // fragmentEl を fragRoot として自動更新を開始/停止
                                startFragmentAutoRefresh(fragRoot, auto, view);
                            }
                          } else if (result.json) {
                              main.innerHTML = renderViewContent(view, result.json);
                            // JSONレスポンスの場合は自動更新は基本的に無効（必要ならviewの仕様で制御）
+                           // JSONで表示するビューでも既存のフラグメント自動更新を停止しておく
+                           stopAllFragmentAutoRefresh();
                          } else {
                              main.innerHTML = '<div class="card"><p>不明なレスポンス形式です</p></div>';
                          }

                          if (push) {
                              // pushState の URL は /main?view=dashboard のようにする
                              const url = '/main?view=' + encodeURIComponent(view);
                              history.pushState({ view: view }, '', url);
                          }
                          updateActiveNav(view);
                      })
                     .catch(err => {
                         console.error('データ取得エラー:', err);
                        // 401は既にリダイレクト済みの可能性があるため、それ以外はエラーメッセージを出す
                        if (err.message !== 'Unauthorized') {
                            main.innerHTML = '<div class="card"><p>データの取得に失敗しました。再読み込みしてください。</p></div>';
                        }
                     });
            }

            // サイドバーのリンク活性化表示を更新
            function updateActiveNav(view) {
                document.querySelectorAll('.nav-link').forEach(el => {
                    if (el.dataset.view === view) el.classList.add('active'); else el.classList.remove('active');
                });
            }

            // フラグメント自動更新を全て停止するユーティリティ
            function stopAllFragmentAutoRefresh() {
                // Map の value が interval id, key が fragmentEl
                fragmentAutoRefreshMap.forEach((id, el) => {
                    try { clearInterval(id); } catch (e) { /* ignore */ }
                });
                fragmentAutoRefreshMap.clear();
            }

            // シンプルなビューごとのレンダラ
            function renderViewContent(view, data) {
                if (view === 'servers') {
                    if (!data || !data.serverList) return '<div class="card">サーバーデータがありません</div>';
                    let html = '<div class="card"><h2>サーバー一覧</h2>';
                    data.serverList.forEach(s => {
                        const name = escapeHtml(s.name || 'unknown');
                        const desc = escapeHtml(s.description || '');
                        const last = escapeHtml(s.lastLogReceived || '未記録');
                        const count = s.todayAccessCount || 0;
                        html += '<div class="server-item"><div class="server-info"><strong>' + name + '</strong><br><small>' + desc + '</small><br><small>最終ログ: ' + last + ' | 今日のアクセス: ' + count + '件</small></div></div>';
                    });
                    html += '</div>';
                    return html;
                }

                if (view === 'alerts') {
                    if (!data || !data.recentAlerts) return '<div class="card">アラートはありません</div>';
                    let html = '<div class="card"><h2>最近のアラート</h2>';
                    data.recentAlerts.forEach(a => {
                        const sev = escapeHtml(a.severityLevel || 'low');
                        const time = escapeHtml(a.accessTime || '');
                        const server = escapeHtml(a.serverName || 'unknown');
                        const ip = escapeHtml(a.ipAddress || '');
                        const url = escapeHtml(a.url || '');
                        html += '<div class="alert-item ' + sev + '"><div class="alert-header"><span class="alert-time">' + time + '</span> <span class="alert-server">' + server + '</span></div><div class="alert-content"><strong>' + ip + '</strong> からの攻撃: ' + escapeHtml(a.attackType || '') + '<br><small>URL: ' + (url.length > 100 ? url.substring(0,100) + '...' : url) + '</small></div></div>';
                    });
                    html += '</div>';
                    return html;
                }

                if (view === 'attack-types') {
                    if (!data || !data.attackTypes) return '<div class="card">攻撃タイプのデータがありません</div>';
                    let html = '<div class="card"><h2>攻撃タイプ</h2>';
                    data.attackTypes.forEach(t => {
                        html += '<div class="attack-type-item"><div class="attack-type-info"><strong>' + escapeHtml(t.type) + '</strong><br><small>' + escapeHtml(t.description || '') + '</small></div><span class="attack-count">' + (t.count || 0) + '</span></div>';
                    });
                    html += '</div>';
                    return html;
                }

                if (view === 'settings') {
                    return '<div class="card"><h2>設定</h2><p>設定画面（未実装）</p></div>';
                }

                // デフォルト: overview
                if (!data) return '<div class="card">データなし</div>';
                let html = '<div class="card"><h2>概要</h2>';
                html += '<p>総アクセス: ' + (data.totalAccess || 0) + '</p>';
                html += '<p>攻撃検知: ' + (data.attackCount || 0) + '</p>';
                html += '</div>';
                return html;
            }

            // HTMLエスケープ（シンプル）
            function escapeHtml(s) {
                if (!s) return '';
                return String(s)
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;')
                    .replace(/'/g, '&#39;');
            }

            // 初期化
            document.addEventListener('DOMContentLoaded', function() {
                // 現在時刻の表示更新
                updateCurrentTime();
                setInterval(updateCurrentTime, 1000);

                // 複数のログアウトボタン（ヘッダ・サイドバー）に対応
                document.querySelectorAll('.logout-btn').forEach(b => b.addEventListener('click', confirmLogout));

                // サイドバーリンクのイベント
                document.querySelectorAll('.nav-link').forEach(el => {
                    el.addEventListener('click', function(e) {
                        // プログレッシブエンハンスメント: JS有効時はAJAXで読み替え
                        e.preventDefault();
                        const view = el.dataset.view || 'dashboard';
                        navigateTo(view, true);
                    });
                });

                // popstateハンドラ
                window.addEventListener('popstate', function(e) {
                    const state = e.state;
                    if (state && state.view) {
                        navigateTo(state.view, false);
                    } else {
                        // クエリパラメータを読んで初期表示
                        const params = new URLSearchParams(window.location.search);
                        const v = params.get('view') || 'dashboard';
                        navigateTo(v, false);
                    }
                });

                // 初回表示: URLのviewに合わせる（サーバ側で既に埋め込まれている場合はfetchを避けるが安全のためここはfetchする）
                const params = new URLSearchParams(window.location.search);
                const initialView = params.get('view') || 'dashboard';
                navigateTo(initialView, false);
            });

            function updateCurrentTime() {
                const now = new Date();
                const timeString = now.toLocaleString('ja-JP');
                const timeElements = document.querySelectorAll('.current-time');
                timeElements.forEach(element => {
                    element.textContent = timeString;
                });
            }
            """;
    }

    /**
     * メニューHTMLを取得（1箇所で管理）
     * @return ナビゲーションHTML
     */
    public String getMenuHtml() {
        return """
            <ul>
                <li><a href="/main?view=dashboard" class="nav-link" data-view="dashboard">ダッシュボード</a></li>
                <li><a href="/main?view=template" class="nav-link" data-view="template">テンプレート（テスト）</a></li>
            </ul>
            """;
    }
}
