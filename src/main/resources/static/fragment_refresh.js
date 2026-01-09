(function(){
    'use strict';

    // 完全一本化: グローバルの loadScriptsSequential を必須で利用します。
    // 注意: /static/script.js が事前に読み込まれ、window.loadScriptsSequential を提供していることが前提です。
    async function loadScriptsSequential(urls) {
        if (typeof window.loadScriptsSequential === 'function') {
            return window.loadScriptsSequential(urls);
        }
        console.error('Global loadScriptsSequential not found. Ensure /static/script.js is loaded before fragment_refresh.js');
        return Promise.resolve();
    }

    function setupFragmentAutoRefresh(root) {
        try {
            const container = root || document;
            const elements = (container && container.querySelectorAll) ? container.querySelectorAll('.fragment-root[data-auto-refresh][data-fragment-name]') : [];
            elements.forEach(el => {
                try {
                    const s = parseInt(el.getAttribute('data-auto-refresh') || '0', 10);
                    const name = el.getAttribute('data-fragment-name');
                    if (!name || !s || s <= 0) return;

                    if (el.__edamame_refresh_interval_id) {
                        try { clearInterval(el.__edamame_refresh_interval_id); } catch(_) {}
                        el.__edamame_refresh_interval_id = null;
                    }

                    const refreshFn = async () => {
                        try {
                            const path = '/api/fragment/' + encodeURIComponent(name);
                            const resp = await fetch(path, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'text/html, application/json' } });
                            if (resp.status === 401) { console.warn('Auto-refresh: unauthorized for fragment', name); return; }
                            if (!resp.ok) { console.warn('Auto-refresh: fetch failed for', name, resp.status); return; }
                            const ct = resp.headers.get('content-type') || '';
                            if (ct.includes('text/html')) {
                                const html = await resp.text();
                                const tmp = document.createElement('div');
                                tmp.innerHTML = html;
                                const newFragment = tmp.querySelector('.fragment-root') || tmp;
                                el.innerHTML = newFragment.innerHTML || '';

                                if (name === 'users') {
                                    // グローバル loader を利用して必要なスクリプトを読み込む
                                    loadScriptsSequential(['/static/user_list.js','/static/user_modal.js']).then(() => {
                                        if (window.UserList && typeof window.UserList.initUserManagement === 'function') {
                                            try { window.UserList.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e){ console.error('UserList.initUserManagement error', e); }
                                        } else if (typeof window.initUserManagement === 'function') {
                                            try { window.initUserManagement(new URLSearchParams(window.location.search).get('q')); } catch(e){ console.error('initUserManagement error', e); }
                                        }
                                    }).catch(()=>{});
                                }
                            } else {
                                console.warn('Auto-refresh: unexpected content-type for fragment', name, ct);
                            }
                        } catch (e) { console.warn('Auto-refresh error for', name, e); }
                    };

                    el.__edamame_refresh_interval_id = setInterval(refreshFn, s * 1000);
                    if (window.dbg) window.dbg('setupFragmentAutoRefresh set for', name, 'interval(s)=', s);
                } catch(e) { console.warn('setupFragmentAutoRefresh element error', e); }
            });
        } catch(e) { console.warn('setupFragmentAutoRefresh error', e); }
    }

    window.setupFragmentAutoRefresh = setupFragmentAutoRefresh;
})();
