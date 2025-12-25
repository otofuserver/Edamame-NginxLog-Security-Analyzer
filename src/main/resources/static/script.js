(function(){
    'use strict';

    // 簡易なランタイムチェック: 分割したモジュールが読み込まれているか確認するヘルパ
    function ensureModules() {
        if (!window.UserList) console.warn('UserList モジュールがロードされていません');
        if (!window.UserModal) console.warn('UserModal モジュールがロードされていません');
    }

    // 動的スクリプトローダー（同一 URL は二重ロードしない）
    const _loadedScripts = new Set();
    function loadScript(url) {
        return new Promise((resolve, reject) => {
            if (!url) return resolve();
            if (_loadedScripts.has(url)) return resolve();
            const s = document.createElement('script');
            s.src = url;
            s.async = false; // 実行順を保つ
            s.onload = () => { _loadedScripts.add(url); resolve(); };
            s.onerror = (e) => reject(new Error('script load error: ' + url));
            document.head.appendChild(s);
        });
    }

    // 指定スクリプト群を順にロード
    async function loadScriptsSequential(urls) {
        for (const u of (urls||[])) {
            if (!u) continue;
            try { await loadScript(u); } catch(e) { console.warn('スクリプト読み込みに失敗:', u, e); }
        }
    }

    function confirmLogout() {
        if (confirm("ログアウトしますか？")) {
            fetch('/logout', { method: 'POST', credentials: 'same-origin' })
                .then(r => { if (r.ok || r.redirected) window.location.href = '/login?logout=success'; else alert('ログアウトに失敗しました。'); })
                .catch(() => { window.location.href = '/logout'; });
        }
    }

    function navigateTo(view, push) {
        const main = document.getElementById('main-content'); if (!main) return;
        const routeMap = { 'dashboard': '/api/fragment/dashboard', 'template': '/api/fragment/test', 'users': '/api/fragment/users' };
        const apiPath = routeMap[view] || ('/api/fragment/' + encodeURIComponent(view));
        main.innerHTML = '<div class="card"><p>読み込み中...</p></div>';
        fetch(apiPath, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'text/html, application/json' } })
            .then(async r => { if (r.status === 401) { window.location.href = '/login'; throw new Error('Unauthorized'); } if (!r.ok) throw new Error('Network'); const ct = r.headers.get('content-type')||''; return ct.includes('text/html')? r.text().then(html=>({html})): r.json().then(json=>({json})); })
            .then(async result => {
                if (result.html) {
                    main.innerHTML = result.html;
                    try {
                        // ユーザー管理画面の場合は依存スクリプトを先にロードしてから初期化
                        if (view === 'users' || (new URLSearchParams(window.location.search).has('q') && view === 'users')) {
                            await loadScriptsSequential(['/static/user_list.js','/static/user_modal.js']);
                        }
                        ensureModules();
                        // UserList モジュールに init があればそれを使う（分割後の初期化）
                        const qparam = new URLSearchParams(window.location.search).get('q');
                        async function attemptInitUserList(retries = 5) {
                            if (window.UserList && typeof window.UserList.initUserManagement === 'function') {
                                console.debug('Initializing UserList via window.UserList.initUserManagement', { qparam });
                                try { window.UserList.initUserManagement(qparam); } catch(e) { console.error('UserList.initUserManagement error', e); }
                                return;
                            }
                            if (typeof window.initUserManagement === 'function') {
                                console.debug('Falling back to window.initUserManagement', { qparam });
                                try { window.initUserManagement(qparam); } catch(e) { console.error('initUserManagement error', e); }
                                return;
                            }
                            if (retries > 0) {
                                console.debug('UserList not ready, retrying in 50ms', { retries });
                                setTimeout(() => attemptInitUserList(retries - 1), 50);
                            } else {
                                console.warn('UserList init failed: module not found');
                            }
                        }
                        attemptInitUserList(5);
                    } catch(e){}
                } else if (result.json) {
                    main.innerHTML = '<pre>'+JSON.stringify(result.json, null, 2)+'</pre>';
                }
            })
            .catch(e => { main.innerHTML = '<div class="card"><p>データ取得エラー</p></div>'; });
    }

    // ページ初期化
    document.addEventListener('DOMContentLoaded', function(){
        const params = new URLSearchParams(window.location.search);
        const initialView = params.get('view') || (params.has('q') ? 'users' : 'dashboard');
        navigateTo(initialView, false);
        // サイドバーのログアウトボタンがあればハンドラを接続
        try {
            const lb = document.getElementById('logout-btn');
            if (lb && !lb.__edamame_logout_attached__) { lb.addEventListener('click', confirmLogout); lb.__edamame_logout_attached__ = true; }
        } catch(e) {}
    });

    window.confirmLogout = confirmLogout;
    window.navigateTo = navigateTo;
})();
