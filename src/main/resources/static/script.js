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

    // デバッグユーティリティ
    function dbg(...args) { try { if (window && window.console && console.debug) console.debug('[EDAMAME]', ...args); } catch(e) { /* ignore */ } }

    // --- クライアント時計 ---
    function pad2(n) { return String(n).padStart(2, '0'); }
    function formatYMDHMS(d) {
        return d.getFullYear() + '-' + pad2(d.getMonth()+1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }
    function startClock() {
        try {
            if (window.__edamame_clock_started__) return;
            function tick() {
                const now = new Date();
                const s = formatYMDHMS(now);
                const els = document.querySelectorAll('.current-time');
                els.forEach(el => { try { el.textContent = s; } catch(_) {} });
            }
            tick();
            window.__edamame_clock_interval__ = setInterval(tick, 1000);
            window.__edamame_clock_started__ = true;
            dbg('client clock started');
        } catch(e) { console.warn('startClock error', e); }
    }

    // --- navigation helper ---
    async function navigateTo(view, push = true) {
        dbg('navigateTo called, view=', view, 'push=', push);
        try {
            const main = document.getElementById('main-content'); if (!main) return;
            const routeMap = { 'dashboard': '/api/fragment/dashboard', 'template': '/api/fragment/test', 'users': '/api/fragment/users' };
            const apiPath = routeMap[view] || ('/api/fragment/' + encodeURIComponent(view));
            main.innerHTML = '<div class="card"><p>読み込み中...</p></div>';
            const resp = await fetch(apiPath, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'text/html, application/json' } });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) { main.innerHTML = '<div class="card"><p>データ取得エラー</p></div>'; return; }
            const ct = resp.headers.get('content-type') || '';
            if (ct.includes('text/html')) {
                const html = await resp.text();
                main.innerHTML = html;
                // users ビューの場合は依存スクリプトを読み込んで初期化
                if (view === 'users') {
                    await loadScriptsSequential(['/static/user_list.js','/static/user_modal.js']);
                    // try initialize
                    if (window.UserList && typeof window.UserList.initUserManagement === 'function') {
                        try { window.UserList.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('UserList.initUserManagement error', e); }
                    } else if (typeof window.initUserManagement === 'function') {
                        try { window.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('initUserManagement error', e); }
                    }
                }
            } else {
                const j = await resp.json(); main.innerHTML = '<pre>'+JSON.stringify(j,null,2)+'</pre>';
            }
            if (push) {
                const newUrl = '/main?view=' + encodeURIComponent(view);
                history.pushState({}, '', newUrl);
            }
        } catch (e) {
            const main = document.getElementById('main-content'); if (main) main.innerHTML = '<div class="card"><p>データ取得エラー</p></div>';
            console.error('navigateTo error', e);
        }
    }

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
                navigateTo(view, true);
            }
        } catch(e) { /* not a valid URL or cross-origin */ }
    }, false);

    // support back/forward
    window.addEventListener('popstate', function(){
        const params = new URLSearchParams(location.search);
        const view = params.get('view') || (params.has('q') ? 'users' : 'dashboard');
        navigateTo(view, false);
    });

    document.addEventListener('DOMContentLoaded', async function(){
        dbg('DOM ready - starting bootstrap');
        // 依存モジュールを順に読み込む
        await loadScriptsSequential(['/static/sidebar_mini_menu.js','/static/profile_modal.js','/static/password_modal.js','/static/logout.js']);
        dbg('loaded core modules, loadedScripts=', Array.from(_loadedScripts));

        // 初期化
        try {
            if (window.SidebarMini && typeof window.SidebarMini.init === 'function') window.SidebarMini.init();
            // expose old-style globals for compatibility
            if (window.ProfileModal && typeof window.ProfileModal.open === 'function') window.openProfileModal = window.ProfileModal.open.bind(window.ProfileModal);
            if (window.PasswordModal && typeof window.PasswordModal.open === 'function') window.openPasswordModal = window.PasswordModal.open.bind(window.PasswordModal);
        } catch(e) { console.warn('bootstrap init error', e); }

        // Logout モジュールがあれば初期化
        try { if (window.Logout && typeof window.Logout.init === 'function') window.Logout.init(); } catch(e) { console.warn('Logout.init error', e); }

        // クライアント時計を開始（重複起動は startClock 内で防止）
        try { startClock(); } catch(e) { console.warn('startClock init error', e); }

        // 初期ロード対応: サーバから直接 /main?view=... で来た場合や F5 のフルリロード時に
        // クライアント側の navigate が必要かを判定する。サーバが既に main-content を出力している場合は
        // 上書きしない。ただしサーバレンダ時でも data-view 属性があればそれを優先して初期化を行う。
        try {
            const params = new URLSearchParams(location.search);
            let initialView = params.get('view') || (params.has('q') ? 'users' : 'dashboard');
            const mainEl = document.getElementById('main-content');
            if (mainEl) {
                // サーバが main-content に data-view を付与している場合はそれを優先
                try { const dv = mainEl.getAttribute('data-view'); if (dv) initialView = dv; } catch(e) {}
                dbg('initialView determined=', initialView, 'location.search=', location.search, 'main-content data-view=', mainEl.getAttribute('data-view'));

                const content = mainEl.innerHTML || '';
                const isEmpty = content.trim() === '';
                const isPlaceholder = /読み込み中|Loading/.test(content);
                // main が空または読み込み中のプレースホルダならクライアントで取得する
                // 常にクライアント側で断片を取得して表示を統一する（ただし明示的に無効化された場合は除く）
                const noClientNav = mainEl.getAttribute('data-no-client-nav') === 'true';
                if (!noClientNav) {
                    dbg('forcing navigateTo for initialView=', initialView, 'noClientNav=', noClientNav);
                    await navigateTo(initialView, false).catch(()=>{});
                } else {
                    dbg('respecting server-rendered content for initialView=', initialView);
                    // サーバ側で既にコンテンツがレンダリングされているケース
                    // 例えば users 断片がサーバで出力されている場合、クライアント側の振る舞い
                    // （検索やモーダル等）が初期化されていない可能性があるため、
                    // user_list/user_modal の初期化を試みる。
                    try {
                        const hasUserList = !!mainEl.querySelector('#user-list-root, .user-list');
                        if (hasUserList) {
                            // 動的にスクリプトを読み込んで初期化
                            await loadScriptsSequential(['/static/user_list.js','/static/user_modal.js']);
                            if (window.UserList && typeof window.UserList.initUserManagement === 'function') {
                                try { window.UserList.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('UserList.initUserManagement error', e); }
                            } else if (typeof window.initUserManagement === 'function') {
                                try { window.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('initUserManagement error', e); }
                            }
                            dbg('initialized user list because hasUserList=true');
                        }
                        // 追加: data-view または URL の view が 'users' の場合は、
                        // サーバレンダで user-list の root がなくてもクライアント側の初期化を行う
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
                        } catch(e) { console.warn('forced user init error', e); }
                    } catch(e) { console.warn('server-rendered main-content user init error', e); }
                }

                // 初期ロード時の履歴を一貫させるために replaceState で current state を設定
                try {
                    const newUrl = '/main?view=' + encodeURIComponent(initialView);
                    history.replaceState({view: initialView}, '', newUrl);
                } catch(e) { /* ignore */ }
            }
        } catch(e) { /* ignore */ }
    });
})();
