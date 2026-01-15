(function(){
    'use strict';

    function escapeHtml(s) { if (s === null || s === undefined) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }
    // URL表示用: スラッシュは可読性のため残す
    function escapeHtmlKeepSlash(s) {
        if (s === null || s === undefined) return '';
        return String(s)
            .replace(/&/g,'&amp;')
            .replace(/</g,'&lt;')
            .replace(/>/g,'&gt;')
            .replace(/"/g,'&quot;')
            .replace(/'/g,'&#39;');
    }

    const STATE = { server: '', filter: 'all', page: 1, size: 20, total: 0, totalPages: 1, q: '', canOperate: false, currentItem: null, currentAction: null };
    let toastTimer = null;
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
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { toast.style.display = 'none'; }, 1600);
    }

    function closeMiniMenu(resetCurrent = true) {
        if (miniMenu) miniMenu.hide();
        if (resetCurrent) STATE.currentItem = null;
    }

    function showMiniMenu(ev) {
        if (!miniMenu || !STATE.currentItem) return;
        const { userFinalThreat, isWhitelisted } = STATE.currentItem;
        const canOperate = STATE.canOperate;
        const items = [
            { label: 'URLをコピー', onClick: async () => { await copyUrl(STATE.currentItem.fullUrl || ''); showToast('URLをコピーしました'); } },
            { label: 'アクセスを危険にカテゴリーする', hidden: userFinalThreat, disabled: !canOperate, onClick: () => openNoteModal('danger', STATE.currentItem) },
            { label: 'アクセスを安全にカテゴリーする', hidden: isWhitelisted, disabled: !canOperate, onClick: () => openNoteModal('safe', STATE.currentItem) },
            { label: 'カテゴリーを解除する', hidden: (!userFinalThreat && !isWhitelisted), disabled: !canOperate, onClick: () => openNoteModal('clear', STATE.currentItem) },
            { label: canOperate ? 'カテゴリーされた理由を確認する' : 'カテゴリーされた理由を確認する（閲覧のみ）', onClick: () => openNoteModal('note', STATE.currentItem), disabled: false }
        ];
        miniMenu.show({ x: ev.pageX ?? ev.clientX, y: ev.pageY ?? ev.clientY, items });
    }

    function setupMiniMenu() {
        const menu = document.getElementById('url-threat-mini-menu');
        miniMenu = window.MiniMenu ? window.MiniMenu.create(menu) : null;
    }

    async function copyUrl(text) {
        try {
            if (navigator.clipboard && window.isSecureContext) {
                await navigator.clipboard.writeText(text);
                return true;
            }
        } catch (e) { /* fallback below */ }
        try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.left = '-1000px';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            return true;
        } catch (e) {
            console.warn('clipboard fallback failed', e);
            return false;
        }
    }

    async function fetchServers() {
        try {
            const resp = await fetch('/api/servers?size=200', { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
            if (resp.status === 401) { window.location.href = '/login'; return []; }
            if (!resp.ok) { console.error('[UrlThreat] fetchServers failed', resp.status); showMessage('サーバー取得に失敗しました'); return []; }
            const data = await resp.json();
            return data.servers || [];
        } catch (e) {
            console.error('[UrlThreat] fetchServers error', e);
            showMessage('通信エラーが発生しました');
            return [];
        }
    }

    function populateServers(servers) {
        const sel = document.getElementById('url-threat-server');
        if (!sel) return;
        sel.innerHTML = '';
        const activeServers = (servers || []).filter(s => s.isActive !== false);
        if (activeServers.length === 0) {
            const opt = document.createElement('option');
            opt.textContent = '有効なサーバーなし';
            opt.value = '';
            sel.appendChild(opt);
            showMessage('有効なサーバーが登録されていません');
            return;
        }
        activeServers.forEach((s, idx) => {
            const opt = document.createElement('option');
            opt.value = s.serverName || '';
            opt.textContent = s.serverName || 'unknown';
            if (idx === 0) opt.selected = true;
            sel.appendChild(opt);
        });
        STATE.server = sel.value;
    }

    async function fetchThreats() {
        const server = STATE.server || '';
        const filter = STATE.filter || 'all';
        const page = STATE.page || 1;
        const size = STATE.size || 20;
        const q = (STATE.q || '').trim();
        if (!server) { showMessage('サーバーを選択してください'); renderRows([]); renderPager(); renderCount(0); return; }
        showMessage('読み込み中...');
        const params = new URLSearchParams();
        params.append('server', server);
        params.append('filter', filter);
        params.append('page', page);
        params.append('size', size);
        if (q) params.append('q', q);
        try {
            const resp = await fetch('/api/url-threats?' + params.toString(), { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) {
                const text = await resp.text().catch(() => '');
                console.error('[UrlThreat] fetchThreats failed', resp.status, text);
                showMessage('脅威度の取得に失敗しました');
                renderRows([]); renderPager(); renderCount(0);
                return;
            }
            const data = await resp.json();
            STATE.total = data.total || 0;
            STATE.totalPages = data.totalPages || 1;
            STATE.canOperate = data.canOperate === true;
            const visibleCount = (data.items || []).length;
            renderRows(data.items || []);
            renderPager();
            renderCount(visibleCount);
            showMessage('');
        } catch (e) {
            console.error('[UrlThreat] fetchThreats error', e);
            showMessage('通信エラーが発生しました');
            renderRows([]); renderPager(); renderCount(0);
        }
    }

    function renderRows(items) {
        const tbody = document.getElementById('url-threat-body');
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!items || items.length === 0) {
            const tr = document.createElement('tr');
            tr.innerHTML = '<td colspan="7">データがありません</td>';
            tbody.appendChild(tr);
            return;
        }
        items.forEach(item => {
            const tr = document.createElement('tr');
            const threatKey = item.threatKey || 'unknown';
            tr.classList.add('threat-' + threatKey);
            tr.dataset.serverName = item.serverName || '';
            tr.dataset.method = item.method || '';
            tr.dataset.fullUrl = item.fullUrl || '';
            tr.dataset.userFinalThreat = item.userFinalThreat === true ? 'true' : 'false';
            tr.dataset.isWhitelisted = item.isWhitelisted === true ? 'true' : 'false';
            tr.dataset.userThreatNote = item.userThreatNote || '';

            const tdThreat = document.createElement('td');
            tdThreat.classList.add('threat-cell');
            tdThreat.innerHTML = '<span class="badge threat-' + threatKey + '">' + escapeHtml(item.threatLabel || '-') + '</span>';
            const tdUrl = document.createElement('td');
            tdUrl.classList.add('url-cell');
            tdUrl.textContent = item.fullUrl || '';
            tdUrl.title = item.fullUrl || '';
            const tdMethod = document.createElement('td');
            tdMethod.classList.add('method-cell');
            tdMethod.textContent = item.method || '';
            tdMethod.title = item.method || '';
            const tdAtk = document.createElement('td');
            tdAtk.classList.add('attack-cell');
            tdAtk.textContent = item.attackType ? item.attackType : '-';
            tdAtk.title = item.attackType ? item.attackType : '';
            const tdLast = document.createElement('td');
            tdLast.classList.add('access-cell');
            tdLast.textContent = item.latestAccessTime || '未記録';
            tdLast.title = item.latestAccessTime || '';
            const tdStatus = document.createElement('td');
            tdStatus.classList.add('status-cell');
            tdStatus.textContent = (item.latestStatusCode !== undefined && item.latestStatusCode !== null) ? item.latestStatusCode : '';
            tdStatus.title = tdStatus.textContent;
            const tdModsec = document.createElement('td');
            tdModsec.classList.add('modsec-cell');
            tdModsec.textContent = item.latestBlockedByModsec === true ? 'ブロック' : '';
            tdModsec.title = tdModsec.textContent;

            tr.append(tdThreat, tdUrl, tdMethod, tdAtk, tdLast, tdStatus, tdModsec);
            tr.addEventListener('click', ev => {
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
    }

    function renderPager() {
        const pager = document.getElementById('url-threat-pager');
        if (!pager) return;
        pager.innerHTML = '';
        if (STATE.totalPages <= 1) return;
        const mk = (label, page, disabled=false) => {
            const btn = document.createElement('button');
            btn.textContent = label;
            btn.disabled = disabled;
            btn.addEventListener('click', () => { if (btn.disabled) return; STATE.page = page; fetchThreats(); });
            return btn;
        };
        pager.appendChild(mk('«', 1, STATE.page === 1));
        pager.appendChild(mk('‹', Math.max(1, STATE.page - 1), STATE.page === 1));
        const info = document.createElement('span');
        info.textContent = `${STATE.page} / ${STATE.totalPages}`;
        pager.appendChild(info);
        pager.appendChild(mk('›', Math.min(STATE.totalPages, STATE.page + 1), STATE.page === STATE.totalPages));
        pager.appendChild(mk('»', STATE.totalPages, STATE.page === STATE.totalPages));
    }

    function renderCount(visibleCount = 0) {
        const el = document.getElementById('url-threat-count');
        if (!el) return;
        if (!STATE.server) { el.textContent = ''; return; }
        const total = STATE.total || 0;
        if (total === 0) { el.textContent = '0 件'; return; }
        const from = Math.min(((STATE.page - 1) * STATE.size) + 1, total);
        const to = Math.min(STATE.page * STATE.size, total);
        const shown = Math.max(0, visibleCount);
        el.textContent = `${total} 件中 ${from}–${to} 件を表示（このページ ${shown} 件）`;
    }

    function showMessage(msg) {
        const el = document.getElementById('url-threat-message');
        if (!el) return;
        el.textContent = msg || '';
    }

    function updateMiniMenuVisibility() {
        const menu = document.getElementById('url-threat-mini-menu');
        if (!menu || !STATE.currentItem) return;
        const { userFinalThreat, isWhitelisted } = STATE.currentItem;
        const canOperate = STATE.canOperate;
        const items = menu.querySelectorAll('.mini-menu-item');
        if (!items || items.length === 0) return;
        items.forEach(btn => {
            if (!btn) return;
            btn.disabled = false; btn.classList.remove('hidden');
        });
        const btnDanger = menu.querySelector('[data-action="danger"]');
        const btnSafe = menu.querySelector('[data-action="safe"]');
        const btnClear = menu.querySelector('[data-action="clear"]');
        const btnNote = menu.querySelector('[data-action="note"]');
        if (btnDanger && userFinalThreat) { btnDanger.classList.add('hidden'); }
        if (btnSafe && isWhitelisted) { btnSafe.classList.add('hidden'); }
        if (btnClear && !userFinalThreat && !isWhitelisted) { btnClear.classList.add('hidden'); }
        if (!canOperate) {
            [btnDanger, btnSafe, btnClear].forEach(b => { if (b) b.disabled = true; });
        }
        if (btnNote && !canOperate) { btnNote.textContent = 'カテゴリーされた理由を確認する（閲覧のみ）'; }
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
        textarea.readOnly = isViewOnly;
        textarea.disabled = isViewOnly;
        save.disabled = isViewOnly;
        title.textContent = action === 'note' ? 'カテゴリーされた理由を確認' : '理由を入力';
        hint.textContent = isViewOnly ? '閲覧のみ可能です（権限なし）' : '変更理由を記載してください';
        backdrop.classList.remove('hidden');
        modal.classList.remove('hidden');
    }

    function closeNoteModal() {
        const backdrop = document.getElementById('url-threat-modal-backdrop');
        const modal = document.getElementById('url-threat-note-modal');
        backdrop.classList.add('hidden');
        modal.classList.add('hidden');
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
            note
        };
        try {
            const resp = await fetch('/api/url-threats', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' }, body: JSON.stringify(body) });
            if (resp.status === 401) { window.location.href = '/login'; return; }
            if (!resp.ok) {
                const text = await resp.text().catch(() => '');
                console.error('[UrlThreat] update failed', resp.status, text);
                showMessage('更新に失敗しました');
                return;
            }
            showMessage('更新しました');
            closeNoteModal();
            await fetchThreats();
        } catch (e) {
            console.error('[UrlThreat] update error', e);
            showMessage('通信エラーが発生しました');
        }
    }

    function bindEvents() {
        const sel = document.getElementById('url-threat-server');
        if (sel) {
            sel.addEventListener('change', () => { STATE.server = sel.value || ''; STATE.page = 1; fetchThreats(); });
        }
        document.querySelectorAll('input[name="url-threat-filter"]').forEach(r => {
            r.addEventListener('change', () => {
                if (r.checked) {
                    STATE.filter = r.value || 'all';
                    STATE.page = 1;
                    fetchThreats();
                }
            });
        });
        const qInput = document.getElementById('url-threat-q');
        if (qInput) {
            let timer = null; const DEBOUNCE = 250;
            const schedule = () => {
                if (timer) clearTimeout(timer);
                timer = setTimeout(() => { STATE.q = qInput.value || ''; STATE.page = 1; fetchThreats(); }, DEBOUNCE);
            };
            qInput.addEventListener('input', schedule);
            qInput.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); schedule(); } });
        }
        const save = document.getElementById('url-threat-note-save');
        const cancel = document.getElementById('url-threat-note-cancel');
        if (save) save.addEventListener('click', submitAction);
        if (cancel) cancel.addEventListener('click', () => closeNoteModal());
        const backdrop = document.getElementById('url-threat-modal-backdrop');
        if (backdrop) backdrop.addEventListener('click', () => closeNoteModal());
        setupMiniMenu();
    }

    async function init() {
        const servers = await fetchServers();
        populateServers(servers);
        bindEvents();
        await fetchThreats();
    }

    window.UrlThreat = { init };
})();
