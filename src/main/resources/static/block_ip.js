(function(){
    'use strict';

    const STATE = { status: 'ACTIVE', serviceType: 'all', q: '', canOperate: false, currentItem: null };
    let listViewRef = null;
    let miniMenu = null;
    let cleanupPollTimer = null;
    let lastCleanupVersion = null;
    let agentCandidatesLoaded = false;
    const CLEANUP_POLL_MS = 15000; // クリーンアップ完了チェック間隔
    const AGENT_DATALIST_ID = 'block-ip-agent-list';

    function $(id){ return document.getElementById(id); }
    function escapeHtml(s){ if (s === null || s === undefined) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }
    function formatDateTime(val){ if (!val) return ''; try { const d = new Date(val); if (isNaN(d.getTime())) return escapeHtml(val); return d.toLocaleString('ja-JP',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}); } catch(e){ return escapeHtml(val); } }

    function showMessage(msg){ const el = $('block-ip-message'); if (!el) return; el.textContent = msg || ''; el.style.display = msg ? 'block' : 'none'; }

    function renderCount(state){ const el = $('block-ip-count'); if (!el) return; const total = state && state.total ? state.total : 0; const page = state && state.page ? state.page : 1; const size = state && state.size ? state.size : 20; const from = total === 0 ? 0 : ((page-1)*size)+1; const to = Math.min(total, page*size); el.textContent = total === 0 ? '0 件' : (from + '〜' + to + ' / ' + total + ' 件'); }

    function setupMiniMenu(){ const menu = $('block-ip-mini-menu'); miniMenu = window.MiniMenu ? window.MiniMenu.create(menu) : null; }

    function hydratePermissionFromMeta(){
        const meta = document.getElementById('block-ip-meta');
        if (meta && meta.dataset && meta.dataset.canOperate === 'true') {
            STATE.canOperate = true;
        }
    }

    function handleRowClick(ev, item){ ev.stopPropagation(); STATE.currentItem = item; showMiniMenu(ev); }

    function showMiniMenu(ev){
        if (!miniMenu || !STATE.currentItem) return;
        const items = [];
        if (STATE.currentItem.status === 'ACTIVE') {
            items.push({ label:'無効化', requirePermission:true, onClick:function(){ openDisableModal(STATE.currentItem); } });
        } else {
            items.push({ label:'有効化', requirePermission:true, onClick:function(){ openActivateModal(STATE.currentItem); } });
        }
        items.push({ label:'編集', requirePermission:true, onClick:function(){ openEditModal(STATE.currentItem); } });
        items.push({ label:'削除', requirePermission:true, onClick:function(){ openDeleteModal(STATE.currentItem); } });
        miniMenu.show({ x: ev.pageX || ev.clientX, y: ev.pageY || ev.clientY, items: items, canOperate: STATE.canOperate });
    }

    function renderRows(items, state){ const tbody = $('block-ip-body'); if (!tbody) return; if (!items || !items.length){ tbody.innerHTML = '<tr><td colspan="8">データがありません</td></tr>'; return; } tbody.innerHTML=''; items.forEach(function(item){ const tr=document.createElement('tr'); tr.dataset.id=item.id; tr.innerHTML = '<td class="mono">'+escapeHtml(item.ipAddress)+'</td>'+
            '<td>'+escapeHtml(labelService(item.serviceType))+'</td>'+
            '<td>'+escapeHtml(item.targetAgentName||'')+'</td>'+
            '<td>'+escapeHtml(labelStatus(item.status))+'</td>'+
            '<td>'+formatDateTime(item.startAt)+'</td>'+
            '<td>'+formatDateTime(item.endAt)+'</td>'+
            '<td>'+escapeHtml(item.reason||'')+'</td>'+
            '<td>'+formatDateTime(item.updatedAt||item.createdAt)+'</td>';
        tr.addEventListener('click', function(ev){ handleRowClick(ev, item); });
        tbody.appendChild(tr);
    }); updateSortIndicators(state); }

    function labelService(type){ switch(type){ case 'MONITOR_BLOCK': return 'モニターブロック'; case 'APP_LOGIN': return 'アプリログイン'; case 'MANUAL': return 'マニュアル'; default: return type || ''; } }
    function labelStatus(st){ switch(st){ case 'ACTIVE': return '有効'; case 'EXPIRED': return '期限切れ'; case 'REVOKED': return '無効'; default: return st || ''; } }

    function updateSortIndicators(state){ document.querySelectorAll('#block-ip-table th.sortable').forEach(function(th){ const key=th.dataset.sort||''; const active=key===(state.sort||''); const base=th.dataset.labelOriginal||th.textContent.replace(/[ ▲▼]+$/,''); th.dataset.labelOriginal = base; const arrow = active ? (state.order==='desc'?' ▼':' ▲') : ''; th.classList.toggle('active-sort', active); th.textContent = base + arrow; }); }

    function createListView(){ const pagerEl=$('block-ip-pager'); const qInput=$('block-ip-q'); if (!window.ListViewCore || typeof window.ListViewCore.createListView!=='function'){ if (window.loadScript){ try { window.loadScript('/static/list_view_core.js').catch(function(){}); } catch(e){} } return null; }
        const listView = window.ListViewCore.createListView({
            headerSelector: '#block-ip-table th.sortable',
            pagerEl: pagerEl,
            searchInput: qInput,
            defaultSize: 20,
            extractFilters: function(){ return { status: STATE.status, serviceType: STATE.serviceType }; },
            applyStateToUi: applyStateToUi,
            applyFiltersFromUrl: applyFiltersFromUrl,
            fetcher: fetchBlockIp,
            renderRows: renderRows,
            onStateChange: function(state){ renderCount(state); showMessage(''); }
        });
        if (listView){ if (!listView.state.sort) listView.state.sort='ipAddress'; if (!listView.state.order) listView.state.order='asc'; listView.init(); }
        return listView;
    }

    async function fetchBlockIp(params){ const sp = new URLSearchParams(); const status = params.status || STATE.status || 'ACTIVE'; const service = params.serviceType || STATE.serviceType || 'all'; const page = params.page || 1; const size = params.size || 20; if (status) sp.append('status', status); if (service && service !== 'all') sp.append('serviceType', service); if (params.q) sp.append('q', params.q); if (params.sort) sp.append('sort', params.sort); if (params.order) sp.append('order', params.order); sp.append('page', page); sp.append('size', size); STATE.status = status; STATE.serviceType = service; showMessage('読み込み中...');
        try {
            const resp = await fetch('/api/block-ip?' + sp.toString(), { method:'GET', credentials:'same-origin', headers:{'Accept':'application/json'} });
            if (resp.status===401){ window.location.href='/login'; return { items:[], total:0, page:1, size:size, totalPages:1 }; }
            if (!resp.ok){ showMessage('取得に失敗しました'); return { items:[], total:0, page:1, size:size, totalPages:1 }; }
            const data = await resp.json();
            STATE.canOperate = data && data.canOperate === true;
            return { items: data.items || [], total: data.total || 0, page: data.page || page, size: data.size || size, totalPages: data.totalPages || Math.max(1, Math.ceil((data.total||0)/(data.size||size))) };
        } catch(e){ showMessage('通信エラーが発生しました'); return { items:[], total:0, page:1, size:size, totalPages:1 }; }
    }

    function applyStateToUi(state){ const qInput=$('block-ip-q'); if (qInput && state && state.q!==undefined) qInput.value=state.q;
        const statusRadios=document.querySelectorAll('input[name="block-ip-status"]'); statusRadios.forEach(function(r){ if (state && state.status!==undefined) r.checked = (r.value===state.status); else r.checked = (r.value===STATE.status); });
        const svcRadios=document.querySelectorAll('input[name="block-ip-service"]'); svcRadios.forEach(function(r){ if (state && state.serviceType!==undefined) r.checked = (r.value===state.serviceType); else r.checked = (r.value===STATE.serviceType); });
    }

    function applyFiltersFromUrl(params, state){
        const status = params.get('status');
        const service = params.get('serviceType');
        if (status) { STATE.status = status; if (state) state.status = status; }
        if (service) { STATE.serviceType = service; if (state) state.serviceType = service; }
    }

    function bindFilters(listView){ document.querySelectorAll('input[name="block-ip-status"]').forEach(function(radio){ radio.addEventListener('change', function(){ STATE.status = radio.value || 'ACTIVE'; if (listView) listView.reload(1); }); });
        document.querySelectorAll('input[name="block-ip-service"]').forEach(function(radio){ radio.addEventListener('change', function(){ STATE.serviceType = radio.value || 'all'; if (listView) listView.reload(1); }); });
        const qInput=$('block-ip-q'); if (qInput && listView){ qInput.addEventListener('keydown', function(e){ if (e.key==='Enter'){ e.preventDefault(); listView.reload(1); } }); }
    }

    function openDeleteModal(item){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-delete-modal');
        const target=$('block-ip-delete-target');
        if (target) target.textContent = 'IP: ' + (item.ipAddress||'') + ' / 種別: ' + labelService(item.serviceType);
        if (backdrop) backdrop.classList.remove('hidden');
        if (modal) modal.classList.remove('hidden');
    }
    function closeDeleteModal(){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-delete-modal');
        if (backdrop) backdrop.classList.add('hidden');
        if (modal) modal.classList.add('hidden');
    }

    function openDisableModal(item){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-disable-modal');
        const target=$('block-ip-disable-target');
        if (target) target.textContent = 'IP: ' + (item.ipAddress||'') + ' / 種別: ' + labelService(item.serviceType);
        if (backdrop) backdrop.classList.remove('hidden');
        if (modal) modal.classList.remove('hidden');
    }
    function closeDisableModal(){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-disable-modal');
        if (backdrop) backdrop.classList.add('hidden');
        if (modal) modal.classList.add('hidden');
    }

    function openActivateModal(item){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-activate-modal');
        const target=$('block-ip-activate-target');
        if (target) target.textContent = 'IP: ' + (item.ipAddress||'') + ' / 種別: ' + labelService(item.serviceType);
        const endRow=$('block-ip-activate-end-row');
        const endInput=$('block-ip-activate-end');
        const needsEnd = !!(item && item.endAt && isPast(item.endAt));
        if (endRow) endRow.style.display = needsEnd ? 'block' : 'none';
        if (endInput){
            endInput.value = needsEnd ? '' : '';
            endInput.placeholder = needsEnd ? '終了が過去です。新しい終了時刻を入力（空欄で無期限）' : '';
        }
        if (backdrop) backdrop.classList.remove('hidden');
        if (modal) modal.classList.remove('hidden');
    }
    function closeActivateModal(){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-activate-modal');
        if (backdrop) backdrop.classList.add('hidden');
        if (modal) modal.classList.add('hidden');
    }

    function openEditModal(item){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-edit-modal');
        const target=$('block-ip-edit-target');
        if (target) target.textContent = 'IP: ' + (item.ipAddress||'') + ' / 種別: ' + labelService(item.serviceType);
        const agentInput=$('block-ip-edit-server');
        const endInput=$('block-ip-edit-end');
        const reasonInput=$('block-ip-edit-reason');
        if (agentInput) agentInput.value = item.targetAgentName || '';
        if (endInput) endInput.value = formatForInput(item.endAt);
        if (reasonInput) reasonInput.value = item.reason || '';
        const err=$('block-ip-edit-error'); if (err) err.style.display='none';
        if (backdrop) backdrop.classList.remove('hidden');
        if (modal) modal.classList.remove('hidden');
    }
    function closeEditModal(){
        const backdrop=$('block-ip-modal-backdrop');
        const modal=$('block-ip-edit-modal');
        if (backdrop) backdrop.classList.add('hidden');
        if (modal) modal.classList.add('hidden');
    }

    async function confirmDelete(){
        if (!STATE.currentItem) { closeDeleteModal(); return; }
        try {
            const resp = await fetch('/api/block-ip/' + encodeURIComponent(STATE.currentItem.id), { method:'DELETE', credentials:'same-origin' });
            if (resp.status===401){ window.location.href='/login'; return; }
            if (!resp.ok){ alert('削除に失敗しました'); return; }
            if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page || 1);
        } catch(e){
            alert('通信エラーが発生しました');
        } finally {
            closeDeleteModal();
        }
    }

    async function confirmDisable(){
        if (!STATE.currentItem) { closeDisableModal(); return; }
        try {
            const resp = await fetch('/api/block-ip/' + encodeURIComponent(STATE.currentItem.id) + '/revoke', { method:'POST', credentials:'same-origin' });
            if (resp.status===401){ window.location.href='/login'; return; }
            if (!resp.ok){ alert('無効化に失敗しました'); return; }
            if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page || 1);
        } catch(e){
            alert('通信エラーが発生しました');
        } finally {
            closeDisableModal();
        }
    }

    async function confirmActivate(){
        if (!STATE.currentItem) { closeActivateModal(); return; }
        try {
            const endRow=$('block-ip-activate-end-row');
            const endInput=$('block-ip-activate-end');
            const shouldSendEnd = endRow && endRow.style.display !== 'none';
            const endVal = endInput ? endInput.value : '';
            const payload = shouldSendEnd ? { endAt: endVal ? endVal : null } : {};
            const resp = await fetch('/api/block-ip/' + encodeURIComponent(STATE.currentItem.id) + '/activate', { method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/json','Accept':'application/json'}, body: JSON.stringify(payload) });
            if (resp.status===401){ window.location.href='/login'; return; }
            if (!resp.ok){ alert('有効化に失敗しました'); return; }
            if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page || 1);
        } catch(e){
            alert('通信エラーが発生しました');
        } finally {
            closeActivateModal();
        }
    }

    async function submitEdit(){
        if (!STATE.currentItem) { closeEditModal(); return; }
        const err=$('block-ip-edit-error');
        const agent = ($('block-ip-edit-server')?.value||'').trim();
        const endVal = $('block-ip-edit-end')?.value || '';
        const reason = ($('block-ip-edit-reason')?.value||'').trim();
        if (!reason){ showEditError('理由を入力してください'); return; }
        if (agent && !isValidTargetAgent(agent)) { showEditError('対象エージェントは英数字・ハイフン・アンダースコア・ドットのみ64文字以内で入力してください'); return; }
        if (endVal && isPast(endVal)) { showEditError('終了時刻は現在以降を指定してください'); return; }
        const payload = { targetAgentName: agent || null, endAt: endVal ? endVal : null, reason: reason };
        try {
            const resp = await fetch('/api/block-ip/' + encodeURIComponent(STATE.currentItem.id), { method:'PUT', credentials:'same-origin', headers:{'Content-Type':'application/json','Accept':'application/json'}, body: JSON.stringify(payload) });
            if (resp.status===401){ window.location.href='/login'; return; }
            if (!resp.ok){ const txt = await resp.text(); showEditError('更新に失敗しました: ' + (txt||'')); return; }
            if (listViewRef && listViewRef.reload) listViewRef.reload(listViewRef.state.page || 1);
            closeEditModal();
        } catch(e){ showEditError('通信エラーが発生しました'); }
    }

    function isPast(val){ if (!val) return false; try { const d = new Date(val); return d.getTime() < Date.now(); } catch(e){ return false; } }
    function formatForInput(val){ if (!val) return ''; try { const d = new Date(val); if (isNaN(d.getTime())) return ''; const pad=n=>String(n).padStart(2,'0'); return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+pad(d.getHours())+':'+pad(d.getMinutes()); } catch(e){ return ''; } }
    function isValidTargetAgent(val){ return /^[A-Za-z0-9._-]{1,64}$/.test(val); }

    function openCreateModal(){ const backdrop=$('block-ip-modal-backdrop'); const modal=$('block-ip-create-modal'); if (backdrop) backdrop.classList.remove('hidden'); if (modal) modal.classList.remove('hidden'); $('block-ip-create-error').style.display='none'; }
    function closeCreateModal(){ const backdrop=$('block-ip-modal-backdrop'); const modal=$('block-ip-create-modal'); if (backdrop) backdrop.classList.add('hidden'); if (modal) modal.classList.add('hidden'); }

    async function submitCreate(){ const ip = ($('block-ip-input-address').value||'').trim(); const target = ($('block-ip-input-server').value||'').trim(); const reason = ($('block-ip-input-reason').value||'').trim(); const endVal = $('block-ip-input-end').value; const errorEl = $('block-ip-create-error'); if (!ip){ showCreateError('IPアドレスを入力してください'); return; } if (!reason){ showCreateError('理由を入力してください'); return; }
        if (target && !isValidTargetAgent(target)) { showCreateError('対象エージェントは英数字・ハイフン・アンダースコア・ドットのみ64文字以内で入力してください'); return; }
        if (endVal && isPast(endVal)) { showCreateError('終了時刻は現在以降を指定してください'); return; }
        const payload = { ipAddress: ip, serviceType: 'MANUAL', targetAgentName: target || null, reason: reason, endAt: endVal ? endVal : null };
        try { const resp = await fetch('/api/block-ip', { method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/json','Accept':'application/json'}, body: JSON.stringify(payload) }); if (resp.status===401){ window.location.href='/login'; return; } if (!resp.ok){ const txt = await resp.text(); showCreateError('作成に失敗しました: ' + (txt||'')); return; } closeCreateModal(); if (listViewRef && listViewRef.reload) listViewRef.reload(1); } catch(e){ showCreateError('通信エラーが発生しました'); }
    }

    function showCreateError(msg){ const el=$('block-ip-create-error'); if (!el) return; el.textContent=msg||''; el.style.display='block'; }
    function showEditError(msg){ const el=$('block-ip-edit-error'); if (!el) return; el.textContent=msg||''; el.style.display='block'; }

    function bindModals(){
        const delCancel=$('block-ip-delete-cancel'); if (delCancel) delCancel.addEventListener('click', function(ev){ ev.stopPropagation(); closeDeleteModal(); });
        const delConfirm=$('block-ip-delete-confirm'); if (delConfirm) delConfirm.addEventListener('click', function(ev){ ev.stopPropagation(); confirmDelete(); });
        const disCancel=$('block-ip-disable-cancel'); if (disCancel) disCancel.addEventListener('click', function(ev){ ev.stopPropagation(); closeDisableModal(); });
        const disConfirm=$('block-ip-disable-confirm'); if (disConfirm) disConfirm.addEventListener('click', function(ev){ ev.stopPropagation(); confirmDisable(); });
        const actCancel=$('block-ip-activate-cancel'); if (actCancel) actCancel.addEventListener('click', function(ev){ ev.stopPropagation(); closeActivateModal(); });
        const actConfirm=$('block-ip-activate-confirm'); if (actConfirm) actConfirm.addEventListener('click', function(ev){ ev.stopPropagation(); confirmActivate(); });
        const editCancel=$('block-ip-edit-cancel'); if (editCancel) editCancel.addEventListener('click', function(ev){ ev.stopPropagation(); closeEditModal(); });
        const editSave=$('block-ip-edit-save'); if (editSave) editSave.addEventListener('click', function(ev){ ev.stopPropagation(); submitEdit(); });
        const createBtn=$('block-ip-create-btn'); if (createBtn) createBtn.addEventListener('click', function(ev){ ev.preventDefault(); openCreateModal(); });
        const createCancel=$('block-ip-create-cancel'); if (createCancel) createCancel.addEventListener('click', function(ev){ ev.stopPropagation(); closeCreateModal(); });
        const createSave=$('block-ip-create-save'); if (createSave) createSave.addEventListener('click', function(ev){ ev.stopPropagation(); submitCreate(); });
    }

    async function init(){
        // 60秒オートリロードは正式に廃止。クリーンアップポーリングのみで更新する。
        setupMiniMenu();
        listViewRef = createListView();
        bindFilters(listViewRef);
        bindModals();
        await loadAgentCandidates();
        bindAgentCandidateLoader();
        startCleanupPoller();
    }

    function startCleanupPoller(){
        if (cleanupPollTimer) clearInterval(cleanupPollTimer);
        const poll = async () => {
            try {
                const resp = await fetch('/api/block-ip/cleanup-status', { method:'GET', credentials:'same-origin', headers:{'Accept':'application/json'} });
                if (resp.status === 401) { return; }
                if (!resp.ok) { return; }
                const data = await resp.json();
                const ver = data && typeof data.version === 'number' ? data.version : null;
                if (ver !== null && ver !== lastCleanupVersion) {
                    if (lastCleanupVersion !== null && ver > lastCleanupVersion) {
                        window.dispatchEvent(new Event('block-ip:cleanup-done'));
                        if (listViewRef && listViewRef.reload) { listViewRef.reload(listViewRef.state.page || 1); }
                    }
                    lastCleanupVersion = ver;
                }
            } catch(e){ /* ignore polling errors */ }
        };
        poll();
        cleanupPollTimer = setInterval(poll, CLEANUP_POLL_MS);
    }

    function getAgentDatalist(){ return document.getElementById(AGENT_DATALIST_ID); }

    async function loadAgentCandidates(){
        if (agentCandidatesLoaded) { return; }
        try {
            const resp = await fetch('/api/block-ip/agents', { credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
            if (!resp.ok) { return; }
            const data = await resp.json();
            const items = Array.isArray(data) ? data : (Array.isArray(data?.items) ? data.items : []);
            const target = getAgentDatalist();
            if (!target) { return; }
            target.innerHTML = '';
            items.forEach(function(item){
                const value = (typeof item === 'string') ? item : (item && item.value ? item.value : '');
                if (!value) { return; }
                const label = (typeof item === 'string') ? value : (item.label || value);
                const opt = document.createElement('option');
                opt.value = value;
                opt.textContent = label;
                if (item && item.type) { opt.dataset.type = item.type; }
                target.appendChild(opt);
            });
            agentCandidatesLoaded = true;
        } catch(e) {
            // サジェストは補助機能のため、失敗時は黙殺する
        }
    }

    function bindAgentCandidateLoader(){
        const createInput = $('block-ip-input-server');
        const editInput = $('block-ip-edit-server');
        const handler = function(){ loadAgentCandidates(); };
        if (createInput) { createInput.addEventListener('focus', handler, { once: true }); }
        if (editInput) { editInput.addEventListener('focus', handler, { once: true }); }
    }

    function dispose(){
        if (cleanupPollTimer) { clearInterval(cleanupPollTimer); cleanupPollTimer = null; }
        lastCleanupVersion = null;
        listViewRef = null;
        miniMenu = null;
        agentCandidatesLoaded = false;
    }

    window.BlockIp = { init, dispose };
})();
