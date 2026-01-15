(function(){
    'use strict';

    // 軽量ブートストラップ: 分割したモジュールを順にロードして初期化する
    const _loadedScripts = new Set();
    function loadScript(url) {
        return new Promise((resolve, reject) => {
            if (!url) return resolve();
            if (_loadedScripts.has(url)) return resolve();
            const s = document.createElement('script');
            s.src = url;
            s.async = false;
            s.onload = () => { _loadedScripts.add(url); resolve(); };
            s.onerror = (e) => reject(new Error('script load error: ' + url));
            document.head.appendChild(s);
        });
    }

    async function loadScriptsSequential(urls) {
        for (const u of (urls||[])) {
            if (!u) continue;
            try { await loadScript(u); } catch(e) { console.warn('スクリプト読み込みに失敗:', u, e); }
        }
    }

    // グローバルで再利用できるようにエクスポート
    window.loadScript = loadScript;
    window.loadScriptsSequential = loadScriptsSequential;

    // デバッグユーティリティ
    function dbg(...args) { try { if (window && window.console && console.debug) console.debug('[EDAMAME]', ...args); } catch(e) { /* ignore */ } }
    window.dbg = dbg;

    // 以下の機能は分割したファイル(clock.js, fragment_refresh.js, navigation.js)に移動しました。

    // intercept clicks on sidebar links (a.nav-link)
    document.addEventListener('click', function(ev){
        const a = ev.target.closest && ev.target.closest('a.nav-link');
        if (!a) return;
        try {
            const url = new URL(a.href, location.origin);
            // only intercept internal /main links
            if (url.pathname === '/main') {
                ev.preventDefault();
                const view = url.searchParams.get('view') || (url.searchParams.has('q') ? 'users' : 'dashboard');
                if (window.navigateTo) {
                    window.navigateTo(view, true);
                } else {
                    // フォールバック: 直接遷移
                    window.location.href = '/main?view=' + encodeURIComponent(view);
                }
            }
        } catch(e) { /* not a valid URL or cross-origin */ }
    }, false);

    // support back/forward
    window.addEventListener('popstate', function(){
        const params = new URLSearchParams(location.search);
        const view = params.get('view') || (params.has('q') ? 'users' : 'dashboard');
        if (window.navigateTo) window.navigateTo(view, false);
    });

    document.addEventListener('DOMContentLoaded', async function(){
        dbg('DOM ready - starting bootstrap');
        // 依存モジュールを順に読み込む
        await loadScriptsSequential(['/static/sidebar_mini_menu.js','/static/profile_modal.js','/static/password_modal.js','/static/logout.js','/static/mini_menu.js','/static/url_threat.js']);
        dbg('loaded core modules, loadedScripts=', Array.from(_loadedScripts));

        // 初期化
        try {
            if (window.SidebarMini && typeof window.SidebarMini.init === 'function') window.SidebarMini.init();
            if (window.ProfileModal && typeof window.ProfileModal.open === 'function') window.openProfileModal = window.ProfileModal.open.bind(window.ProfileModal);
            if (window.PasswordModal && typeof window.PasswordModal.open === 'function') window.openPasswordModal = window.PasswordModal.open.bind(window.PasswordModal);
        } catch(e) { console.warn('bootstrap init error', e); }

        try { if (window.Logout && typeof window.Logout.init === 'function') window.Logout.init(); } catch(e) { console.warn('Logout.init error', e); }

        // 時計・断片更新・ナビゲーション機能を分割ファイルから読み込む
        await loadScriptsSequential(['/static/clock.js', '/static/fragment_refresh.js', '/static/navigation.js']);

        // startClock がロードされていれば実行
        try { if (window.startClock) window.startClock(); } catch(e) { console.warn('startClock init error', e); }

        // 初期ロードの既存ロジック: サーバレンダや初期View判定は navigation.js に委譲
        try {
            const params = new URLSearchParams(location.search);
            let initialView = params.get('view') || (params.has('q') ? 'users' : 'dashboard');
            const mainEl = document.getElementById('main-content');
            if (mainEl) {
                try {
                    const dv = mainEl.getAttribute('data-view');
                    if (dv) initialView = dv;
                } catch(e) {}
                dbg('initialView determined=', initialView, 'location.search=', location.search, 'main-content data-view=', mainEl.getAttribute('data-view'));

                const noClientNav = mainEl.getAttribute('data-no-client-nav') === 'true';
                if (!noClientNav) {
                    dbg('forcing navigateTo for initialView=', initialView, 'noClientNav=', noClientNav);
                    if (window.navigateTo) await window.navigateTo(initialView, false).catch(()=>{});
                } else {
                    dbg('respecting server-rendered content for initialView=', initialView);
                    // server-rendered の場合でも必要なクライアント初期化を行う
                    try {
                        const hasUserList = !!mainEl.querySelector('#user-list-root, .user-list');
                        if (hasUserList) {
                            await loadScriptsSequential(['/static/user_list.js','/static/user_modal.js']);
                            if (window.UserList && typeof window.UserList.initUserManagement === 'function') {
                                try { window.UserList.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('UserList.initUserManagement error', e); }
                            } else if (typeof window.initUserManagement === 'function') {
                                try { window.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('initUserManagement error', e); }
                            }
                            dbg('initialized user list because hasUserList=true');
                        }
                        const hasServerList = !!mainEl.querySelector('#server-results-body, .server-list');
                        if (hasServerList) {
                            await loadScriptsSequential(['/static/mini_menu.js','/static/server_list.js']);
                            if (window.ServerList && typeof window.ServerList.initServerManagement === 'function') {
                                try { window.ServerList.initServerManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('ServerList.initServerManagement error', e); }
                            }
                            dbg('initialized server list because hasServerList=true');
                        }
                        const hasUrlThreat = initialView === 'url_threat' || !!mainEl.querySelector('#url-threat-table');
                        if (hasUrlThreat) {
                            await loadScriptsSequential(['/static/mini_menu.js','/static/url_threat.js']);
                            if (window.UrlThreat && typeof window.UrlThreat.init === 'function') {
                                try { window.UrlThreat.init(); } catch(e) { console.error('UrlThreat.init error (server-rendered)', e); }
                            }
                            dbg('initialized url threat because hasUrlThreat=true');
                        }

                        // 強制的なビュー別初期化
                        try {
                            if (initialView === 'users') {
                                dbg('forcing user init because initialView=users');
                                await loadScriptsSequential(['/static/user_list.js','/static/user_modal.js']);
                                if (window.UserList && typeof window.UserList.initUserManagement === 'function') {
                                    try { window.UserList.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('UserList.initUserManagement error (force)', e); }
                                } else if (typeof window.initUserManagement === 'function') {
                                    try { window.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('initUserManagement error (force)', e); }
                                }
                            }
                            if (initialView === 'servers') {
                                dbg('forcing server init because initialView=servers');
                                await loadScriptsSequential(['/static/mini_menu.js','/static/server_list.js']);
                                if (window.ServerList && typeof window.ServerList.initServerManagement === 'function') {
                                    try { window.ServerList.initServerManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('ServerList.initServerManagement error (force)', e); }
                                }
                            }
                            if (initialView === 'url_threat') {
                                dbg('forcing url_threat init because initialView=url_threat');
                                await loadScriptsSequential(['/static/mini_menu.js','/static/url_threat.js']);
                                if (window.UrlThreat && typeof window.UrlThreat.init === 'function') {
                                    try { window.UrlThreat.init(); } catch(e) { console.error('UrlThreat.init error (force)', e); }
                                }
                            }
                        } catch(e) {
                            console.warn('server-rendered forced init error', e);
                        }
                    } catch(e) {
                        console.warn('server-rendered init error', e);
                    }
                }
            }
        } catch(e) {
            console.error('initial navigation error', e);
        }
    });
})();
