(function(){
    'use strict';

    // UserList モジュール: 検索、一覧描画、ページング
    function escapeHtmlLocal(s) { if (!s) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    let currentUsers = [];
    let currentTotal = 0;
    let currentPage = 1;
    let currentSize = 20;
    let sortColumn = 'username';
    let sortDir = 1;

    async function doSearch(page=1) {
        console.debug('[UserList] doSearch called', { page });
        const qInput = document.getElementById('q');
        if (!qInput) { console.warn('[UserList] doSearch: q input not found'); return; }
        const q = (qInput.value || '').trim();
        const url = q === '' ? ('/api/users?page=' + page + '&size=' + currentSize) : ('/api/users?q=' + encodeURIComponent(q) + '&page=' + page + '&size=20');
        console.debug('[UserList] fetching', { url });
        try {
            const resp = await fetch(url, { method: 'GET', credentials: 'same-origin' });
            console.debug('[UserList] fetch response', { ok: resp.ok, status: resp.status });
            if (!resp.ok) { renderError('エラー(' + resp.status + ')'); return; }
            const data = await resp.json();
            console.debug('[UserList] fetch json', { users: Array.isArray(data.users) ? data.users.length : typeof data.users, total: data.total });
            render(data.users || [], data.total || 0, data.page || 1, data.size || 20);
        } catch (e) { console.error('[UserList] fetch error', e); renderError('通信エラー'); }
    }

    function render(users, total, page, size) {
        currentUsers = users || [];
        currentTotal = total || 0;
        currentPage = page || 1;
        currentSize = size || 20;
        const resultsBody = document.getElementById('user-results-body');
        const pagination = document.getElementById('pagination');
        if (!resultsBody || !pagination) return;
        if (!currentUsers || currentUsers.length === 0) { resultsBody.innerHTML = '<tr><td colspan="4">該当するユーザーが見つかりません</td></tr>'; pagination.innerHTML = ''; return; }
        // sort
        const arr = currentUsers.slice();
        arr.sort((a,b) => {
            let va = a[sortColumn]; let vb = b[sortColumn];
            if (sortColumn === 'lastLogin') { va = va ? new Date(va).getTime() : 0; vb = vb ? new Date(vb).getTime() : 0; }
            else if (sortColumn === 'enabled') { va = a.enabled ? 1 : 0; vb = b.enabled ? 1 : 0; }
            else { va = (va||'').toString().toLowerCase(); vb = (vb||'').toString().toLowerCase(); }
            if (va < vb) return -1 * sortDir; if (va > vb) return 1 * sortDir; return 0;
        });
        resultsBody.innerHTML = '';
        for (const u of arr) {
            const last = u.lastLogin ? new Date(u.lastLogin).toLocaleString() : '';
            const tr = document.createElement('tr');
            tr.innerHTML = '<td>' + escapeHtmlLocal(u.username) + '</td><td>' + escapeHtmlLocal(u.email) + '</td><td>' + escapeHtmlLocal(last) + '</td><td>' + (u.enabled ? 'はい' : 'いいえ') + '</td>';
            tr.style.cursor = 'pointer';
            tr.addEventListener('click', () => { if (window.UserModal && typeof window.UserModal.openUserModal === 'function') window.UserModal.openUserModal(u); });
            resultsBody.appendChild(tr);
        }
        renderPagination(currentTotal, currentPage, currentSize);
    }

    function renderPagination(total, page, size) {
        const pagination = document.getElementById('pagination'); if (!pagination) return;
        pagination.innerHTML = '';
        const totalPages = Math.max(1, Math.ceil(total / size));
        const prev = document.createElement('button'); prev.textContent = '前へ'; prev.disabled = page <= 1; prev.addEventListener('click', () => doSearch(page - 1));
        const next = document.createElement('button'); next.textContent = '次へ'; next.disabled = page >= totalPages; next.addEventListener('click', () => doSearch(page + 1));
        const info = document.createElement('span'); info.textContent = ' ページ ' + page + ' / ' + totalPages + ' （合計 ' + total + ' 件） ';
        pagination.appendChild(prev); pagination.appendChild(info); pagination.appendChild(next);
    }

    function renderError(msg) {
        const resultsBody = document.getElementById('user-results-body'); if (resultsBody) resultsBody.innerHTML = '<tr><td colspan="4">' + escapeHtmlLocal(msg) + '</td></tr>';
        const pagination = document.getElementById('pagination'); if (pagination) pagination.innerHTML = '';
    }

    function attachHeaderSortHandlers() {
        document.querySelectorAll('th[data-column]').forEach(th => {
            th.__edamame_click__ && th.removeEventListener('click', th.__edamame_click__);
            th.__edamame_click__ = function() { const col = th.getAttribute('data-column'); if (sortColumn === col) sortDir = -sortDir; else { sortColumn = col; sortDir = 1; } render(currentUsers, currentTotal, currentPage, currentSize); };
            th.addEventListener('click', th.__edamame_click__);
        });
    }

    // 初期化: 検索入力・作成ボタン・イベントを設定
    function initUserManagement(initialQ) {
        // 要素がまだ存在しない場合があるためリトライを行う（遅延に強くする）
        const MAX_RETRY = 20;
        let attempt = 0;
        function tryInit() {
            attempt++;
            console.debug('[UserList] initUserManagement attempt', attempt, { initialQ });
            const qInput = document.getElementById('q');
            const form = document.getElementById('user-search-form');
            const resultsBody = document.getElementById('user-results-body');
            const pagination = document.getElementById('pagination');
            if (!qInput || !resultsBody || !pagination) {
                if (attempt < MAX_RETRY) { setTimeout(tryInit, 100); return; }
                console.warn('[UserList] initUserManagement: required DOM elements not found', { q:!!qInput, results:!!resultsBody, pagination:!!pagination });
                return;
            }

            if (initialQ) qInput.value = initialQ;

            // デバウンス
            let debounceTimer = null; const DEBOUNCE_MS = 250;
            function scheduleSearch() { if (debounceTimer) clearTimeout(debounceTimer); debounceTimer = setTimeout(() => { console.debug('[UserList] scheduleSearch -> doSearch'); doSearch(1); }, DEBOUNCE_MS); }

            if (form) { form.addEventListener('submit', e => { e.preventDefault(); }); }
            qInput.addEventListener('input', scheduleSearch);
            qInput.addEventListener('keydown', function(e){ if (e.key === 'Enter') { e.preventDefault(); doSearch(1); } });

            // 新規作成ボタンを検索フォームの上・右端に追加
            try {
                const searchForm = document.getElementById('user-search-form');
                console.debug('[UserList] searchForm element', !!searchForm);
                if (searchForm && !document.getElementById('user-create-btn')) {
                    const wrapper = document.createElement('div');
                    wrapper.style.display = 'flex'; wrapper.style.justifyContent = 'flex-end'; wrapper.style.marginBottom = '0.5rem'; wrapper.style.width = '100%';
                    const btn = document.createElement('button'); btn.id = 'user-create-btn'; btn.type = 'button'; btn.textContent = 'ユーザー作成'; btn.className = 'btn btn-primary';
                    btn.style.display = 'inline-block'; btn.style.padding = '.4rem .6rem';
                    btn.addEventListener('click', () => { console.debug('[UserList] create button clicked'); if (window.UserModal && typeof window.UserModal.openCreateUserModal === 'function') window.UserModal.openCreateUserModal(); });
                    wrapper.appendChild(btn);
                    // insert wrapper before the search form so it appears above
                    if (searchForm.parentNode) searchForm.parentNode.insertBefore(wrapper, searchForm);
                    console.debug('[UserList] create button inserted', { wrapper, btn });
                }
            } catch (e) { console.error('[UserList] initUserManagement button setup error', e); }

            attachHeaderSortHandlers();
            doSearch(1);
        }
        tryInit();
    }

    window.UserList = { initUserManagement, doSearch, render, renderError };
})();
