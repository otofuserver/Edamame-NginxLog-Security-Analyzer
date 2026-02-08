(function(){
    'use strict';

    window.PasswordModal = {
        open: function() {
            const modal = document.getElementById('password-modal'); if (!modal) return; modal.style.display='flex'; modal.setAttribute('aria-hidden','false');
            const saveBtn = document.getElementById('password-save'); const closeBtn = document.getElementById('password-close');
            if (saveBtn) saveBtn.onclick = savePassword; if (closeBtn) closeBtn.onclick = () => { modal.style.display='none'; modal.setAttribute('aria-hidden','true'); resetForm(); };
            resetForm();

            // マスク解除チェックボックスのハンドラを登録（重複登録防止）
            try {
                const unmask = document.getElementById('password-unmask');
                const cur = document.getElementById('current-password');
                const np = document.getElementById('new-password');
                const cp = document.getElementById('confirm-password');
                if (unmask && !unmask.__handler_attached__) {
                    unmask.addEventListener('change', () => {
                        const t = unmask.checked ? 'text' : 'password';
                        if (cur) cur.type = t;
                        if (np) np.type = t;
                        if (cp) cp.type = t;
                    });
                    // 初期表示をチェック状態に合わせる
                    const initialType = unmask.checked ? 'text' : 'password';
                    if (cur) cur.type = initialType;
                    if (np) np.type = initialType;
                    if (cp) cp.type = initialType;
                    unmask.__handler_attached__ = true;
                } else if (unmask) {
                    // 既にハンドラがある場合でも初期表示を設定
                    const initialType = unmask.checked ? 'text' : 'password';
                    if (cur) cur.type = initialType;
                    if (np) np.type = initialType;
                    if (cp) cp.type = initialType;
                }
            } catch(e) { console.warn('password-unmask init error', e); }
        }
    };

    function resetForm(){
        try {
            const fields = ['current-password','new-password','confirm-password'];
            fields.forEach(id => { const el = document.getElementById(id); if (el) el.value = ''; });
            const unmask = document.getElementById('password-unmask'); if (unmask) { unmask.checked = false; }
            ['current-password','new-password','confirm-password'].forEach(id => { const el = document.getElementById(id); if (el) el.type = 'password'; });
            const errEl = document.getElementById('password-error'); if (errEl) { errEl.style.display = 'none'; errEl.textContent = ''; }
        } catch(e) {}
    }

    async function savePassword() {
        const errEl = document.getElementById('password-error'); if (errEl) { errEl.style.display = 'none'; errEl.textContent = ''; }
        try {
            const p1 = document.getElementById('new-password').value || '';
            const p2 = document.getElementById('confirm-password').value || '';
            const c0 = document.getElementById('current-password').value || '';
            const MIN_LEN = 8;
            // 許可する記号に @ を含め、ハイフンはエスケープして扱う
            const symbolRegex = /[!@#$%&*()_\-@]/;
            const allowedCharsRegex = /^[A-Za-z0-9!@#$%&*()_\-@]+$/;

            if (p1 !== p2) { if (errEl) { errEl.textContent = 'パスワードが一致しません'; errEl.style.display = 'block'; } return; }
            if (!c0) { if (errEl) { errEl.textContent = '現在のパスワードを入力してください'; errEl.style.display = 'block'; } return; }
            if (p1 === c0) { if (errEl) { errEl.textContent = '現在のパスワードと同じ値は使用できません'; errEl.style.display = 'block'; } return; }
            if (p1.length < MIN_LEN) { if (errEl) { errEl.textContent = 'パスワードは最低' + MIN_LEN + '文字必要です'; errEl.style.display = 'block'; } return; }
            if (!/[A-Za-z]/.test(p1) || !/\d/.test(p1) || !symbolRegex.test(p1)) { if (errEl) { errEl.textContent = 'パスワードは英字・数字・記号のすべてを含める必要があります'; errEl.style.display = 'block'; } return; }
            if (!allowedCharsRegex.test(p1)) { if (errEl) { errEl.textContent = 'パスワードに使用できない文字が含まれています'; errEl.style.display = 'block'; } return; }

            const resp = await fetch('/api/me/password', { method: 'POST', credentials: 'same-origin', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ currentPassword: c0, password: p1 }) });
            if (!resp.ok) { const txt = await resp.text(); if (errEl) { errEl.textContent = '変更に失敗しました: ' + txt; errEl.style.display = 'block'; } return; }
            alert('パスワードを変更しました');
            const modal = document.getElementById('password-modal'); modal.style.display='none'; modal.setAttribute('aria-hidden','true');
            resetForm();
        } catch(e) { const errEl = document.getElementById('password-error'); if (errEl) { errEl.textContent = '通信エラー: ' + e.message; errEl.style.display = 'block'; } }
    }
})();
