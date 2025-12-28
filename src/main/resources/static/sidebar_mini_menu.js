(function(){
    'use strict';

    // サイドバーのミニメニュー処理をモジュール化
    window.SidebarMini = {
        init: function() {
            try {
                const userBtn = document.getElementById('sidebar-user-button');
                const mini = document.getElementById('sidebar-mini-menu');
                if (userBtn && mini) {
                    bindSidebarUserButton(userBtn, mini);
                } else {
                    // 要素がまだない場合はデリゲーションでフォールバック
                    document.addEventListener('click', function delegatedSidebarClick(ev){
                        const btn = ev.target.closest && ev.target.closest('#sidebar-user-button');
                        if (!btn) return;
                        ev.stopPropagation();
                        const m = document.getElementById('sidebar-mini-menu');
                        if (!m) { return; }
                        bindSidebarUserButton(btn, m);
                        btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
                    });
                }

                function bindSidebarUserButton(userBtnEl, miniEl) {
                    if (userBtnEl.__edamame_mini_bound__) return;
                    userBtnEl.addEventListener('click', (ev) => {
                        ev.stopPropagation();
                        const isShown = miniEl.classList.contains('show');
                        if (isShown) {
                            miniEl.classList.remove('show');
                            miniEl.style.display = 'none';
                            miniEl.style.left = ''; miniEl.style.top = ''; miniEl.style.right = ''; miniEl.style.bottom = '';
                            userBtnEl.setAttribute('aria-expanded','false');
                        } else {
                            miniEl.classList.add('show');
                            miniEl.style.display = 'block';
                            miniEl.style.zIndex = '1600';
                            miniEl.style.left = ''; miniEl.style.top = ''; miniEl.style.right = ''; miniEl.style.bottom = '';
                            try {
                                const parent = miniEl.parentElement;
                                const avatarRect = userBtnEl.getBoundingClientRect();
                                const parentRect = parent.getBoundingClientRect();
                                const miniRect = miniEl.getBoundingClientRect();
                                miniEl.style.right = 'auto';
                                miniEl.style.bottom = 'auto';
                                let left = (avatarRect.right - parentRect.left) - miniRect.width;
                                if (left < 4) left = 4;
                                let top = (avatarRect.top - parentRect.top) - miniRect.height - 6;
                                if (top < 0) top = (avatarRect.bottom - parentRect.top) + 6;
                                miniEl.style.left = left + 'px';
                                miniEl.style.top = top + 'px';
                            } catch(e) {
                                // fallback
                            }
                        }
                        userBtnEl.setAttribute('aria-expanded','true');
                    });
                    miniEl.addEventListener('click', (e) => { e.stopPropagation(); });
                    document.addEventListener('click', (ev) => {
                        if (!userBtnEl.contains(ev.target) && !miniEl.contains(ev.target)) {
                            miniEl.classList.remove('show');
                            miniEl.style.left = ''; miniEl.style.top = ''; miniEl.style.right = ''; miniEl.style.bottom = '';
                            miniEl.style.display = 'none';
                            userBtnEl.setAttribute('aria-expanded','false');
                        }
                    });
                    attachMiniItemHandlers(miniEl);
                    userBtnEl.__edamame_mini_bound__ = true;
                }

                function attachMiniItemHandlers(miniEl) {
                    if (!miniEl) return;
                    if (miniEl.__edamame_items_bound__) return;
                    const items = miniEl.querySelectorAll('.mini-menu-item');
                    items.forEach(it => {
                        if (it.__edamame_item_bound__) return;
                        it.addEventListener('click', (e) => {
                            e.stopPropagation();
                            const id = it.id;
                            if (id === 'mini-change-password') window.openPasswordModal && window.openPasswordModal();
                            else if (id === 'mini-profile') window.openProfileModal && window.openProfileModal(true);
                        });
                        it.__edamame_item_bound__ = true;
                    });
                    miniEl.__edamame_items_bound__ = true;
                }

            } catch(e) { console.warn('SidebarMini.init error', e); }
        }
    };
})();

