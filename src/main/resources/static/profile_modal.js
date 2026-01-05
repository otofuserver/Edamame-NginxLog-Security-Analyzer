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

                // 追加: メール入力のクライアント側制限（許可文字以外を弾く）
                if (emailEl) {
                    // 許可文字（クライアント側簡易チェック）: 英数字 + @ . _ + - + %
                    const allowedRe = /^[A-Za-z0-9@._+%\-]*$/;
                    // 入力中の不正文字を除去して即時フィードバック
                    emailEl.addEventListener('input', (ev) => {
                        const v = emailEl.value || '';
                        if (!allowedRe.test(v)) {
                            // 不許可文字を取り除く（ユーザ体験重視）
                            const cleaned = v.split('').filter(ch => allowedRe.test(ch)).join('');
                            // カーソル位置を維持するために簡易調整
                            const prevPos = emailEl.selectionStart || cleaned.length;
                            emailEl.value = cleaned;
                            try { emailEl.setSelectionRange(Math.min(prevPos, cleaned.length), Math.min(prevPos, cleaned.length)); } catch(e) { /* ignore */ }
                            const errEl = document.getElementById('profile-error');
                            if (errEl) { errEl.textContent = 'メールアドレスに使えない文字が含まれていました。'; errEl.style.display = 'block'; }
                        } else {
                            const errEl = document.getElementById('profile-error');
                            if (errEl) { errEl.style.display = 'none'; errEl.textContent = ''; }
                        }
                    });
                    // 貼り付け時は不正文字を除去してから挿入
                    emailEl.addEventListener('paste', (ev) => {
                        ev.preventDefault();
                        const paste = (ev.clipboardData || window.clipboardData).getData('text') || '';
                        const cleaned = paste.split('').filter(ch => allowedRe.test(ch)).join('');
                        // カーソル位置に挿入（簡易）
                        const start = emailEl.selectionStart || 0;
                        const end = emailEl.selectionEnd || 0;
                        const cur = emailEl.value || '';
                        emailEl.value = cur.slice(0, start) + cleaned + cur.slice(end);
                        // 手動で input イベント発火
                        emailEl.dispatchEvent(new Event('input', { bubbles: true }));
                    });
                }

                // 保存時のクライアント側最終チェック（編集ボタンの保存処理内で使用）
                function isEmailClientValid(email) {
                    if (!email) return false;
                    // 指定の正規表現を使用（RFC に完全準拠するものではないが一般的な形式チェック）
                    return /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(email);
                }

                // helper: 検証コード入力ダイアログとサーバ送信
                async function promptAndVerify(requestId) {
                    // カスタムモーダルを生成してユーザー入力を待つ
                    return new Promise((resolve) => {
                         try {
                            // モーダルが既に存在していれば削除してから作成
                            const existing = document.getElementById('email-verify-modal');
                            if (existing) existing.remove();

                            const overlay = document.createElement('div');
                            overlay.id = 'email-verify-modal';
                            overlay.style.position = 'fixed';
                            overlay.style.left = '0';
                            overlay.style.top = '0';
                            overlay.style.width = '100%';
                            overlay.style.height = '100%';
                            overlay.style.background = 'rgba(0,0,0,0.5)';
                            overlay.style.display = 'flex';
                            overlay.style.alignItems = 'center';
                            overlay.style.justifyContent = 'center';
                            // 高い z-index を設定して他 UI に埋もれないようにする
                            overlay.style.zIndex = String(2147483647);
                            // pointer-events を明示的に有効化
                            overlay.style.pointerEvents = 'auto';
                            // ARIA 属性を付与してアクセシビリティ対応
                            overlay.setAttribute('role', 'dialog');
                            overlay.setAttribute('aria-modal', 'true');
                            overlay.setAttribute('aria-hidden', 'false');

                            const box = document.createElement('div');
                            box.style.background = '#fff';
                            box.style.padding = '20px';
                            box.style.borderRadius = '6px';
                            box.style.maxWidth = '420px';
                            box.style.width = '90%';
                            box.style.boxShadow = '0 8px 24px rgba(0,0,0,0.2)';
                            // フォーカス可能にしてスクリーンリーダー/キーボード操作を改善
                            box.tabIndex = -1;

                            const title = document.createElement('h3');
                            title.textContent = 'メール確認コードの入力';
                            title.style.marginTop = '0';
                            title.style.marginBottom = '8px';
                            box.appendChild(title);

                            const info = document.createElement('div');
                            info.textContent = '送信された6桁の数字コードを入力してください。';
                            info.style.marginBottom = '12px';
                            box.appendChild(info);

                            const input = document.createElement('input');
                            input.type = 'text';
                            input.placeholder = '例: 012345';
                            input.maxLength = 6;
                            input.style.fontSize = '16px';
                            input.style.padding = '8px';
                            input.style.width = '100%';
                            input.style.boxSizing = 'border-box';
                            input.autocomplete = 'one-time-code';
                            box.appendChild(input);

                            const err = document.createElement('div');
                            err.style.color = 'red';
                            err.style.marginTop = '8px';
                            err.style.minHeight = '18px';
                            box.appendChild(err);

                            const btnRow = document.createElement('div');
                            btnRow.style.display = 'flex';
                            btnRow.style.justifyContent = 'flex-end';
                            btnRow.style.marginTop = '12px';

                            const cancelBtn = document.createElement('button');
                            cancelBtn.textContent = 'キャンセル';
                            cancelBtn.style.marginRight = '8px';

                            const submitBtn = document.createElement('button');
                            submitBtn.textContent = '確認';
                            submitBtn.style.fontWeight = 'bold';

                            btnRow.appendChild(cancelBtn);
                            btnRow.appendChild(submitBtn);
                            box.appendChild(btnRow);

                            overlay.appendChild(box);
                            // 常に document.documentElement に追加して、他の stacking context に埋もれないようにする
                            try {
                                document.documentElement.appendChild(overlay);
                            } catch(e) {
                                try { document.body.appendChild(overlay); } catch(ee) { /* ignore */ }
                            }
                            // z-index を重要度付きで設定して確実に前面表示を試みる
                            try { overlay.style.setProperty('z-index', String(2147483647), 'important'); } catch(e) { overlay.style.zIndex = String(2147483647); }
                            // pointer-events を明示してクリック可能に
                            overlay.style.pointerEvents = 'all';
                            // overlay appended to DOM
                            // 追加デバッグ: 実際の computed style と bounding rect を取得して表示確認
                            try {
                                const cs = window.getComputedStyle(overlay);
                                const rect = overlay.getBoundingClientRect();
                                // computed style logged for debugging previously
                            } catch(e) { console.debug('profile_modal: computedStyle/rect failed', e); }
                            try { overlay.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch(e) { /* ignore */ }

                            // フォーカス
                            try { input.setAttribute('inputmode', 'numeric'); input.focus(); box.focus(); } catch(e) { /* ignore focus errors */ }

                            // 表示チェック: 100ms後に要素が可視でない場合はフォールバックで prompt を出す
                            setTimeout(() => {
                                try {
                                    const el = document.getElementById('email-verify-modal');
                                    const visible = el && el.getClientRects && el.getClientRects().length > 0;
                                    const overlayEl = el || overlay;
                                    const isDisplayed = visible && window.getComputedStyle(overlayEl).display !== 'none' && overlayEl.offsetWidth > 0 && overlayEl.offsetHeight > 0;
                                    // overlay visibility check
                                    if (!isDisplayed) {
                                        // フォールバックとして prompt を表示
                                        try {
                                            cleanUp();
                                        } catch(_){}
                                        const code = window.prompt('確認コード（6桁）を入力してください:');
                                        if (code === null) return resolve(false);
                                        if (!/^\d{6}$/.test(code)) { window.alert('コードは6桁の数字で入力してください。'); return resolve(false); }
                                        fetch('/api/me/email-change/verify', { method: 'POST', credentials: 'same-origin', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ requestId: requestId, code: code }) })
                                            .then(r => r.ok ? resolve(true) : (r.text().then(t=>{ window.alert('検証に失敗しました: '+t); resolve(false);}))).catch(_=>{ resolve(false); });
                                    }
                                } catch (e) { /* ignore overlay visibility check errors */ }
                            }, 100);

                            function cleanUp() {
                                try { overlay.remove(); } catch (e) {}
                            }

                            cancelBtn.addEventListener('click', () => {
                                cleanUp();
                                resolve(false);
                            });

                            submitBtn.addEventListener('click', async () => {
                                const code = (input.value || '').trim();
                                if (!/^\d{6}$/.test(code)) {
                                    err.textContent = '6桁の数字コードを入力してください。';
                                    return;
                                }
                                // ボタン二重押し防止
                                submitBtn.disabled = true;
                                cancelBtn.disabled = true;
                                err.textContent = '';
                                try {
                                    const resp = await fetch('/api/me/email-change/verify', { method: 'POST', credentials: 'same-origin', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ requestId: requestId, code: code }) });
                                    if (!resp.ok) {
                                        const text = await resp.text();
                                        err.textContent = '検証に失敗しました: ' + text;
                                        submitBtn.disabled = false;
                                        cancelBtn.disabled = false;
                                        return;
                                    }
                                    cleanUp();
                                    resolve(true);
                                } catch (e) {
                                    err.textContent = '検証通信エラー: ' + (e && e.message ? e.message : e);
                                    submitBtn.disabled = false;
                                    cancelBtn.disabled = false;
                                }
                            });

                            // Enter キーで送信
                            input.addEventListener('keydown', (ev) => {
                                if (ev.key === 'Enter') { ev.preventDefault(); submitBtn.click(); }
                                if (ev.key === 'Escape') { ev.preventDefault(); cancelBtn.click(); }
                            });

                        } catch (e) {
                            // 何らかの理由でモーダルが作れなければフォールバックで prompt を使用
                            try {
                                const code = window.prompt('確認コード（6桁）を入力してください:');
                                if (code === null) return resolve(false);
                                if (!/^\d{6}$/.test(code)) return resolve(false);
                                try {
                                    fetch('/api/me/email-change/verify', { method: 'POST', credentials: 'same-origin', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ requestId: requestId, code: code }) })
                                        .then(r => r.ok ? resolve(true) : resolve(false)).catch(_ => resolve(false));
                                } catch (ee) { return resolve(false); }
                            } catch (ee) { return resolve(false); }
                        }
                    });
                }

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
                                // クライアント側の形式チェック。サーバ側バリデーションは別途必須。
                                if (!isEmailClientValid(email)) {
                                    const errEl = document.getElementById('profile-error');
                                    if (errEl) { errEl.textContent = 'メールアドレスの形式が正しくありません。'; errEl.style.display = 'block'; }
                                    return;
                                }
                                const resp2 = await fetch('/api/me/profile', { method: 'PUT', credentials: 'same-origin', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ name: name, email: email }) });
                                if (!resp2.ok) { const t = await resp2.text(); const errEl = document.getElementById('profile-error'); if (errEl) { errEl.textContent = '保存に失敗しました: ' + t; errEl.style.display = 'block'; } return; }
                                // サーバが検証フロー開始を指示する場合、verificationRequired フラグと requestId が返る
                                const json = await resp2.json();
                                // server response received
                                if (json && json.verificationRequired) {
                                    const reqId = json.requestId;
                                    const errEl = document.getElementById('profile-error');
                                    if (errEl) { errEl.textContent = '確認コードを新しいメール宛に送信しました。受信後に入力してください。'; errEl.style.display = 'block'; }
                                    // プロンプトで入力を促す（UIの拡張は今後追加）
                                    const verified = await promptAndVerify(reqId);
                                    if (verified) {
                                        // 成功時は UI を更新して終了
                                        emailEl.setAttribute('readonly','true'); emailEl.setAttribute('data-editable','false'); editBtn.textContent = '編集';
                                        if (errEl) { errEl.style.display='none'; errEl.textContent=''; }
                                    } else {
                                        // 失敗時は編集モードを解除せず、保留状態を維持する（ユーザーが後で検証可能）
                                        // ここでは編集をロックしておかない（ユーザーが再送を行えるようにする）
                                    }
                                    return;
                                }
                                // 通常の保存成功フロー
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













