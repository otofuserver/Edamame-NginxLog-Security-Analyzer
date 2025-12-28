(function(){
    'use strict';

    // プロフィールモーダル処理をモジュール化
    window.ProfileModal = {
        open: async function(showLogins) {
            if (!window.fetch) return; // sanity
            try {
                const resp = await fetch('/api/me/profile', { method: 'GET', credentials: 'same-origin' });
                if (!resp.ok) { alert('プロフィール取得に失敗しました'); return; }
                const body = await resp.json();
                const user = body.user || {};
                const logins = body.loginHistory || [];
                const modal = document.getElementById('profile-modal'); if (!modal) return;
                const nameEl = document.getElementById('profile-name');
                const emailEl = document.getElementById('profile-email');
                const editBtn = document.getElementById('profile-edit-btn');
                if (nameEl) { nameEl.value = user.username || ''; nameEl.setAttribute('readonly','true'); }
                if (emailEl) { emailEl.value = user.email || ''; emailEl.setAttribute('readonly','true'); emailEl.setAttribute('data-editable','false'); }
                if (editBtn) { editBtn.textContent = '編集'; }

                const logdiv = document.getElementById('profile-logins');
                const headerEl = document.getElementById('profile-logins-header');
                function formatDateStr(s) {
                    if (!s) return '';
                    let d = new Date(s);
                    if (isNaN(d.getTime())) {
                        const t = s.replace(' ', 'T');
                        d = new Date(t);
                    }
                    if (isNaN(d.getTime())) return s;
                    try { return new Intl.DateTimeFormat('ja-JP', { year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', second:'2-digit' }).format(d); } catch(e) { return d.toLocaleString(); }
                }

                if (logins && logins.length > 0) {
                    const rows = logins.slice(0,20);
                    const table = document.createElement('table');
                    table.className = 'profile-logins-table';
                    const thead = document.createElement('thead');
                    thead.innerHTML = '<tr><th>日時</th><th>IP</th><th>詳細</th></tr>';
                    table.appendChild(thead);
                    const tbody = document.createElement('tbody');
                    for (const r of rows) {
                        const tr = document.createElement('tr');
                        const dt = document.createElement('td'); dt.textContent = formatDateStr(r.login_time || r.loginTime || r.timestamp || ''); tr.appendChild(dt);
                        const ip = document.createElement('td'); ip.textContent = r.ip || r.address || ''; tr.appendChild(ip);
                        const detail = document.createElement('td');
                        const details = [];
                        if (r.user_agent) details.push(r.user_agent);
                        if (r.ua) details.push(r.ua);
                        if (r.note) details.push(r.note);
                        detail.textContent = details.join(' | ');
                        tr.appendChild(detail);
                        tbody.appendChild(tr);
                    }
                    table.appendChild(tbody);
                    logdiv.innerHTML = '';
                    logdiv.appendChild(table);
                    if (headerEl) headerEl.style.display = 'block';
                    logdiv.style.display = showLogins ? 'block' : 'none';
                } else {
                    if (headerEl) headerEl.style.display = 'none';
                    logdiv.style.display = 'none';
                }

                modal.style.display = 'flex'; modal.setAttribute('aria-hidden','false');

                const profileCloseEl = document.getElementById('profile-close');
                if (profileCloseEl) profileCloseEl.onclick = () => { modal.style.display='none'; modal.setAttribute('aria-hidden','true'); };

                // profile edit button handling
                if (editBtn && emailEl) {
                    editBtn.onclick = async () => {
                        const isEditable = emailEl.getAttribute('data-editable') === 'true';
                        if (!isEditable) {
                            emailEl.removeAttribute('readonly');
                            emailEl.setAttribute('data-editable','true');
                            editBtn.textContent = '保存';
                            emailEl.focus();
                        } else {
                            // save
                            try {
                                const name = document.getElementById('profile-name').value || '';
                                const email = document.getElementById('profile-email').value || '';
                                const resp2 = await fetch('/api/me/profile', { method: 'PUT', credentials: 'same-origin', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ name: name, email: email }) });
                                if (!resp2.ok) { const t = await resp2.text(); const errEl = document.getElementById('profile-error'); if (errEl) { errEl.textContent = '保存に失敗しました: ' + t; errEl.style.display = 'block'; } return; }
                                emailEl.setAttribute('readonly','true'); emailEl.setAttribute('data-editable','false'); editBtn.textContent = '編集';
                                const errEl = document.getElementById('profile-error'); if (errEl) { errEl.style.display='none'; errEl.textContent=''; }
                            } catch(e) { const errEl = document.getElementById('profile-error'); if (errEl) { errEl.textContent = '通信エラー: ' + e.message; errEl.style.display = 'block'; } }
                        }
                    };
                }

            } catch(e) { alert('通信エラー: ' + e.message); }
        }
    };
})();

