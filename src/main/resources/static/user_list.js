(function(){
    'use strict';

    function escapeHtmlLocal(s) { if (!s) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    function renderError(msg) {
        const resultsBody = document.getElementById('user-results-body'); if (resultsBody) resultsBody.innerHTML = '<tr><td colspan="4">' + escapeHtmlLocal(msg) + '</td></tr>';
        const pagination = document.getElementById('pagination'); if (pagination) pagination.innerHTML = '';
    }

    function attachCreateButton(){
        try {
            const searchForm = document.getElementById('user-search-form');
            if (searchForm && !document.getElementById('user-create-btn')) {
                const wrapper = document.createElement('div');
                wrapper.style.display = 'flex'; wrapper.style.justifyContent = 'flex-end'; wrapper.style.marginBottom = '0.5rem'; wrapper.style.width = '100%';
                const btn = document.createElement('button'); btn.id = 'user-create-btn'; btn.type = 'button'; btn.textContent = 'ユーザー作成'; btn.className = 'btn btn-primary';
                btn.style.display = 'inline-block'; btn.style.padding = '.4rem .6rem';
                btn.addEventListener('click', () => { if (window.UserModal && typeof window.UserModal.openCreateUserModal === 'function') window.UserModal.openCreateUserModal(); });
                wrapper.appendChild(btn);
                if (searchForm.parentNode) searchForm.parentNode.insertBefore(wrapper, searchForm);
            }
        } catch (e) { console.error('[UserList] create button setup error', e); }
    }

    function createListViewInstance(){
        const qInput = document.getElementById('q');
        const pagerEl = document.getElementById('pagination');
        const headerSelector = '#user-results th[data-column]';

        if (!window.ListViewCore || typeof window.ListViewCore.createListView !== 'function') {
            if (typeof window.loadScript === 'function') {
                try { window.loadScript('/static/list_view_core.js').catch(()=>{}); } catch(e) {}
            }
            return null;
        }

        const listView = window.ListViewCore.createListView({
            headerSelector,
            pagerEl,
            searchInput: qInput,
            applyStateToUi: (state) => { if (qInput) qInput.value = state.q || ''; },
            extractFilters: () => ({}),
            fetcher: async (params) => {
                const sp = new URLSearchParams();
                if (params.q) sp.append('q', params.q);
                sp.append('page', params.page ? params.page : 1);
                sp.append('size', params.size ? params.size : 20);
                if (params.sort) sp.append('sort', params.sort);
                if (params.order) sp.append('order', params.order);
                const url = '/api/users?' + sp.toString();
                const resp = await fetch(url, { method: 'GET', credentials: 'same-origin' });
                if (!resp.ok) { renderError('エラー(' + resp.status + ')'); throw new Error('fetch failed'); }
                const data = await resp.json();
                return {
                    items: data.users || [],
                    total: data && data.total ? data.total : 0,
                    page: data && data.page ? data.page : (params.page ? params.page : 1),
                    size: data && data.size ? data.size : (params.size ? params.size : 20),
                    totalPages: (data && data.totalPages) ? data.totalPages : (Math.ceil(((data && data.total) ? data.total : 0) / ((data && data.size) ? data.size : (params.size ? params.size : 20))) || 1)
                };
            },
            renderRows: (users, state) => {
                const resultsBody = document.getElementById('user-results-body');
                if (!resultsBody) return;
                if (!users || users.length === 0) { resultsBody.innerHTML = '<tr><td colspan="4">該当するユーザーが見つかりません</td></tr>'; return; }
                const sortKey = state.sort || 'username';
                const dir = state.order === 'desc' ? -1 : 1;
                const arr = users.slice().sort((a,b)=>{
                    let va = a[sortKey]; let vb = b[sortKey];
                    if (sortKey === 'lastLogin') { va = va ? new Date(va).getTime() : 0; vb = vb ? new Date(vb).getTime() : 0; }
                    else if (sortKey === 'enabled') { va = a.enabled ? 1 : 0; vb = b.enabled ? 1 : 0; }
                    else { va = (va||'').toString().toLowerCase(); vb = (vb||'').toString().toLowerCase(); }
                    if (va < vb) return -1 * dir; if (va > vb) return 1 * dir; return 0;
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
            }
        });

        if (listView) {
            // 初期ソートを設定（クエリに無い場合）
            if (!listView.state.sort) listView.state.sort = 'username';
            listView.init();
            return listView;
        }
        console.warn('[UserList] ListViewCore not available');
        return null;
    }

    function initUserManagement(){
        const MAX_RETRY = 30;
        let attempt = 0;
        function tryInit(){
            attempt++;
            const qInput = document.getElementById('q');
            const resultsBody = document.getElementById('user-results-body');
            const pagination = document.getElementById('pagination');
            const listViewAvailable = window.ListViewCore && typeof window.ListViewCore.createListView === 'function';
            if (!qInput || !resultsBody || !pagination || !listViewAvailable) {
                if (!listViewAvailable && typeof window.loadScript === 'function') {
                    try { window.loadScript('/static/list_view_core.js').catch(()=>{}); } catch(e) {}
                }
                if (attempt < MAX_RETRY) { setTimeout(tryInit, 100); return; }
                console.warn('[UserList] initUserManagement: required DOM or ListViewCore not ready', { q:!!qInput, results:!!resultsBody, pagination:!!pagination, listCore:listViewAvailable });
                return;
            }
            attachCreateButton();
            createListViewInstance();
        }
        tryInit();
    }

    window.UserList = { initUserManagement, renderError };
})();
