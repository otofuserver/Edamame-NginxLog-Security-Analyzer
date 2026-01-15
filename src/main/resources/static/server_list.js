(function(){
    'use strict';

    // ServerList モジュール: 検索、一覧描画、ページング、行クリックでコンテキストメニュー表示
    function escapeHtml(s) { if (!s) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    let currentServers = [];
    let currentTotal = 0;
    let currentPage = 1;
    let currentSize = 20;
    let sortColumn = 'serverName';
    let sortDir = 1;
    let selectedServer = null;
    let serverMenu = null;
    // サーバー操作を行う現在ユーザーが管理者かどうか（サーバ側が出力する隠し要素から取得する）
    let currentUserIsAdmin = false;
    let confirmHandlersBound = false;

    async function doSearch(page=1) {
        console.debug('[ServerList] doSearch', { page });
        const qInput = document.getElementById('server-q');
        if (!qInput) { console.warn('[ServerList] q input not found'); return; }
        const q = (qInput.value || '').trim();
        const url = q === '' ? ('/api/servers?page=' + page + '&size=' + currentSize) : ('/api/servers?q=' + encodeURIComponent(q) + '&page=' + page + '&size=' + currentSize);
        try {
            const resp = await fetch(url, { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
            console.debug('[ServerList] fetch response', { ok: resp.ok, status: resp.status });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) {
                const text = await resp.text().catch(()=>'<no-body>');
                console.error('[ServerList] fetch failed', { status: resp.status, body: text });
                renderError('エラー(' + resp.status + ')');
                return;
            }
            const data = await resp.json();
            render(data.servers || [], data.total || 0, data.page || 1, data.size || currentSize);
        } catch (e) { console.error('[ServerList] fetch error', e); renderError('通信エラー'); }
    }

    function render(servers, total, page, size) {
        currentServers = servers || [];
        currentTotal = total || 0;
        currentPage = page || 1;
        currentSize = size || 20;
        const body = document.getElementById('server-results-body');
        const pagination = document.getElementById('server-pagination');
        if (!body || !pagination) return;
        if (!currentServers || currentServers.length === 0) { body.innerHTML = '<tr><td colspan="3">該当するサーバーが見つかりません</td></tr>'; pagination.innerHTML = ''; return; }

        const arr = currentServers.slice();
        arr.sort((a,b) => {
            let va = a[sortColumn]; let vb = b[sortColumn];
            if (sortColumn === 'lastLogReceived') { va = va ? new Date(va).getTime() : 0; vb = vb ? new Date(vb).getTime() : 0; }
            else if (sortColumn === 'isActive') { va = a.isActive ? 1 : 0; vb = b.isActive ? 1 : 0; }
            else { va = (va||'').toString().toLowerCase(); vb = (vb||'').toString().toLowerCase(); }
            if (va < vb) return -1 * sortDir; if (va > vb) return 1 * sortDir; return 0;
        });

        body.innerHTML = '';
        for (const s of arr) {
            const last = s.lastLogReceived ? new Date(s.lastLogReceived).toLocaleString() : '';
            const tr = document.createElement('tr');
            tr.className = s.isActive ? '' : 'inactive';
            tr.innerHTML = '<td>' + escapeHtml(s.serverName) + '</td><td>' + escapeHtml(last) + '</td><td>' + (s.isActive ? '有効' : '無効') + '</td>';
            tr.style.cursor = 'pointer';
            // 行クリックでコンテキストメニュー表示
            tr.addEventListener('click', (ev) => {
                ev.stopPropagation();
                selectedServer = s;
                showContextMenu(ev);
            });
            body.appendChild(tr);
        }
        renderPagination(currentTotal, currentPage, currentSize);
    }

    function renderPagination(total, page, size) {
        const pagination = document.getElementById('server-pagination'); if (!pagination) return;
        pagination.innerHTML = '';
        const totalPages = Math.max(1, Math.ceil(total / size));
        const prev = document.createElement('button'); prev.textContent = '前へ'; prev.disabled = page <= 1; prev.addEventListener('click', () => doSearch(page - 1));
        const next = document.createElement('button'); next.textContent = '次へ'; next.disabled = page >= totalPages; next.addEventListener('click', () => doSearch(page + 1));
        const info = document.createElement('span'); info.textContent = ' ページ ' + page + ' / ' + totalPages + ' （合計 ' + total + ' 件） ';
        pagination.appendChild(prev); pagination.appendChild(info); pagination.appendChild(next);
    }

    function renderError(msg) {
        const body = document.getElementById('server-results-body'); if (body) body.innerHTML = '<tr><td colspan="3">' + escapeHtml(msg) + '</td></tr>';
        const pagination = document.getElementById('server-pagination'); if (pagination) pagination.innerHTML = '';
    }

    function attachHeaderSortHandlers() {
        document.querySelectorAll('th[data-column]').forEach(th => {
            th.__edamame_click__ && th.removeEventListener('click', th.__edamame_click__);
            th.__edamame_click__ = function() { const col = th.getAttribute('data-column'); if (sortColumn === col) sortDir = -sortDir; else { sortColumn = col; sortDir = 1; } render(currentServers, currentTotal, currentPage, currentSize); };
            th.addEventListener('click', th.__edamame_click__);
        });
    }

    function hideContextMenu() {
        if (serverMenu && typeof serverMenu.hide === 'function') {
            serverMenu.hide();
            return;
        }
        const menu = document.getElementById('server-context-menu'); if (!menu) return; menu.style.display = 'none'; menu.setAttribute('aria-hidden','true');
    }

    function showContextMenu(ev) {
        if (!serverMenu) {
            const menuEl = document.getElementById('server-context-menu');
            if (menuEl && window.MiniMenu && typeof window.MiniMenu.create === 'function') {
                serverMenu = window.MiniMenu.create(menuEl);
            }
        }
        if (!serverMenu) return;
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

    // 確認モーダル表示/非表示とコールバック管理（URL脅威度同様に hidden クラスで制御）
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
            cCancel.addEventListener('click', () => { hideConfirmModal(); console.debug('[ServerList] confirm-cancel clicked'); });
        }
        if (cOk) {
            cOk.addEventListener('click', () => {
                console.debug('[ServerList] confirm-ok clicked handler start');
                if (typeof __confirmOkHandler === 'function') {
                    try { __confirmOkHandler(); } catch(e){ console.error('confirm ok handler error', e); }
                }
            });
        }
        confirmHandlersBound = !!(cCancel || cOk);
        if (!confirmHandlersBound) console.warn('[ServerList] confirm handlers not bound (buttons missing)');
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
            doSearch(currentPage);
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
            doSearch(currentPage);
        } catch (e) { console.error('[ServerList] enable error', e); alert('通信エラー'); }
    }

    function initServerManagement(initialQ) {
        const MAX_RETRY = 20; let attempt = 0;
        function tryInit() {
            attempt++;
            const qInput = document.getElementById('server-q');
            const form = document.getElementById('server-search-form');
            const body = document.getElementById('server-results-body');
            const pagination = document.getElementById('server-pagination');
            if (!qInput || !body || !pagination) { if (attempt < MAX_RETRY) { setTimeout(tryInit, 100); return; } console.warn('[ServerList] init: required DOM not found'); return; }

            // 管理者フラグの取得（DashboardController が隠し要素で出力している）
            try {
                const adminEl = document.getElementById('current-user-admin');
                currentUserIsAdmin = adminEl && adminEl.dataset && adminEl.dataset.isAdmin === 'true';
            } catch (e) { currentUserIsAdmin = false; }

            if (initialQ) qInput.value = initialQ;

            let debounceTimer = null; const DEBOUNCE_MS = 250;
            function scheduleSearch() { if (debounceTimer) clearTimeout(debounceTimer); debounceTimer = setTimeout(() => doSearch(1), DEBOUNCE_MS); }

            if (form) { form.addEventListener('submit', e => { e.preventDefault(); }); }
            qInput.addEventListener('input', scheduleSearch);
            qInput.addEventListener('keydown', function(e){ if (e.key === 'Enter') { e.preventDefault(); doSearch(1); } });

            // attach header handlers
            attachHeaderSortHandlers();

            // mini_menu.js 初期化（サーバー管理用）
            try {
                const menuEl = document.getElementById('server-context-menu');
                if (menuEl && window.MiniMenu && typeof window.MiniMenu.create === 'function') {
                    serverMenu = window.MiniMenu.create(menuEl);
                }
            } catch (e) {
                console.error('[ServerList] mini menu init error', e);
            }

            doSearch(1);
        }
        tryInit();
    }

    window.ServerList = { initServerManagement, doSearch, render, renderError };
})();
