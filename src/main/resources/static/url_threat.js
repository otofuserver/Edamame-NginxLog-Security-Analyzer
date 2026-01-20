(function(){
    'use strict';

    function escapeHtml(s) { if (s === null || s === undefined) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }
    function escapeHtmlKeepSlash(s) { if (s === null || s === undefined) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    const STATE = { server: '', filter: 'all', canOperate: false, currentItem: null, currentAction: null, lastVisible: 0, total: 0 };
    let listViewRef = null;
    let miniMenu = null;

    function showToast(msg) {
        let toast = document.getElementById('url-threat-toast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'url-threat-toast';
            toast.style.position = 'fixed';
            toast.style.right = '16px';
            toast.style.top = '16px';
            toast.style.padding = '10px 14px';
            toast.style.background = '#2b8a3e';
            toast.style.color = '#fff';
            toast.style.borderRadius = '8px';
            toast.style.boxShadow = '0 6px 18px rgba(0,0,0,0.18)';
            toast.style.zIndex = '2000';
            toast.style.fontSize = '0.95rem';
            document.body.appendChild(toast);
        }
        toast.textContent = msg || '';
        toast.style.display = 'block';
        setTimeout(function(){ toast.style.display = 'none'; }, 1600);
    }

    function closeMiniMenu(resetCurrent) { if (miniMenu) miniMenu.hide(); if (resetCurrent) STATE.currentItem = null; }

    function showMiniMenu(ev) {
        if (!miniMenu || !STATE.currentItem) return;
        const item = STATE.currentItem;
        const canOperate = STATE.canOperate;
        const items = [
            { label: 'URLをコピー', onClick: function(){ copyUrl(item.fullUrl || ''); showToast('URLをコピーしました'); } },
            { label: 'アクセスを危険にカテゴリーする', hidden: item.userFinalThreat, disabled: !canOperate, onClick: function(){ openNoteModal('danger', item); } },
            { label: 'アクセスを安全にカテゴリーする', hidden: item.isWhitelisted, disabled: !canOperate, onClick: function(){ openNoteModal('safe', item); } },
            { label: 'カテゴリーを解除する', hidden: (!item.userFinalThreat && !item.isWhitelisted), disabled: !canOperate, onClick: function(){ openNoteModal('clear', item); } },
            { label: canOperate ? 'カテゴリーされた理由を確認する' : 'カテゴリーされた理由を確認する（閲覧のみ）', onClick: function(){ openNoteModal('note', item); }, disabled: false }
        ];
        const x = (ev.pageX !== undefined) ? ev.pageX : ev.clientX;
        const y = (ev.pageY !== undefined) ? ev.pageY : ev.clientY;
        miniMenu.show({ x: x, y: y, items: items });
    }

    function setupMiniMenu() { const menu = document.getElementById('url-threat-mini-menu'); miniMenu = window.MiniMenu ? window.MiniMenu.create(menu) : null; }

    async function copyUrl(text) {
        try {
            if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(text); return true; }
        } catch (e) {}
        try {
            const ta = document.createElement('textarea');
            ta.value = text; ta.style.position = 'fixed'; ta.style.left = '-1000px';
            document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta); return true;
        } catch (e) { console.warn('clipboard fallback failed', e); return false; }
    }

    async function fetchServers(preferredServer) {
        try {
            const resp = await fetch('/api/servers?size=200', { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
            if (resp.status === 401) { window.location.href = '/login'; return []; }
            if (!resp.ok) { showMessage('サーバー取得に失敗しました'); return []; }
            const data = await resp.json();
            return data.servers || [];
        } catch (e) { showMessage('通信エラーが発生しました'); return []; }
    }

    function populateServers(servers, preferredServer) {
        const sel = document.getElementById('url-threat-server');
        if (!sel) return;
        sel.innerHTML = '';
        const activeServers = (servers || []).filter(function(s){ return s.isActive !== false; });
        if (activeServers.length === 0) {
            const opt = document.createElement('option');
            opt.textContent = '有効なサーバーなし'; opt.value = '';
            sel.appendChild(opt); showMessage('有効なサーバーが登録されていません'); return;
        }
        activeServers.forEach(function(s, idx){
            const opt = document.createElement('option'); opt.value = s.serverName || ''; opt.textContent = s.serverName || 'unknown'; if (idx === 0) opt.selected = true; sel.appendChild(opt);
        });
        if (preferredServer && activeServers.some(function(s){ return s.serverName === preferredServer; })) sel.value = preferredServer;
        STATE.server = sel.value;
    }

    function renderRows(items, state) {
        const tbody = document.getElementById('url-threat-body');
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!items || items.length === 0) { const tr = document.createElement('tr'); tr.innerHTML = '<td colspan="7">データがありません</td>'; tbody.appendChild(tr); STATE.lastVisible = 0; renderCount(state, 0); return; }
        STATE.lastVisible = items.length;
        items.forEach(function(item){
            const tr = document.createElement('tr');
            const threatKey = item.threatKey || 'unknown';
            tr.classList.add('threat-' + threatKey);
            tr.dataset.serverName = item.serverName || '';
            tr.dataset.method = item.method || '';
            tr.dataset.fullUrl = item.fullUrl || '';
            tr.dataset.userFinalThreat = item.userFinalThreat === true ? 'true' : 'false';
            tr.dataset.isWhitelisted = item.isWhitelisted === true ? 'true' : 'false';
            tr.dataset.userThreatNote = item.userThreatNote || '';

            const tdThreat = document.createElement('td'); tdThreat.classList.add('threat-cell'); tdThreat.innerHTML = '<span class="badge threat-' + threatKey + '">' + escapeHtml(item.threatLabel || '-') + '</span>';
            const tdUrl = document.createElement('td'); tdUrl.classList.add('url-cell'); tdUrl.textContent = item.fullUrl || ''; tdUrl.title = item.fullUrl || '';
            const tdMethod = document.createElement('td'); tdMethod.classList.add('method-cell'); tdMethod.textContent = item.method || ''; tdMethod.title = item.method || '';
            const tdAtk = document.createElement('td'); tdAtk.classList.add('attack-cell'); tdAtk.textContent = item.attackType ? item.attackType : '-'; tdAtk.title = item.attackType ? item.attackType : '';
            const tdLast = document.createElement('td'); tdLast.classList.add('access-cell'); tdLast.textContent = item.latestAccessTime || '未記録'; tdLast.title = item.latestAccessTime || '';
            const tdStatus = document.createElement('td'); tdStatus.classList.add('status-cell'); tdStatus.textContent = (item.latestStatusCode !== undefined && item.latestStatusCode !== null) ? item.latestStatusCode : ''; tdStatus.title = tdStatus.textContent;
            const tdModsec = document.createElement('td'); tdModsec.classList.add('modsec-cell'); tdModsec.textContent = item.latestBlockedByModsec === true ? 'ブロック' : ''; tdModsec.title = tdModsec.textContent;

            tr.append(tdThreat, tdUrl, tdMethod, tdAtk, tdLast, tdStatus, tdModsec);
            tr.addEventListener('click', function(ev){
                ev.stopPropagation();
                STATE.currentItem = {
                    serverName: tr.dataset.serverName,
                    method: tr.dataset.method,
                    fullUrl: tr.dataset.fullUrl,
                    userFinalThreat: tr.dataset.userFinalThreat === 'true',
                    isWhitelisted: tr.dataset.isWhitelisted === 'true',
                    userThreatNote: tr.dataset.userThreatNote || ''
                };
                updateMiniMenuVisibility();
                showMiniMenu(ev);
            });
            tbody.appendChild(tr);
        });
        updateSortIndicators(state);
        renderCount(state, STATE.lastVisible);
    }

    function renderCount(state, visibleCount) {
        const el = document.getElementById('url-threat-count');
        if (!el) return;
        if (!STATE.server) { el.textContent = ''; return; }
        const total = state && state.total ? state.total : STATE.total;
        const page = state && state.page ? state.page : 1;
        const size = state && state.size ? state.size : 20;
        const from = Math.min(((page - 1) * size) + 1, total);
        const to = Math.min(page * size, total);
        el.textContent = total === 0 ? '0 件' : (total + ' 件中 ' + from + '–' + to + ' 件を表示（このページ ' + (visibleCount || 0) + ' 件）');
    }

    function showMessage(msg) { const el = document.getElementById('url-threat-message'); if (!el) return; el.textContent = msg || ''; }

    function updateMiniMenuVisibility() {
        const menu = document.getElementById('url-threat-mini-menu');
        if (!menu || !STATE.currentItem) return;
        const userFinalThreat = STATE.currentItem.userFinalThreat;
        const isWhitelisted = STATE.currentItem.isWhitelisted;
        const canOperate = STATE.canOperate;
        const btnDanger = menu.querySelector('[data-action="danger"]');
        const btnSafe = menu.querySelector('[data-action="safe"]');
        const btnClear = menu.querySelector('[data-action="clear"]');
        const btnNote = menu.querySelector('[data-action="note"]');
        if (btnDanger) { btnDanger.classList.toggle('hidden', !!userFinalThreat); btnDanger.disabled = !canOperate; }
        if (btnSafe) { btnSafe.classList.toggle('hidden', !!isWhitelisted); btnSafe.disabled = !canOperate; }
        if (btnClear) { btnClear.classList.toggle('hidden', !userFinalThreat && !isWhitelisted); btnClear.disabled = !canOperate; }
        if (btnNote) { btnNote.disabled = false; if (!canOperate) btnNote.textContent = 'カテゴリーされた理由を確認する（閲覧のみ）'; }
    }

    function openNoteModal(action, item) {
        const backdrop = document.getElementById('url-threat-modal-backdrop');
        const modal = document.getElementById('url-threat-note-modal');
        const title = document.getElementById('url-threat-note-title');
        const urlEl = document.getElementById('url-threat-modal-url');
        const textarea = document.getElementById('url-threat-note-text');
        const hint = document.getElementById('url-threat-note-hint');
        const save = document.getElementById('url-threat-note-save');
        STATE.currentAction = action;
        urlEl.textContent = item.fullUrl || '';
        textarea.value = item.userThreatNote || '';
        const canOperate = STATE.canOperate;
        const isViewOnly = action === 'note' && !canOperate;
        textarea.readOnly = isViewOnly; textarea.disabled = isViewOnly; save.disabled = isViewOnly;
        title.textContent = action === 'note' ? 'カテゴリーされた理由を確認' : '理由を入力';
        hint.textContent = isViewOnly ? '閲覧のみ可能です（権限なし）' : '変更理由を記載してください';
        if (backdrop) backdrop.classList.remove('hidden');
        if (modal) modal.classList.remove('hidden');
    }

    function closeNoteModal() {
        const backdrop = document.getElementById('url-threat-modal-backdrop');
        const modal = document.getElementById('url-threat-note-modal');
        if (backdrop) backdrop.classList.add('hidden');
        if (modal) modal.classList.add('hidden');
        STATE.currentAction = null;
    }

    async function submitAction() {
        if (!STATE.currentItem || !STATE.currentAction) { closeNoteModal(); return; }
        const note = document.getElementById('url-threat-note-text').value || '';
        const body = {
            serverName: STATE.currentItem.serverName,
            method: STATE.currentItem.method,
            fullUrl: STATE.currentItem.fullUrl,
            action: STATE.currentAction,
            note: note
        };
        try {
            const resp = await fetch('/api/url-threats', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' }, body: JSON.stringify(body) });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) { showMessage('更新に失敗しました'); return; }
            showMessage('更新しました');
            closeNoteModal();
            if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page);
        } catch (e) { showMessage('通信エ��ーが発生しました'); }
    }

    function updateSortIndicators(state) {
        document.querySelectorAll('#url-threat-table th.sortable').forEach(function(th){
            const key = th.dataset.sort || '';
            const isActive = key === (state.sort || '');
            const label = th.dataset.labelOriginal || th.textContent.trim();
            if (!th.dataset.labelOriginal) th.dataset.labelOriginal = label;
            const arrow = isActive ? (state.order === 'desc' ? ' ▼' : ' ▲') : '';
            th.classList.toggle('active-sort', isActive);
            th.textContent = label + arrow;
        });
    }

    function showMessageLoading() { showMessage('読み込み中...'); }

    function applyStateToUi(state){
        const sel = document.getElementById('url-threat-server');
        if (sel) {
            if (state && state.server) sel.value = state.server; else if (STATE.server) sel.value = STATE.server;
            STATE.server = sel.value || '';
        }
        const qInput = document.getElementById('url-threat-q');
        if (qInput && state && typeof state.q === 'string') qInput.value = state.q;
        const filterVal = (state && state.filter) ? state.filter : STATE.filter;
        document.querySelectorAll('input[name="url-threat-filter"]').forEach(function(r){ if (r.value === filterVal) r.checked = true; });
    }

    function bindEvents(listView){
        const sel = document.getElementById('url-threat-server');
        if (sel) sel.addEventListener('change', function(){ STATE.server = sel.value || ''; if (listView && listView.reload) listView.reload(1); });
        document.querySelectorAll('input[name="url-threat-filter"]').forEach(function(r){ r.addEventListener('change', function(){ if (r.checked && listView && listView.reload) { STATE.filter = r.value || 'all'; listView.reload(1); } }); });
        const save = document.getElementById('url-threat-note-save'); if (save) save.addEventListener('click', submitAction);
        const cancel = document.getElementById('url-threat-note-cancel'); if (cancel) cancel.addEventListener('click', function(){ closeNoteModal(); });
        const backdrop = document.getElementById('url-threat-modal-backdrop'); if (backdrop) backdrop.addEventListener('click', function(){ closeNoteModal(); });
        setupMiniMenu();
    }

    function parseInitialFromUrl(){
        try {
            const params = new URLSearchParams(window.location.search);
            STATE.server = params.get('server') || STATE.server;
            STATE.filter = params.get('filter') || STATE.filter;
        } catch(e) {}
    }

    function createListView(){
        const qInput = document.getElementById('url-threat-q');
        const pagerEl = document.getElementById('url-threat-pager');
        const headerSelector = '#url-threat-table th.sortable';
        if (!window.ListViewCore || typeof window.ListViewCore.createListView !== 'function') {
            if (typeof window.loadScript === 'function') { try { window.loadScript('/static/list_view_core.js').catch(function(){}); } catch(e){} }
            return null;
        }
        const listView = window.ListViewCore.createListView({
            headerSelector: headerSelector,
            pagerEl: pagerEl,
            searchInput: qInput,
            applyStateToUi: applyStateToUi,
            extractFilters: function(){ return { server: STATE.server, filter: STATE.filter }; },
            fetcher: async function(params){
                const sp = new URLSearchParams();
                const server = params.server || STATE.server || '';
                const filter = params.filter || STATE.filter || 'all';
                const page = params.page ? params.page : 1;
                const size = params.size ? params.size : 20;
                STATE.server = server; STATE.filter = filter;
                if (!server) { showMessage('サーバーを選択してください'); return { items: [], total: 0, page: 1, size: size, totalPages: 1 }; }
                sp.append('server', server);
                sp.append('filter', filter);
                sp.append('page', page);
                sp.append('size', size);
                if (params.q) sp.append('q', params.q);
                if (params.sort) sp.append('sort', params.sort); if (params.order) sp.append('order', params.order);
                showMessageLoading();
                const resp = await fetch('/api/url-threats?' + sp.toString(), { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
                if (resp.status === 401) { window.location.href = '/login'; return { items: [], total:0, page:1, size:size, totalPages:1 }; }
                if (!resp.ok) { showMessage('脅威度の取得に失敗しました'); return { items: [], total:0, page:1, size:size, totalPages:1 }; }
                const data = await resp.json();
                STATE.canOperate = data && data.canOperate === true;
                STATE.total = data && data.total ? data.total : 0;
                return {
                    items: data && data.items ? data.items : [],
                    total: data && data.total ? data.total : 0,
                    page: data && data.page ? data.page : page,
                    size: data && data.size ? data.size : size,
                    totalPages: (data && data.totalPages) ? data.totalPages : Math.max(1, Math.ceil((data && data.total ? data.total : 0) / (data && data.size ? data.size : size)))
                };
            },
            renderRows: renderRows,
            onStateChange: function(state){ renderCount(state, STATE.lastVisible); updateMiniMenuVisibility(); showMessage(''); }
        });
        if (listView) {
            if (!listView.state.sort) listView.state.sort = 'priority';
            if (!listView.state.order) listView.state.order = 'asc';
            listView.init();
        }
        return listView;
    }

    async function init() {
        parseInitialFromUrl();
        const servers = await fetchServers(STATE.server);
        populateServers(servers, STATE.server);
        listViewRef = createListView();
        bindEvents(listViewRef);
    }

    window.UrlThreat = { init };
})();
