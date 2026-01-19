(function(){
    'use strict';

    const STATE = { items: [], sort: 'updatedAt', order: 'desc', server: '', q: '', canEdit: false, currentItem: null, page:1, size:20, total:0, totalPages:1 };
    let miniMenu = null;

    function $(id){ return document.getElementById(id); }

    function escapeHtml(s){ if (s === null || s === undefined) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    function formatDateTime(val){
        if (!val) return '';
        try {
            const d = new Date(val);
            if (Number.isNaN(d.getTime())) return escapeHtml(val);
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

    function fillServerSelect(selectEl, servers, opts = { includeEmpty: true, includeAll: false }) {
        if (!selectEl) return;
        const current = selectEl.value;
        selectEl.innerHTML = '';
        const addOpt = (val,label) => { const o=document.createElement('option'); o.value=val; o.textContent=label; selectEl.appendChild(o); };
        if (opts.includeEmpty) addOpt('', 'すべて');
        if (opts.includeAll) addOpt('all','all (全体)');
        (servers||[]).filter(s => s.isActive !== false).forEach(s => addOpt(s.serverName || '', s.serverName || ''));
        if (current) selectEl.value = current;
    }

    async function loadServersToControls(){
        const servers = await fetchServers();
        fillServerSelect($('url-suppression-server'), servers, { includeEmpty: true, includeAll: false });
        fillServerSelect($('url-suppression-server-input'), servers, { includeEmpty: true, includeAll: false });
    }

    async function search(page=1){
        const q = ($('url-suppression-q')?.value || '').trim();
        STATE.q = q; STATE.server = $('url-suppression-server')?.value || '';
        STATE.page = page || 1;
        const params = new URLSearchParams();
        if (STATE.q) params.append('q', STATE.q);
        if (STATE.server) params.append('server', STATE.server);
        params.append('sort', STATE.sort);
        params.append('order', STATE.order);
        params.append('page', STATE.page);
        params.append('size', STATE.size);
        const url = '/api/url-suppressions?' + params.toString();
        try {
            const resp = await fetch(url, { method:'GET', credentials:'same-origin' });
            if (!resp.ok) { renderError('取得に失敗しました'); return; }
            const data = await resp.json();
            STATE.items = data.items || [];
            STATE.canEdit = data.canEdit === true;
            STATE.total = data.total ?? STATE.items.length;
            STATE.totalPages = data.totalPages ?? 1;
            STATE.page = data.page ?? STATE.page;
            STATE.size = data.size ?? STATE.size;
            applyPermissions(STATE.canEdit);
            render();
        } catch (e) { renderError('通信エラー'); }
    }

    function render(){
        const tbody = $('url-suppression-body'); if (!tbody) return;
        if (!STATE.items.length){ tbody.innerHTML = '<tr><td colspan="6">データがありません</td></tr>'; return; }
        const sorted = STATE.items.slice().sort((a,b)=>{
            let va=a[STATE.sort], vb=b[STATE.sort];
            if (va === null || va === undefined) va=''; if (vb === null || vb === undefined) vb='';
            if (typeof va === 'string') va = va.toLowerCase(); if (typeof vb === 'string') vb = vb.toLowerCase();
            if (va<vb) return STATE.order==='asc'?-1:1; if (va>vb) return STATE.order==='asc'?1:-1; return 0;
        });
        tbody.innerHTML='';
        sorted.forEach(item=>{
            const tr=document.createElement('tr');
            if (!item.isEnabled) tr.classList.add('inactive');
            tr.dataset.id=item.id;
            tr.dataset.server=item.serverName||'';
            tr.dataset.enabled=item.isEnabled?'true':'false';
            tr.dataset.pattern=item.urlPattern||'';
            tr.dataset.description=item.description||'';
            tr.innerHTML=`<td>${escapeHtml(item.serverName||'')}</td>
                <td class="mono">${escapeHtml(item.urlPattern||'')}</td>
                <td>${item.isEnabled?'有効':'無効'}</td>
                <td>${formatDateTime(item.lastAccessAt)}</td>
                 <td>${item.dropCount??0}</td>
                <td>${formatDateTime(item.updatedAt)}</td>`;
            tr.addEventListener('click', ev=>handleRowClick(ev, item));
            tbody.appendChild(tr);
        });
        updateSortIndicators();
        renderPager();
    }

    function renderError(msg){ const tbody=$('url-suppression-body'); if (tbody) tbody.innerHTML='<tr><td colspan="6">'+escapeHtml(msg)+'</td></tr>'; }

    function setupMiniMenu(){ const menu=$('url-suppression-mini-menu'); miniMenu = window.MiniMenu?window.MiniMenu.create(menu):null; }

    function handleRowClick(ev, item){
        ev.stopPropagation();
        STATE.currentItem = item;
        showMiniMenu(ev);
    }

    function showMiniMenu(ev){
        if (!miniMenu || !STATE.currentItem) return;
        const item = STATE.currentItem;
        const canEdit = STATE.canEdit;
        const enabled = item.isEnabled === true;
        const items = [
            {label: enabled ? '無効化' : '有効化', hidden: !canEdit, onClick: ()=>toggle(item, !enabled)},
            {label:'編集', hidden:!canEdit, onClick:()=>openModal(item)},
            {label:'削除', hidden:!canEdit, onClick:()=>remove(item)}
         ];
        miniMenu.show({x: ev.pageX??ev.clientX, y: ev.pageY??ev.clientY, items});
    }

    async function toggle(item, enabled){
        await saveRule({ ...item, isEnabled: enabled });
    }

    async function remove(item){
        if (!confirm('削除しますか？')) return;
        try {
            const resp = await fetch('/api/url-suppressions/'+encodeURIComponent(item.id), { method:'DELETE', credentials:'same-origin' });
            if (!resp.ok) { alert('削除に失敗しました'); return; }
            search();
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
        await saveRule({ ...payload, id: id ? Number(id) : null });
        closeModal();
    }

    async function saveRule(rule){
        const isUpdate = !!rule.id;
        const path = isUpdate ? '/api/url-suppressions/'+encodeURIComponent(rule.id) : '/api/url-suppressions';
        const method = isUpdate ? 'PUT' : 'POST';
        try {
            const resp = await fetch(path, { method, credentials:'same-origin', headers:{'Content-Type':'application/json'}, body: JSON.stringify(rule) });
            if (!resp.ok){ const txt=await resp.text(); alert('保存に失敗しました: '+txt); return; }
            search();
        } catch (e){ alert('通信エラー'); }
    }

    function showError(id,msg){ const el=$(id); if (!el) return; el.textContent=msg||''; el.style.display='block'; }

    function attachEvents(){
         const debouncedSearch = (()=>{
            let t; return ()=>{ clearTimeout(t); t=setTimeout(()=>search(1), 200); };
         })();
         $('url-suppression-q')?.addEventListener('input', debouncedSearch);
         $('url-suppression-q')?.addEventListener('keydown', e=>{ if (e.key==='Enter'){ e.preventDefault(); } });
         $('url-suppression-server')?.addEventListener('change', ()=>search(1));
         $('url-suppression-create')?.addEventListener('click', ()=>openModal(null));
         $('url-suppression-save')?.addEventListener('click', ()=>saveFromModal());
         $('url-suppression-cancel')?.addEventListener('click', ()=>closeModal());
         $('url-suppression-delete')?.addEventListener('click', ()=>{
            const id=$('url-suppression-id').value; if (!id) { closeModal(); return; }
            if (!confirm('削除しますか？')) return;
            fetch('/api/url-suppressions/'+encodeURIComponent(id), { method:'DELETE', credentials:'same-origin' })
                .then(r=>{ if(!r.ok) { r.text().then(t=>alert('削除に失敗: '+t)); return; } closeModal(); search(); })
                .catch(()=>alert('通信エラー'));
        });
        document.addEventListener('click', ()=>{ if (miniMenu) miniMenu.hide(); });
        document.querySelectorAll('#url-suppression-results th[data-column]').forEach(th=>{
            th.addEventListener('click', ()=>{
                const col = th.getAttribute('data-column');
                if (STATE.sort === col) STATE.order = STATE.order === 'asc' ? 'desc' : 'asc'; else { STATE.sort = col; STATE.order = 'asc'; }
                render();
             });
         });
     }

    function updateSortIndicators(){
        document.querySelectorAll('#url-suppression-results th[data-column]').forEach(th=>{
            const col=th.getAttribute('data-column');
            const active=col===STATE.sort; const arrow=active ? (STATE.order==='asc'?' ▲':' ▼') : '';
            th.textContent=(th.dataset.labelOriginal||th.textContent.replace(/[ ▲▼]+$/,''))+(arrow);
            th.dataset.labelOriginal = th.dataset.labelOriginal || th.textContent.replace(/[ ▲▼]+$/,'');
            th.classList.toggle('active-sort', active);
        });
    }

    function renderPager(){
        const pager = document.getElementById('url-suppression-pager');
        if (!pager) return;
        pager.innerHTML='';
        if (STATE.totalPages <= 1) return;
        const mkBtn = (label, targetPage, disabled=false) => {
            const b=document.createElement('button');
            b.textContent=label; b.disabled=disabled; b.addEventListener('click', ()=>{ if (!b.disabled) search(targetPage); });
            return b;
        };
        pager.appendChild(mkBtn('«', 1, STATE.page<=1));
        pager.appendChild(mkBtn('‹', Math.max(1, STATE.page-1), STATE.page<=1));
        const info=document.createElement('span'); info.textContent=` ${STATE.page} / ${STATE.totalPages} （全 ${STATE.total} 件） `;
        pager.appendChild(info);
        pager.appendChild(mkBtn('›', Math.min(STATE.totalPages, STATE.page+1), STATE.page>=STATE.totalPages));
        pager.appendChild(mkBtn('»', STATE.totalPages, STATE.page>=STATE.totalPages));
    }

    function applyPermissions(override){
        const meta = $('url-suppression-meta');
        const canEditMeta = meta && meta.dataset.canEdit === 'true';
        const canEdit = override !== undefined ? override : canEditMeta;
        if (override !== undefined) STATE.canEdit = canEdit;
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

    async function init(){
        setupMiniMenu();
        setupCreateButton();
        attachEvents();
        applyPermissions();
        await loadServersToControls();
        search(1);
     }

    window.UrlSuppression = { init };
})();
