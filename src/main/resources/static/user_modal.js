(function(){
    'use strict';

    // UserModal モジュール: モーダル操作・パスワード生成など
    function resolveModalElements() {
        // 要素は DOM に依存するため実行時に取得する
        const modal = document.getElementById('user-modal');
        return {
            modal,
            modalBackdrop: modal ? modal.querySelector('.modal-backdrop') : null,
            modalId: document.getElementById('modal-id'),
            modalUsername: document.getElementById('modal-username'),
            modalEmail: document.getElementById('modal-email'),
            modalEnabled: document.getElementById('modal-enabled'),
            modalSave: document.getElementById('modal-save'),
            modalDelete: document.getElementById('modal-delete'),
            modalCancel: document.getElementById('modal-cancel'),
            passwordGenerateBtn: document.getElementById('password-generate'),
            passwordCopyBtn: document.getElementById('password-copy'),
            generatedPasswordDiv: document.getElementById('generated-password')
        };
    }

    async function onGeneratePassword(editingUserRef) {
        if (!confirm('このユーザーのパスワードをサーバ側で安全に生成してリセットしますか？\n（生成されたパスワードは一度だけ表示されます。必ずコピーして保管してください。）')) return;
        // 編集対象が内部にあるか、モーダルのユーザー名入力から判定
        const els = resolveModalElements();
        const modalUsernameVal = els.modalUsername ? (els.modalUsername.value || '').trim() : '';
        const editingUser = editingUserRef || _state._editingUser || (window.UserModal && typeof window.UserModal.getEditingUser === 'function' ? window.UserModal.getEditingUser() : null);
        let username = null;
        if (editingUser && editingUser.username) username = editingUser.username;
        else if (modalUsernameVal) username = modalUsernameVal;
        // 作成モードでは username が存在してもサーバ側でリセットできない場合があるので警告
        if (!username) { alert('ユーザーが選択されていません'); return; }
        if (_state._isCreating && (!editingUser || !editingUser.username)) { alert('パスワード生成は既存ユーザーでのみ可能です。ユーザー作成後にパスワードを生成してください。'); return; }
        try {
            const resp = await fetch('/api/users/' + encodeURIComponent(username) + '/reset-password', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '' });
            if (!resp.ok) { alert('サーバ側のパスワード生成に失敗しました (' + resp.status + ')'); return; }
            const body = await resp.json();
            const pwd = body && body.password ? String(body.password) : null;
            if (!pwd) { alert('サーバからパスワードが返されませんでした'); return; }
            // 表示は一度だけ（モーダルを閉じると消去）
            _state._stagedPassword = pwd;
            const els = resolveModalElements();
            if (els.generatedPasswordDiv) { els.generatedPasswordDiv.innerText = pwd; els.generatedPasswordDiv.style.display = 'block'; }
            if (els.passwordCopyBtn) els.passwordCopyBtn.style.display = 'inline-block';
        } catch (e) { alert('通信エラー: ' + e.message); }
    }

    async function onCopyPassword() {
        const els = resolveModalElements();
        const staged = _state._stagedPassword;
        const toCopy = staged || (els.generatedPasswordDiv ? els.generatedPasswordDiv.innerText.trim() : '');
        if (!toCopy) { alert('コピーするパスワードがありません'); return; }
        try {
            await navigator.clipboard.writeText(toCopy);
            alert('パスワードをクリップボードにコピーしました');
        } catch (e) {
            try {
                const ta = document.createElement('textarea');
                ta.style.position = 'fixed'; ta.style.left = '-9999px'; ta.style.top = '0';
                ta.value = toCopy;
                document.body.appendChild(ta);
                ta.focus(); ta.select();
                const ok = document.execCommand && document.execCommand('copy');
                document.body.removeChild(ta);
                if (ok) { alert('パスワードをクリップボードにコピーしました'); return; }
            } catch (e2) {}
            alert('クリップボードにコピーできませんでした。表示されたパスワードを手動でコピーしてください。');
        }
    }

    function attachModalEventHandlers() {
         const els = resolveModalElements();
         try { if (els.modalSave) { els.modalSave.onclick = () => saveUser(); } } catch(e) {}
         try { if (els.modalDelete) { els.modalDelete.onclick = () => deleteUser(); } } catch(e) {}
         try { if (els.modalCancel) { els.modalCancel.onclick = () => closeUserModal(); } } catch(e) {}
         try { if (els.modalBackdrop) { els.modalBackdrop.onclick = () => closeUserModal(); } } catch(e) {}
         try { if (els.passwordGenerateBtn) els.passwordGenerateBtn.onclick = () => onGeneratePassword(); } catch(e) {}
         try { if (els.passwordCopyBtn) els.passwordCopyBtn.onclick = () => onCopyPassword(); } catch(e) {}
         // username 入力時にインラインエラーとモーダルエラーをクリア
         try { if (els.modalUsername) { els.modalUsername.oninput = function() { try { const u = document.getElementById('username-error'); if (u) { u.innerText = ''; u.style.display = 'none'; } const m = document.getElementById('modal-error'); if (m) { m.innerText = ''; m.style.display = 'none'; } } catch(e) {} }; } } catch(e) {}
         try { const roleAddBtn = document.getElementById('role-add'); if (roleAddBtn) roleAddBtn.onclick = () => { const sel = document.getElementById('role-select'); if (sel && sel.value) stageAddRole(sel.value); }; } catch(e) {}
    }

    // 内部 state
    const _state = {
        _editingUser: null,
        _stagedPassword: null,
        _pendingAdds: new Set(),
        _pendingRemovals: new Set(),
        _isCreating: false
    };

    function renderUserRoles(roles) {
        const container = document.getElementById('user-roles');
        if (!container) return;
        container.innerHTML = '';
        for (const r of (roles||[])) {
            const pill = document.createElement('span');
            pill.className = 'role-pill';
            pill.style.padding = '.25rem .5rem';
            pill.style.background = '#eef2ff';
            pill.style.borderRadius = '6px';
            pill.style.display = 'inline-flex';
            pill.style.alignItems = 'center';
            pill.style.gap = '.5rem';
            pill.textContent = r;
            const btn = document.createElement('button'); btn.type = 'button'; btn.textContent = '×';
            btn.style.border = 'none'; btn.style.background = 'transparent'; btn.style.cursor = 'pointer';
            btn.addEventListener('click', () => stageRemoveRole(r));
            pill.appendChild(btn);
            container.appendChild(pill);
        }
    }

    function populateRoleSelect(allRoles, userRoles) {
        const sel = document.getElementById('role-select'); if (!sel) return;
        sel.innerHTML = '';
        const userSet = new Set((userRoles||[]));
        for (const r of (allRoles||[])) {
            if (userSet.has(r)) continue;
            const opt = document.createElement('option'); opt.value = r; opt.textContent = r; sel.appendChild(opt);
        }
    }

    function stageAddRoleToUI(role) {
        const sel = document.getElementById('role-select'); if (sel) {
            for (let i = sel.options.length - 1; i >= 0; i--) { if (sel.options[i].value === role) sel.remove(i); }
        }
        const container = document.getElementById('user-roles'); if (container) {
            const pill = document.createElement('span');
            pill.className = 'role-pill';
            pill.style.padding = '.25rem .5rem';
            pill.style.background = '#eef2ff';
            pill.style.borderRadius = '6px';
            pill.style.display = 'inline-flex';
            pill.style.alignItems = 'center';
            pill.style.gap = '.5rem';
            pill.textContent = role;
            const btn = document.createElement('button'); btn.type = 'button'; btn.textContent = '×';
            btn.style.border = 'none'; btn.style.background = 'transparent'; btn.style.cursor = 'pointer';
            btn.addEventListener('click', () => stageRemoveRole(role));
            pill.appendChild(btn);
            container.appendChild(pill);
        }
    }

    function stageAddRole(role) {
        if (!role) return;
        if (_state._pendingRemovals.has(role)) _state._pendingRemovals.delete(role);
        else _state._pendingAdds.add(role);
        stageAddRoleToUI(role);
    }

    function stageRemoveRole(role) {
        if (!role) return;
        if (_state._pendingAdds.has(role)) _state._pendingAdds.delete(role);
        else _state._pendingRemovals.add(role);
        const container = document.getElementById('user-roles'); if (container) {
            Array.from(container.children).forEach(ch => { if (ch.textContent && ch.textContent.startsWith(role)) container.removeChild(ch); });
        }
        const sel = document.getElementById('role-select'); if (sel) { const opt = document.createElement('option'); opt.value = role; opt.textContent = role; sel.appendChild(opt); }
    }

    async function saveUser() {
        // resolve
        const els = resolveModalElements();
        if (!els.modal) return;
        const editingUser = _state._editingUser;
        // 簡易な可視性チェック
        if (els.modal.getAttribute('aria-hidden') === 'true' || els.modal.style.display === 'none') { console.warn('modal not visible'); return; }

        // パスワードワーニング
        if (_state._stagedPassword && !(window.UserModal && window.UserModal._passwordWarnShown)) {
            if (!confirm('生成されたパスワードは一度しか表示されません。保存を続行しますか？')) return;
            if (window.UserModal) window.UserModal._passwordWarnShown = true;
        }

        const payload = { email: (els.modalEmail && els.modalEmail.value) ? els.modalEmail.value : '', enabled: !!(els.modalEnabled && els.modalEnabled.checked) };
        try {
            if (window.isCreatingGlobal || (window.UserModal && window.UserModal._isCreating)) {
                const newUsername = (els.modalUsername && els.modalUsername.value ? els.modalUsername.value : '').trim();
                if (!newUsername) { alert('ユーザー名を入力してください'); return; }
                payload.username = newUsername;
                // 作成時はデフォルトで無効化（有効化はメールのリンクまたは管理者操作で行うため）
                payload.enabled = false;
                const resp = await fetch('/api/users', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
                if (!resp.ok) {
                    await showModalErrorFromResponse(resp, 'create');
                    return;
                }
                // 作成成功後、保留中のロールを付与する
                try {
                    for (const r of Array.from(_state._pendingAdds)) {
                        const rr = await fetch('/api/users/' + encodeURIComponent(newUsername) + '/roles', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: r }) });
                        if (!rr.ok) { console.warn('ロール追加に失敗しました: ' + r + ' (' + rr.status + ')'); }
                    }
                    // クリア
                    _state._pendingAdds.clear(); _state._pendingRemovals.clear();
                } catch (e) { console.warn('create user: apply roles error', e); }
                // refresh list if available
                if (window.UserList && typeof window.UserList.doSearch === 'function') window.UserList.doSearch(1);
                closeUserModal();
                return;
            }
            if (!editingUser || !editingUser.username) { alert('編集対象が不明です'); return; }
            const resp = await fetch('/api/users/' + encodeURIComponent(editingUser.username), { method: 'PUT', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
            if (!resp.ok) { await showModalErrorFromResponse(resp); return; }
            // apply role changes
            for (const r of Array.from(_state._pendingAdds)) {
                const rr = await fetch('/api/users/' + encodeURIComponent(editingUser.username) + '/roles', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: r }) });
                if (!rr.ok) { await showModalErrorFromResponse(rr); return; }
            }
            for (const r of Array.from(_state._pendingRemovals)) {
                const rr = await fetch('/api/users/' + encodeURIComponent(editingUser.username) + '/roles/' + encodeURIComponent(r), { method: 'DELETE', credentials: 'same-origin' });
                if (!rr.ok) { await showModalErrorFromResponse(rr); return; }
            }
            _state._pendingAdds.clear(); _state._pendingRemovals.clear();
            // refresh roles and list
            if (window.UserList && typeof window.UserList.refreshUser === 'function') {
                await window.UserList.refreshUser(editingUser.username);
            } else if (window.UserList && typeof window.UserList.doSearch === 'function') {
                window.UserList.doSearch(1);
            }
            closeUserModal();
         } catch (e) { alert('通信エラー: ' + e.message); }
     }

     async function showModalErrorFromResponse(resp, context) {
        const els = resolveModalElements();
        let msg = '保存に失敗しました（通信エラー）';
        try {
            const body = await resp.text();
            try {
                const json = JSON.parse(body);
                if (json && json.error) msg = json.error;
            } catch (e) {
                // 非JSONボディはそのまま表示
                if (body && body.trim()) msg = body.trim();
            }
        } catch (e) {}

        // 409 の場合は日本語の固定メッセージに置き換え
        const usernameErrEl = document.getElementById('username-error');
        const modalErrEl = document.getElementById('modal-error');

        // create コンテキストで 500 の場合は安全な固定文言のみ表示
        if (context === 'create' && resp.status === 500) {
            const fixed = 'ユーザー作成に失敗しました。';
            try { if (modalErrEl) { modalErrEl.innerText = fixed; modalErrEl.style.display = 'block'; } } catch(e) {}
            try { if (usernameErrEl) { usernameErrEl.innerText = ''; usernameErrEl.style.display = 'none'; } } catch(e) {}
            return;
        }

        if (resp.status === 409) {
            // username 固有のエラーはインラインで表示
            const inlineMsg = 'ユーザー名は既に使用されています。別の名前を指定してください。';
            try { if (usernameErrEl) { usernameErrEl.innerText = inlineMsg; usernameErrEl.style.display = 'block'; } } catch(e) {}
            try { if (modalErrEl) { modalErrEl.innerText = ''; modalErrEl.style.display = 'none'; } } catch(e) {}
            return;
        }

        try {
            if (els && els.modal) {
                if (modalErrEl) { modalErrEl.innerText = msg; modalErrEl.style.display = 'block'; }
            } else {
                alert(msg);
            }
        } catch (e) { alert(msg); }
    }

    function closeUserModal() {
         const els = resolveModalElements();
         if (!els.modal) return;
         _state._editingUser = null;
         _state._pendingAdds.clear(); _state._pendingRemovals.clear();
         try { if (els.generatedPasswordDiv) { els.generatedPasswordDiv.innerText = ''; els.generatedPasswordDiv.style.display = 'none'; } } catch(e) {}
         try { if (els.passwordCopyBtn) els.passwordCopyBtn.style.display = 'none'; } catch(e) {}
         // モーダルエラーをクリア
         try { const merr = document.getElementById('modal-error'); if (merr) { merr.innerText = ''; merr.style.display = 'none'; } } catch(e) {}
         try { const uerr = document.getElementById('username-error'); if (uerr) { uerr.innerText = ''; uerr.style.display = 'none'; } } catch(e) {}
          _state._stagedPassword = null;
          try { els.modal.setAttribute('aria-hidden','true'); els.modal.style.display = 'none'; } catch(e) {}
     }

    async function deleteUser() {
         const editingUser = _state._editingUser;
         if (!editingUser) return;
         if (!confirm('このユーザーを削除しますか？')) return;
         try {
            const resp = await fetch('/api/users/' + encodeURIComponent(editingUser.username), { method: 'DELETE', credentials: 'same-origin' });
            if (!resp.ok) { await showModalErrorFromResponse(resp); return; }
             if (window.UserList && typeof window.UserList.doSearch === 'function') window.UserList.doSearch(1);
             closeUserModal();
         } catch (e) { alert('通信エラー: ' + e.message); }
    }

    // 公開 API
    const api = {
        openUserModal: function(user) {
            const els = resolveModalElements();
            if (!els.modal) return;
            console.debug('[UserModal] openUserModal', { user });
            _state._editingUser = user;
            _state._isCreating = false;
            _state._pendingAdds.clear(); _state._pendingRemovals.clear();
            // clear staged password display
            try { if (els.generatedPasswordDiv) { els.generatedPasswordDiv.innerText = ''; els.generatedPasswordDiv.style.display = 'none'; } } catch(e) {}
            try { if (els.passwordCopyBtn) els.passwordCopyBtn.style.display = 'none'; } catch(e) {}
            _state._stagedPassword = null; _state._passwordWarnShown = false; _state._passwordSavedAlertShown = false;
            if (els.modalId) els.modalId.innerText = user.id !== undefined ? String(user.id) : '';
            if (els.modalUsername) els.modalUsername.value = user.username || '';
            if (els.modalUsername) els.modalUsername.readOnly = true;
            if (els.modalEmail) els.modalEmail.value = user.email || '';
            // モーダルエラーをクリア
            try { const merr = document.getElementById('modal-error'); if (merr) { merr.innerText = ''; merr.style.display = 'none'; } } catch(e) {}
            try { const uerr = document.getElementById('username-error'); if (uerr) { uerr.innerText = ''; uerr.style.display = 'none'; } } catch(e) {}
             if (els.modalEnabled) els.modalEnabled.checked = !!user.enabled;
            // 編集モードでは有効チェックを表示する
            if (els.modalEnabled) {
                try { els.modalEnabled.style.display = ''; } catch(e) {}
                try { const row = els.modalEnabled.closest && els.modalEnabled.closest('.form-row'); if (row) row.style.display = 'flex'; } catch(e) {}
            }
             if (els.modalDelete) els.modalDelete.style.display = user.enabled ? 'none' : 'inline-block';
            // 編集モードではパスワード生成を許可
            try {
                if (els.passwordGenerateBtn) {
                    els.passwordGenerateBtn.style.display = 'inline-block';
                    const row = els.passwordGenerateBtn.closest && els.passwordGenerateBtn.closest('.form-row'); if (row) row.style.display = 'flex';
                }
            } catch(e) {}
             try { if (els.generatedPasswordDiv) els.generatedPasswordDiv.style.display = 'none'; } catch(e) {}
             try { if (els.passwordCopyBtn) els.passwordCopyBtn.style.display = 'none'; } catch(e) {}
             // load user roles first; only show modal after successful fetch to avoid briefly displaying
             // modal when the server rejects access (e.g. self-edit forbidden)
             fetch('/api/users/' + encodeURIComponent(user.username), { method: 'GET', credentials: 'same-origin' })
                 .then(async r => {
                     if (!r.ok) {
                         // エラー応答はモーダル内に表示して閉じる（モーダルはまだ表示していない）
                         try { await showModalErrorFromResponse(r); } catch(e) {}
                         // ensure state cleared
                         _state._editingUser = null;
                         throw new Error('fetch user detail failed: ' + r.status);
                     }
                     return r.json();
                 })
                 .then(data => {
                     populateRoleSelect(data.allRoles||[], data.roles||[]);
                     renderUserRoles(data.roles||[]);
                    // アクティベーション再送ボタンの表示制御
                    try {
                        const resendBtn = document.getElementById('resend-activation-btn');
                        if (resendBtn) {
                            if (data.user && data.user.enabled === false && data.hasExpiredUnusedActivationToken === true) {
                                resendBtn.style.display = 'inline-block';
                                resendBtn.onclick = async function() {
                                    if (!confirm('アクティベーションメールを再送しますか？')) return;
                                    try {
                                        const resp = await fetch('/api/users/' + encodeURIComponent(user.username) + '/resend-activation', { method: 'POST', credentials: 'same-origin' });
                                        if (!resp.ok) {
                                            try { const txt = await resp.text(); alert('再送に失敗しました: ' + txt); } catch(e) { alert('再送に失敗しました'); }
                                            return;
                                        }
                                        alert('アクティベーションメールを再送しました');
                                    } catch (e) { alert('通信エラー: ' + e.message); }
                                };
                            } else {
                                resendBtn.style.display = 'none';
                                resendBtn.onclick = null;
                            }
                        }
                    } catch(e) {}
                     // モーダルを表示（フェッチ成功後）
                     try { els.modal.setAttribute('aria-hidden','false'); els.modal.style.display = 'flex'; } catch(e) {}
                     try { const content = els.modal.querySelector('.modal-content'); if (els.modalBackdrop) { els.modalBackdrop.style.zIndex='1000'; els.modalBackdrop.style.position='fixed'; } if (content) { content.style.zIndex='1001'; content.style.position='relative'; } } catch(e) {}
                     attachModalEventHandlers();
                 })
                 .catch(() => { /* fetch failed or access denied - modal not shown */ });
        },
        openCreateUserModal: function() {
             const els = resolveModalElements(); if (!els.modal) return;
            console.debug('[UserModal] openCreateUserModal');
            _state._editingUser = null; _state._pendingAdds.clear(); _state._pendingRemovals.clear();
            _state._stagedPassword = null; _state._passwordWarnShown = false; _state._passwordSavedAlertShown = false;
             if (els.modalId) els.modalId.innerText = '';
             if (els.modalUsername) { els.modalUsername.value = ''; els.modalUsername.readOnly = false; }
             if (els.modalEmail) els.modalEmail.value = '';
             // 作成モードでは「有効」チェックボックスは廃止（表示しない）し、デフォルトは無効
             if (els.modalEnabled) {
                 try { els.modalEnabled.checked = false; } catch(e) {}
                 try { els.modalEnabled.style.display = 'none'; } catch(e) {}
                 try { const row = els.modalEnabled.closest && els.modalEnabled.closest('.form-row'); if (row) row.style.display = 'none'; } catch(e) {}
             }
              if (els.modalDelete) els.modalDelete.style.display = 'none';
             // モーダルエラーをクリア
             try { const merr = document.getElementById('modal-error'); if (merr) { merr.innerText = ''; merr.style.display = 'none'; } } catch(e) {}
             try { const uerr = document.getElementById('username-error'); if (uerr) { uerr.innerText = ''; uerr.style.display = 'none'; } } catch(e) {}
             els.modal.setAttribute('aria-hidden','false'); els.modal.style.display = 'flex';
             try { const content = els.modal.querySelector('.modal-content'); if (els.modalBackdrop) { els.modalBackdrop.style.zIndex='1000'; els.modalBackdrop.style.position='fixed'; } if (content) { content.style.zIndex='1001'; content.style.position='relative'; } } catch(e) {}
             // ここで全ロールを取得して選択肢を初期化
             try {
                 fetch('/api/users/roles', { method: 'GET', credentials: 'same-origin' })
                     .then(r => { if (!r.ok) throw new Error('failed roles'); return r.json(); })
                     .then(list => { populateRoleSelect(list || [], []); renderUserRoles([]); })
                     .catch(() => { populateRoleSelect([], []); renderUserRoles([]); });
             } catch(e) { populateRoleSelect([], []); renderUserRoles([]); }
             // 内部フラグ
              _state._isCreating = true;
              // 作成モードではパスワード生成 UI を隠す
             try {
                 if (els.passwordGenerateBtn) {
                     els.passwordGenerateBtn.style.display = 'none';
                     const row = els.passwordGenerateBtn.closest && els.passwordGenerateBtn.closest('.form-row'); if (row) row.style.display = 'none';
                 }
             } catch(e) {}
             try { if (els.generatedPasswordDiv) els.generatedPasswordDiv.style.display = 'none'; } catch(e) {}
             try { if (els.passwordCopyBtn) els.passwordCopyBtn.style.display = 'none'; } catch(e) {}
              attachModalEventHandlers();
         },
        saveUser: saveUser,
        closeUserModal: closeUserModal,
        deleteUser: deleteUser,
        onGeneratePassword: onGeneratePassword,
        onCopyPassword: onCopyPassword,
        attachHandlers: attachModalEventHandlers,
        // internal state exposure for diagnostics
        // 追加 API
        populateRoleSelect: populateRoleSelect,
        getEditingUser: function() { return _state._editingUser; },
        _editingUser: _state._editingUser,
        _stagedPassword: _state._stagedPassword,
        _pendingAdds: _state._pendingAdds,
        _pendingRemovals: _state._pendingRemovals,
        _isCreating: _state._isCreating
     };

     // 外部から状態を参照できるように getter を追加
     try {
        Object.defineProperty(api, '_editingUser', { get: function(){ return _state._editingUser; } });
        Object.defineProperty(api, '_stagedPassword', { get: function(){ return _state._stagedPassword; } });
        Object.defineProperty(api, '_pendingAdds', { get: function(){ return _state._pendingAdds; } });
        Object.defineProperty(api, '_pendingRemovals', { get: function(){ return _state._pendingRemovals; } });
        Object.defineProperty(api, '_isCreating', { get: function(){ return _state._isCreating; } });
    } catch(e) {}

    try { window.UserModal = api; } catch(e) {}
 })();

