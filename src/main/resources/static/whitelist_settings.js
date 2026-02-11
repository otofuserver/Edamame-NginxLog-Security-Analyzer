(function(){
    'use strict';

    const STATE = { mode: false, ips: [] };
    let listView = null;

    function $(id){ return document.getElementById(id); }

    function escapeHtml(s){ if (s === null || s === undefined) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

    function renderRows(items){
        const tbody = $('whitelist-tbody'); if (!tbody) return;
        if (!items || !items.length) {
            tbody.innerHTML = '<tr><td colspan="2">登録されたIPがありません</td></tr>';
            return;
        }
        tbody.innerHTML = '';
        items.forEach(function(item){
            const tr = document.createElement('tr');
            tr.innerHTML = '<td class="mono">'+escapeHtml(item.ip || '')+'</td>'+
                '<td><button type="button" class="link-like" data-ip="'+escapeHtml(item.ip||'')+'">削除</button></td>';
            tr.querySelector('button').addEventListener('click', function(ev){ ev.stopPropagation(); removeIp(item.ip); });
            tbody.appendChild(tr);
        });
    }

    function applyStateToUi(){
        const toggle = $('whitelist-mode-toggle');
        const status = $('whitelist-mode-status');
        if (toggle) toggle.checked = !!STATE.mode;
        if (status) status.textContent = STATE.mode ? 'ON' : 'OFF';
    }

    function buildListView(){
        if (!window.ListViewCore || typeof window.ListViewCore.createListView !== 'function') return;
        listView = window.ListViewCore.createListView({
            headerSelector: '#whitelist-list th[data-column]',
            pagerEl: null,
            searchInput: null,
            applyStateToUi: applyStateToUi,
            fetcher: async function(){
                return { items: STATE.ips.map(function(ip){ return { ip: ip }; }), total: STATE.ips.length, page:1, size:STATE.ips.length || 1, totalPages:1 };
            },
            renderRows: function(items){ renderRows(items); },
            onStateChange: function(){}
        });
        if (listView) listView.init();
    }

    async function loadSettings(){
        try {
            const resp = await fetch('/api/whitelist-settings', { method:'GET', credentials:'same-origin' });
            if (!resp.ok) { showError('設定の取得に失敗しました'); return; }
            const data = await resp.json();
            STATE.mode = data && data.whitelistMode === true;
            STATE.ips = Array.isArray(data && data.whitelistIps) ? data.whitelistIps : [];
            applyStateToUi();
            if (listView && listView.reload) {
                listView.reload(1);
            } else {
                renderRows(STATE.ips.map(function(ip){ return { ip: ip }; }));
            }
        } catch (e) {
            showError('通信エラーが発生しました');
        }
    }

    async function saveSettings(){
        try {
            const resp = await fetch('/api/whitelist-settings', {
                method:'PUT',
                credentials:'same-origin',
                headers:{'Content-Type':'application/json'},
                body: JSON.stringify({ whitelistMode: STATE.mode, whitelistIps: STATE.ips })
            });
            if (!resp.ok) {
                const txt = await resp.text();
                showError('更新に失敗しました: '+txt);
                return;
            }
            const data = await resp.json();
            STATE.mode = data && data.whitelistMode === true;
            STATE.ips = Array.isArray(data && data.whitelistIps) ? data.whitelistIps : [];
            applyStateToUi();
            if (listView && listView.reload) { listView.reload(1); }
        } catch (e) {
            showError('通信エラーが発生しました');
        }
    }

    function parseInputIps(){
        const input = $('whitelist-ip-input');
        if (!input) return [];
        const raw = input.value || '';
        if (!raw.trim()) return [];
        return raw.split(',').map(function(part){ return part.trim(); }).filter(function(ip){ return ip; });
    }

    function validateIp(ip){
        if (!ip.match(/^[0-9a-fA-F:.,]+$/)) {
            throw new Error('IP形式が不正です: '+ip);
        }
    }

    function addIps(newIps){
        let changed = false;
        newIps.forEach(function(ip){
            validateIp(ip);
            if (!STATE.ips.includes(ip)) {
                STATE.ips.push(ip);
                changed = true;
            }
        });
        if (changed) saveSettings();
    }

    function removeIp(ip){
        const next = STATE.ips.filter(function(v){ return v !== ip; });
        STATE.ips = next;
        saveSettings();
    }

    function bindEvents(){
        const toggle = $('whitelist-mode-toggle');
        if (toggle) {
            toggle.addEventListener('change', function(){
                STATE.mode = toggle.checked;
                applyStateToUi();
                saveSettings();
            });
        }
        const addBtn = $('whitelist-add-btn');
        if (addBtn) {
            addBtn.addEventListener('click', function(){
                try {
                    const ips = parseInputIps();
                    if (!ips.length) { showError('IPを入力してください'); return; }
                    hideError();
                    addIps(ips);
                    const input = $('whitelist-ip-input');
                    if (input) input.value = '';
                } catch (e) {
                    showError(e.message || '入力エラー');
                }
            });
        }
    }

    function showError(msg){ const el=$('whitelist-error'); if (el){ el.textContent=msg||''; el.style.display='block'; } }
    function hideError(){ const el=$('whitelist-error'); if (el){ el.textContent=''; el.style.display='none'; } }

    async function init(){
        hideError();
        buildListView();
        bindEvents();
        await loadSettings();
    }

    window.WhitelistSettings = { init };
})();

