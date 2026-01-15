(function(){
    'use strict';

    async function navigateTo(view, push = true) {
        if (window.dbg) window.dbg('navigateTo called, view=', view, 'push=', push);
        try {
            const main = document.getElementById('main-content'); if (!main) return;
            const routeMap = { 'dashboard': '/api/fragment/dashboard', 'template': '/api/fragment/test', 'users': '/api/fragment/users', 'servers': '/api/fragment/servers', 'url_threat': '/api/fragment/url_threat' };
            const apiPath = routeMap[view] || ('/api/fragment/' + encodeURIComponent(view));
            main.innerHTML = '<div class="card"><p>読み込み中...</p></div>';
            const resp = await fetch(apiPath, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'text/html, application/json' } });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) { main.innerHTML = '<div class="card"><p>データ取得エラー</p></div>'; return; }
            const ct = resp.headers.get('content-type') || '';
            if (ct.includes('text/html')) {
                const html = await resp.text();
                main.innerHTML = html;

                // update data-view so server/client initialization can rely on it
                try { main.setAttribute('data-view', view); } catch(e) { /* ignore */ }

                try { if (window.setupFragmentAutoRefresh) setupFragmentAutoRefresh(main); } catch(e) { console.warn('setupFragmentAutoRefresh after navigateTo error', e); }

                if (view === 'users' || view === 'servers') {
                    if (view === 'users') {
                        await loadScriptsSequential(['/static/user_list.js','/static/user_modal.js']);
                        if (window.UserList && typeof window.UserList.initUserManagement === 'function') {
                            try { window.UserList.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('UserList.initUserManagement error', e); }
                        } else if (typeof window.initUserManagement === 'function') {
                            try { window.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('initUserManagement error', e); }
                        }
                    }
                    if (view === 'servers') {
                        await loadScriptsSequential(['/static/server_list.js']);
                        if (window.ServerList && typeof window.ServerList.initServerManagement === 'function') {
                            try { window.ServerList.initServerManagement(new URLSearchParams(window.location.search).get('q')); } catch(e) { console.error('ServerList.initServerManagement error', e); }
                        }
                    }
                }
                if (view === 'url_threat') {
                    await loadScriptsSequential(['/static/mini_menu.js','/static/url_threat.js']);
                    if (window.UrlThreat && typeof window.UrlThreat.init === 'function') {
                        try { window.UrlThreat.init(); } catch(e) { console.error('UrlThreat.init error', e); }
                    }
                }
            } else {
                const j = await resp.json(); main.innerHTML = '<pre>'+JSON.stringify(j,null,2)+'</pre>';
            }
            // always reflect view in the URL: pushState when push=true, otherwise replaceState
            const newUrl = '/main?view=' + encodeURIComponent(view);
            try {
                if (push) {
                    history.pushState({}, '', newUrl);
                } else {
                    history.replaceState({}, '', newUrl);
                }
            } catch(e) {
                // some old browsers may throw; fallback to direct location change only if push requested
                if (push) window.location.href = newUrl;
            }
        } catch (e) {
            const main = document.getElementById('main-content'); if (main) main.innerHTML = '<div class="card"><p>データ取得エラー</p></div>';
            console.error('navigateTo error', e);
        }
    }

    window.navigateTo = navigateTo;
})();
