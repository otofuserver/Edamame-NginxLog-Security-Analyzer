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
                                applyFragmentHtml(el, html);

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

    async function refreshFragmentOnce(name) {
        if (!name) return;
        try {
            const path = '/api/fragment/' + encodeURIComponent(name);
            const resp = await fetch(path, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'text/html, application/json' } });
            if (resp.status === 401) { console.warn('Manual refresh unauthorized for fragment', name); return; }
            if (!resp.ok) { console.warn('Manual refresh failed for', name, resp.status); return; }
            const ct = resp.headers.get('content-type') || '';
            if (ct.includes('text/html')) {
                const html = await resp.text();
                const target = document.querySelector('.fragment-root[data-fragment-name="' + name + '"]');
                if (target) {
                    applyFragmentHtml(target, html);
                    if (name === 'block_ip') {
                        await ensureBlockIpInit();
                    }
                }
            }
        } catch (e) { console.warn('Manual fragment refresh error for', name, e); }
    }

    async function ensureBlockIpInit(){
        const doInit = () => {
            if (!window.BlockIp) return false;
            try { if (typeof window.BlockIp.dispose === 'function') window.BlockIp.dispose(); } catch(_){ }
            try { if (typeof window.BlockIp.init === 'function') { window.BlockIp.init(); return true; } } catch(e){ console.warn('block_ip init after refresh failed', e); }
            return false;
        };
        if (doInit()) return;
        if (typeof window.loadScript === 'function') {
            try {
                await window.loadScript('/static/block_ip.js');
                doInit();
            } catch(e){ console.warn('block_ip script load failed', e); }
        }
    }

    function applyFragmentHtml(el, html) {
        const tmp = document.createElement('div');
        tmp.innerHTML = html;
        const newFragment = tmp.querySelector('.fragment-root') || tmp;
        el.innerHTML = newFragment.innerHTML || '';
    }

    window.setupFragmentAutoRefresh = setupFragmentAutoRefresh;
    window.refreshFragmentOnce = refreshFragmentOnce;

    // block_ipクリーンアップ完了時の軽量トリガー用: このイベントを受けたら block_ip 断片だけ再取得
    window.addEventListener('block-ip:cleanup-done', function(){ refreshFragmentOnce('block_ip'); });
})();
