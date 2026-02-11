(function(){
    'use strict';

    // サイドバー「設定」ボタンからミニメニューを開く
    window.SidebarSettingsMenu = {
        init(){
            try {
                const btn = document.getElementById('sidebar-settings-button');
                const menuEl = document.getElementById('sidebar-settings-mini-menu');
                if (!btn || !menuEl || !window.MiniMenu || typeof window.MiniMenu.create !== 'function') return;
                if (btn.__edamame_settings_bound__) return;

                const menu = window.MiniMenu.create(menuEl);
                if (!menu) return;

                const operatorEl = document.getElementById('current-user-operator');
                const adminEl = document.getElementById('current-user-admin');
                const isAdmin = adminEl && adminEl.dataset.isAdmin === 'true';
                const canOperate = isAdmin || (operatorEl && operatorEl.dataset.isOperator === 'true');

                btn.addEventListener('click', (ev) => {
                    ev.preventDefault();
                    ev.stopPropagation();
                    const isOpen = menuEl.classList.contains('show');
                    if (isOpen) {
                        menu.hide();
                        btn.setAttribute('aria-expanded', 'false');
                        return;
                    }
                    const rect = btn.getBoundingClientRect();
                    const x = rect.left + (rect.width / 2);
                    const y = rect.bottom;
                    btn.setAttribute('aria-expanded', 'true');
                    menu.show({
                        x,
                        y,
                        canOperate,
                        items: [
                            {
                                label: 'URL指定非監視設定',
                                requirePermission: true,
                                onClick: () => {
                                    try {
                                        if (window.navigateTo) {
                                            window.navigateTo('url_suppression', true);
                                        } else {
                                            window.location.href = '/main?view=url_suppression';
                                        }
                                    } catch (e) {
                                        window.location.href = '/main?view=url_suppression';
                                    }
                                }
                            },
                            {
                                label: 'ホワイトリスト設定',
                                requirePermission: true,
                                disabled: !isAdmin,
                                onClick: () => {
                                    try {
                                        if (window.navigateTo) {
                                            window.navigateTo('whitelist_settings', true);
                                        } else {
                                            window.location.href = '/main?view=whitelist_settings';
                                        }
                                    } catch (e) {
                                        window.location.href = '/main?view=whitelist_settings';
                                    }
                                }
                            }
                        ]
                     });
                 });

                // 外側クリックで aria を戻すために hide をフック
                document.addEventListener('click', (ev) => {
                    if (!btn.contains(ev.target) && !menuEl.contains(ev.target)) {
                        btn.setAttribute('aria-expanded', 'false');
                    }
                });

                btn.__edamame_settings_bound__ = true;
            } catch (e) {
                console.warn('SidebarSettingsMenu.init error', e);
            }
        }
    };
})();
