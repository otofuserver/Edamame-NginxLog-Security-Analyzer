(function(){
    'use strict';

    function escapeHtml(s) { if (!s) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    let selectedServer = null;
    let serverMenu = null;
    let currentUserIsAdmin = false;
    let confirmHandlersBound = false;

    function renderError(msg) {
        const body = document.getElementById('server-results-body'); if (body) body.innerHTML = '<tr><td colspan="3">' + escapeHtml(msg) + '</td></tr>';
        const pagination = document.getElementById('server-pagination'); if (pagination) pagination.innerHTML = '';
    }

    function showContextMenu(ev) {
        if (!serverMenu) {
            const menuEl = document.getElementById('server-context-menu');
            if (menuEl && window.MiniMenu && typeof window.MiniMenu.create === 'function') {
                serverMenu = window.MiniMenu.create(menuEl);
            }
        }
        if (!serverMenu || !selectedServer) return;
        const isActive = selectedServer && selectedServer.isActive;
        const serverName = selectedServer && selectedServer.serverName ? selectedServer.serverName : '';
        serverMenu.show({
            x: ev.clientX,
            y: ev.clientY,
            items: [
                {
                    label: '無効化',
                    hidden: !isActive,
                    disabled: !currentUserIsAdmin,
                    onClick: () => {
                        if (!currentUserIsAdmin) return;
                        hideContextMenu();
                        showConfirmModal('サーバー "' + serverName + '" を無効化しますか？', disableSelectedServer);
                    }
                },
                {
                    label: '有効化',
                    hidden: !!isActive,
                    disabled: !currentUserIsAdmin,
                    onClick: () => {
                        if (!currentUserIsAdmin) return;
                        hideContextMenu();
                        showConfirmModal('サーバー "' + serverName + '" を有効化しますか？', enableSelectedServer);
                    }
                }
            ]
        });
    }

    function hideContextMenu() {
        if (serverMenu && typeof serverMenu.hide === 'function') {
            serverMenu.hide();
            return;
        }
        const menu = document.getElementById('server-context-menu'); if (!menu) return; menu.style.display = 'none'; menu.setAttribute('aria-hidden','true');
    }

    let __confirmOkHandler = null;
    function showConfirmModal(message, onOk) {
         hideContextMenu();
         const modal = document.getElementById('confirm-modal');
         const backdrop = document.getElementById('confirm-backdrop');
         const msgEl = document.getElementById('confirm-modal-message');
         if (!modal || !backdrop || !msgEl) {
             if (typeof onOk === 'function') onOk();
             return;
         }
         bindConfirmHandlers();
         msgEl.textContent = message || 'この操作を実行しますか？';
         modal.classList.remove('hidden');
         backdrop.classList.remove('hidden');
         __confirmOkHandler = onOk;
     }
     function hideConfirmModal() {
         const modal = document.getElementById('confirm-modal');
         const backdrop = document.getElementById('confirm-backdrop');
         if (modal) modal.classList.add('hidden');
         if (backdrop) backdrop.classList.add('hidden');
         __confirmOkHandler = null;
     }

    function bindConfirmHandlers() {
        if (confirmHandlersBound) return;
        const cCancel = document.getElementById('confirm-cancel');
        const cOk = document.getElementById('confirm-ok');
        if (cCancel) {
            cCancel.addEventListener('click', () => { hideConfirmModal(); });
        }
        if (cOk) {
            cOk.addEventListener('click', () => {
                if (typeof __confirmOkHandler === 'function') {
                    try { __confirmOkHandler(); } catch(e){ console.error('confirm ok handler error', e); }
                }
            });
        }
        confirmHandlersBound = !!(cCancel || cOk);
    }

    async function disableSelectedServer() {
        if (!selectedServer || selectedServer.id == null) { alert('サーバーが選択されていません'); return; }
        if (!currentUserIsAdmin) { alert('操作には管理者権限が必要です'); return; }
        const id = selectedServer.id;
        try {
            const resp = await fetch('/api/servers/' + encodeURIComponent(id) + '/disable', { method: 'POST', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) {
                const text = await resp.text().catch(()=>'<no-body>');
                alert('無効化に失敗しました: ' + resp.status + '\n' + text);
                return;
            }
            hideContextMenu();
            hideConfirmModal();
            if (window.ServerListView && typeof window.ServerListView.reload === 'function') window.ServerListView.reload();
        } catch (e) { console.error('[ServerList] disable error', e); alert('通信エラー'); }
    }

    async function enableSelectedServer() {
        if (!selectedServer || selectedServer.id == null) { alert('サーバーが選択されていません'); return; }
        if (!currentUserIsAdmin) { alert('操作には管理者権限が必要です'); return; }
        const id = selectedServer.id;
        try {
            const resp = await fetch('/api/servers/' + encodeURIComponent(id) + '/enable', { method: 'POST', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) {
                const text = await resp.text().catch(()=>'<no-body>');
                alert('有効化に失敗しました: ' + resp.status + '\n' + text);
                return;
            }
            hideContextMenu();
            hideConfirmModal();
            if (window.ServerListView && typeof window.ServerListView.reload === 'function') window.ServerListView.reload();
        } catch (e) { console.error('[ServerList] enable error', e); alert('通信エラー'); }
    }

    function renderRows(servers, state){
        const body = document.getElementById('server-results-body');
        if (!body) return;
        if (!servers || servers.length === 0) { body.innerHTML = '<tr><td colspan="3">該当するサーバーが見つかりません</td></tr>'; return; }
        const sortKey = state.sort || 'serverName';
        const dir = state.order === 'desc' ? -1 : 1;
        const arr = servers.slice().sort((a,b)=>{
            let va = a[sortKey]; let vb = b[sortKey];
            if (sortKey === 'lastLogReceived') { va = va ? new Date(va).getTime() : 0; vb = vb ? new Date(vb).getTime() : 0; }
            else if (sortKey === 'isActive') { va = a.isActive ? 1 : 0; vb = b.isActive ? 1 : 0; }
            else { va = (va||'').toString().toLowerCase(); vb = (vb||'').toString().toLowerCase(); }
            if (va < vb) return -1 * dir; if (va > vb) return 1 * dir; return 0;
        });
        body.innerHTML = '';
        for (const s of arr) {
            const last = s.lastLogReceived ? new Date(s.lastLogReceived).toLocaleString() : '';
            const tr = document.createElement('tr');
            tr.className = s.isActive ? '' : 'inactive';
            tr.innerHTML = '<td>' + escapeHtml(s.serverName) + '</td><td>' + escapeHtml(last) + '</td><td>' + (s.isActive ? '有効' : '無効') + '</td>';
            tr.style.cursor = 'pointer';
            tr.addEventListener('click', (ev) => {
                ev.stopPropagation();
                selectedServer = s;
                showContextMenu(ev);
            });
            body.appendChild(tr);
        }
    }

    function initServerManagement(){
        const MAX_RETRY = 30; let attempt = 0;
        function tryInit() {
            attempt++;
            const qInput = document.getElementById('server-q');
            const body = document.getElementById('server-results-body');
            const pagination = document.getElementById('server-pagination');
            const listViewAvailable = window.ListViewCore && typeof window.ListViewCore.createListView === 'function';
            if (!qInput || !body || !pagination || !listViewAvailable) {
                if (!listViewAvailable && typeof window.loadScript === 'function') {
                    try { window.loadScript('/static/list_view_core.js').catch(()=>{}); } catch(e) {}
                }
                if (attempt < MAX_RETRY) { setTimeout(tryInit, 100); return; }
                console.warn('[ServerList] init: required DOM or ListViewCore not ready', { q:!!qInput, body:!!body, pagination:!!pagination, listCore:listViewAvailable });
                return;
            }

            try {
                const adminEl = document.getElementById('current-user-admin');
                currentUserIsAdmin = adminEl && adminEl.dataset && adminEl.dataset.isAdmin === 'true';
            } catch (e) { currentUserIsAdmin = false; }

            let debounceTimer = null; const DEBOUNCE_MS = 250;
            function scheduleSearch(listView) { if (debounceTimer) clearTimeout(debounceTimer); debounceTimer = setTimeout(() => { if (listView && listView.reload) listView.reload(1); }, DEBOUNCE_MS); }

            const listView = window.ListViewCore.createListView({
                headerSelector: '#server-results th[data-column]',
                pagerEl: pagination,
                searchInput: qInput,
                applyStateToUi: function(state){ if (qInput) qInput.value = state.q || ''; },
                extractFilters: function(){ return {}; },
                fetcher: async function(params){
                    const sp = new URLSearchParams();
                    if (params.q) sp.append('q', params.q);
                    sp.append('page', params.page ? params.page : 1);
                    sp.append('size', params.size ? params.size : 20);
                    if (params.sort) sp.append('sort', params.sort);
                    if (params.order) sp.append('order', params.order);
                    const url = '/api/servers?' + sp.toString();
                    const resp = await fetch(url, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
                    if (resp.status === 401) { window.location.href = '/login'; return { items: [], total:0, page:1, size:20, totalPages:1 }; }
                    if (!resp.ok) {
                        const text = await resp.text().catch(()=>'<no-body>');
                        console.error('[ServerList] fetch failed', { status: resp.status, body: text });
                        renderError('エラー(' + resp.status + ')');
                        throw new Error('fetch failed');
                    }
                    const data = await resp.json();
                    return {
                        items: data.servers || [],
                        total: data && data.total ? data.total : 0,
                        page: data && data.page ? data.page : (params.page ? params.page : 1),
                        size: data && data.size ? data.size : (params.size ? params.size : 20),
                        totalPages: (data && data.totalPages) ? data.totalPages : Math.max(1, Math.ceil((data && data.total ? data.total : 0) / (data && data.size ? data.size : (params.size ? params.size : 20))))
                    };
                },
                renderRows: renderRows,
                onStateChange: function(){ /* no-op */ }
            });

            if (listView) {
                if (!listView.state.sort) listView.state.sort = 'serverName';
                listView.init();
                window.ServerListView = listView;
                qInput.addEventListener('input', function(){ scheduleSearch(listView); });
                qInput.addEventListener('keydown', function(e){ if (e.key === 'Enter') { e.preventDefault(); scheduleSearch(listView); } });
                // mini_menu.js 初期化（サーバー管理用）
                try {
                    const menuEl = document.getElementById('server-context-menu');
                    if (menuEl && window.MiniMenu && typeof window.MiniMenu.create === 'function') {
                        serverMenu = window.MiniMenu.create(menuEl);
                    }
                } catch (e) {
                    console.error('[ServerList] mini menu init error', e);
                }
            }
        }
        tryInit();
    }

    window.ServerList = { renderError, initServerManagement };
 })();
