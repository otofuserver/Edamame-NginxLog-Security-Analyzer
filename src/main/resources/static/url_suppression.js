(function(){
    'use strict';

    const STATE = { canEdit: false, currentItem: null };
    let miniMenu = null;
    let listViewRef = null;
    let initialServer = '';

    function $(id){ return document.getElementById(id); }

    function escapeHtml(s){ if (s === null || s === undefined) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    function formatDateTime(val){
        if (!val) return '';
        try {
            const d = new Date(val);
            if (isNaN(d.getTime())) return escapeHtml(val);
            return d.toLocaleString('ja-JP', { year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', second:'2-digit', hour12:false });
        } catch(e){ return escapeHtml(val); }
    }

    async function fetchServers() {
        try {
            const resp = await fetch('/api/servers?size=200', { method:'GET', credentials:'same-origin' });
            if (!resp.ok) return [];
            const data = await resp.json();
            return data.servers || [];
        } catch (e) { return []; }
    }

    function fillServerSelect(selectEl, servers, opts) {
        if (!selectEl) return;
        const current = selectEl.value;
        const options = opts || { includeEmpty: true, includeAll: false };
        selectEl.innerHTML = '';
        const addOpt = function(val,label){ var o=document.createElement('option'); o.value=val; o.textContent=label; selectEl.appendChild(o); };
        if (options.includeEmpty) addOpt('', 'すべて');
        if (options.includeAll) addOpt('all','all (全体)');
        (servers||[]).filter(function(s){ return s.isActive !== false; }).forEach(function(s){ addOpt(s.serverName || '', s.serverName || ''); });
        if (current) selectEl.value = current;
        if (initialServer) selectEl.value = initialServer;
    }

    async function loadServersToControls(){
        const servers = await fetchServers();
        fillServerSelect($('url-suppression-server'), servers, { includeEmpty: true, includeAll: false });
        fillServerSelect($('url-suppression-server-input'), servers, { includeEmpty: true, includeAll: false });
    }

    function renderRows(items, state){
        const tbody = $('url-suppression-body'); if (!tbody) return;
        if (!items || !items.length){ tbody.innerHTML = '<tr><td colspan="6">データがありません</td></tr>'; return; }
        const sortKey = state.sort || 'updatedAt';
        const dir = state.order === 'asc' ? 1 : -1;
        const sorted = items.slice().sort(function(a,b){
            var va = a[sortKey]; var vb = b[sortKey];
            if (va === null || va === undefined) va=''; if (vb === null || vb === undefined) vb='';
            if (typeof va === 'string') va = va.toLowerCase(); if (typeof vb === 'string') vb = vb.toLowerCase();
            if (va < vb) return -1 * dir; if (va > vb) return 1 * dir; return 0;
        });
        tbody.innerHTML='';
        sorted.forEach(function(item){
            const tr=document.createElement('tr');
            if (!item.isEnabled) tr.classList.add('inactive');
            tr.dataset.id=item.id;
            tr.dataset.server=item.serverName||'';
            tr.dataset.enabled=item.isEnabled?'true':'false';
            tr.dataset.pattern=item.urlPattern||'';
            tr.dataset.description=item.description||'';
            tr.innerHTML='<td>'+escapeHtml(item.serverName||'')+'</td>'+
                '<td class="mono">'+escapeHtml(item.urlPattern||'')+'</td>'+
                '<td>'+(item.isEnabled?'有効':'無効')+'</td>'+
                '<td>'+formatDateTime(item.lastAccessAt)+'</td>'+
                '<td>'+(item.dropCount?item.dropCount:0)+'</td>'+
                '<td>'+formatDateTime(item.updatedAt)+'</td>';
            tr.addEventListener('click', function(ev){ handleRowClick(ev, item); });
            tbody.appendChild(tr);
        });
        updateSortIndicators(state);
    }

    function renderError(msg){ const tbody=$('url-suppression-body'); if (tbody) tbody.innerHTML='<tr><td colspan="6">'+escapeHtml(msg)+'</td></tr>'; }

    function setupMiniMenu(){ const menu=$('url-suppression-mini-menu'); miniMenu = window.MiniMenu?window.MiniMenu.create(menu):null; }

    function handleRowClick(ev, item){ ev.stopPropagation(); STATE.currentItem = item; showMiniMenu(ev); }

    function showMiniMenu(ev){
        if (!miniMenu || !STATE.currentItem) return;
        const item = STATE.currentItem;
        const canEdit = STATE.canEdit;
        const enabled = item.isEnabled === true;
        const items = [
            {label: enabled ? '無効化' : '有効化', hidden: !canEdit, onClick: function(){ toggle(item, !enabled); }},
            {label:'編集', hidden:!canEdit, onClick:function(){ openModal(item); }},
            {label:'削除', hidden:!canEdit, onClick:function(){ removeItem(item); }}
         ];
        miniMenu.show({x: ev.pageX ? ev.pageX : ev.clientX, y: ev.pageY ? ev.pageY : ev.clientY, items: items});
    }

    async function toggle(item, enabled){ await saveRule(Object.assign({}, item, { isEnabled: enabled })); }

    async function removeItem(item){
        if (!confirm('削除しますか？')) return;
        try {
            const resp = await fetch('/api/url-suppressions/'+encodeURIComponent(item.id), { method:'DELETE', credentials:'same-origin' });
            if (!resp.ok) { alert('削除に失敗しました'); return; }
            if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page);
        } catch (e){ alert('通信エラー'); }
    }

    function openModal(item){
        const modal=$('url-suppression-modal'); if (!modal) return;
        const canEdit = STATE.canEdit;
        $('url-suppression-id').value = item && item.id ? item.id : '';
        $('url-suppression-server-input').value = item && item.serverName ? item.serverName : 'all';
        $('url-suppression-pattern').value = item && item.urlPattern ? item.urlPattern : '';
        $('url-suppression-desc').value = item && item.description ? item.description : '';
        $('url-suppression-enabled').checked = item ? !!item.isEnabled : true;
        $('url-suppression-delete').style.display = item && canEdit ? 'inline-block' : 'none';
        $('url-suppression-error').style.display='none';
        modal.setAttribute('aria-hidden','false');
        modal.style.display='flex';
    }

    function closeModal(){ const modal=$('url-suppression-modal'); if (!modal) return; modal.setAttribute('aria-hidden','true'); modal.style.display='none'; }

    async function saveFromModal(){
        const id = $('url-suppression-id').value;
        const payload = {
            serverName: $('url-suppression-server-input').value || 'all',
            urlPattern: $('url-suppression-pattern').value.trim(),
            description: $('url-suppression-desc').value,
            isEnabled: $('url-suppression-enabled').checked
        };
        if (!payload.urlPattern){ showError('url-suppression-error','正規表現を入力してください'); return; }
        await saveRule(Object.assign({}, payload, { id: id ? Number(id) : null }));
        closeModal();
    }

    async function saveRule(rule){
        const isUpdate = !!rule.id;
        const path = isUpdate ? '/api/url-suppressions/'+encodeURIComponent(rule.id) : '/api/url-suppressions';
        const method = isUpdate ? 'PUT' : 'POST';
        try {
            const resp = await fetch(path, { method: method, credentials:'same-origin', headers:{'Content-Type':'application/json'}, body: JSON.stringify(rule) });
            if (!resp.ok){ const txt=await resp.text(); alert('保存に失敗しました: '+txt); return; }
            if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page);
        } catch (e){ alert('通信エラー'); }
    }

    function showError(id,msg){ const el=$(id); if (!el) return; el.textContent=msg||''; el.style.display='block'; }

    function updateSortIndicators(state){
        document.querySelectorAll('#url-suppression-results th[data-column]').forEach(function(th){
            const col=th.getAttribute('data-column');
            const active=col===state.sort; const arrow=active ? (state.order==='asc'?' ▲':' ▼') : '';
            th.textContent=(th.dataset.labelOriginal||th.textContent.replace(/[ ▲▼]+$/,''))+(arrow);
            th.dataset.labelOriginal = th.dataset.labelOriginal || th.textContent.replace(/[ ▲▼]+$/,'');
            th.classList.toggle('active-sort', active);
        });
    }

    function applyPermissions(canEditOverride){
        const meta = $('url-suppression-meta');
        const canEditMeta = meta && meta.dataset && meta.dataset.canEdit === 'true';
        const canEdit = (canEditOverride !== undefined) ? canEditOverride : canEditMeta;
        STATE.canEdit = !!canEdit;
        const createBtn = $('url-suppression-create');
        if (createBtn) createBtn.style.display = canEdit ? 'inline-block' : 'none';
    }

    function setupCreateButton(){
        const controls = document.getElementById('url-suppression-controls');
        const btn = document.getElementById('url-suppression-create');
        if (!controls || !btn || document.getElementById('url-suppression-create-wrapper')) return;
        const wrapper = document.createElement('div');
        wrapper.id = 'url-suppression-create-wrapper';
        wrapper.style.display = 'flex';
        wrapper.style.justifyContent = 'flex-end';
        wrapper.style.marginBottom = '0.5rem';
        wrapper.style.width = '100%';
        btn.classList.add('btn','btn-primary');
        btn.style.display = 'inline-block';
        btn.style.padding = '.4rem .6rem';
        if (controls.parentNode) {
            controls.parentNode.insertBefore(wrapper, controls);
            wrapper.appendChild(btn);
        }
    }

    function attachEvents(){
        document.addEventListener('click', function(){ if (miniMenu) miniMenu.hide(); });
        const createBtn = $('url-suppression-create');
        if (createBtn) createBtn.addEventListener('click', function(){ openModal(null); });
        const saveBtn = $('url-suppression-save');
        if (saveBtn) saveBtn.addEventListener('click', function(){ saveFromModal(); });
        const cancelBtn = $('url-suppression-cancel');
        if (cancelBtn) cancelBtn.addEventListener('click', function(){ closeModal(); });
        const deleteBtn = $('url-suppression-delete');
        if (deleteBtn) deleteBtn.addEventListener('click', function(){
            const id=$('url-suppression-id').value; if (!id) { closeModal(); return; }
            if (!confirm('削除しますか？')) return;
            fetch('/api/url-suppressions/'+encodeURIComponent(id), { method:'DELETE', credentials:'same-origin' })
                .then(function(r){ if(!r.ok) { r.text().then(function(t){ alert('削除に失敗: '+t); }); return; } closeModal(); if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page); })
                .catch(function(){ alert('通信エラー'); });
        });
    }

    function parseInitialServer(){
        try {
            const params = new URLSearchParams(window.location.search);
            initialServer = params.get('server') || '';
        } catch(e) { initialServer = ''; }
    }

    function createListView(){
        const qInput = $('url-suppression-q');
        const pagerEl = $('url-suppression-pager');
        const headerSelector = '#url-suppression-results th[data-column]';
        if (!window.ListViewCore || typeof window.ListViewCore.createListView !== 'function') {
            if (typeof window.loadScript === 'function') { try { window.loadScript('/static/list_view_core.js').catch(function(){}); } catch(e){} }
            return null;
        }
        const serverSelect = $('url-suppression-server');
        const listView = window.ListViewCore.createListView({
            headerSelector: headerSelector,
            pagerEl: pagerEl,
            searchInput: qInput,
            applyStateToUi: function(state){
                if (qInput) qInput.value = state.q || '';
                if (serverSelect && initialServer) serverSelect.value = initialServer;
            },
            extractFilters: function(){ return { server: serverSelect ? serverSelect.value : '' }; },
            fetcher: async function(params){
                const sp = new URLSearchParams();
                if (params.q) sp.append('q', params.q);
                if (params.server) sp.append('server', params.server);
                sp.append('sort', params.sort ? params.sort : 'updatedAt');
                sp.append('order', params.order ? params.order : 'desc');
                sp.append('page', params.page ? params.page : 1);
                sp.append('size', params.size ? params.size : 20);
                const url = '/api/url-suppressions?' + sp.toString();
                const resp = await fetch(url, { method:'GET', credentials:'same-origin' });
                if (!resp.ok) { renderError('取得に失敗しました'); throw new Error('fetch failed'); }
                const data = await resp.json();
                STATE.canEdit = data && data.canEdit === true;
                applyPermissions(STATE.canEdit);
                return {
                    items: data && data.items ? data.items : [],
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
            if (!listView.state.sort) listView.state.sort = 'updatedAt';
            listView.init();
        }
        return listView;
    }

    async function init(){
        parseInitialServer();
        setupMiniMenu();
        setupCreateButton();
        attachEvents();
        await loadServersToControls();
        listViewRef = createListView();
        const serverSelect = $('url-suppression-server');
        if (serverSelect && listViewRef && listViewRef.reload) {
            serverSelect.addEventListener('change', function(){ listViewRef.reload(1); });
        }
        applyPermissions();
    }

    window.UrlSuppression = { init };
})();
