(function(){
    'use strict';

    // ログアウト処理モジュール
    window.Logout = {
        init: function() {
            try {
                // expose global confirmLogout for compatibility
                window.confirmLogout = this.confirmLogout.bind(this);
                const lb = document.getElementById('logout-btn');
                if (lb && !lb.__edamame_logout_attached__) {
                    lb.addEventListener('click', (e) => { e.preventDefault(); window.confirmLogout(); });
                    lb.__edamame_logout_attached__ = true;
                }
            } catch(e) { console.warn('Logout.init error', e); }
        },

        confirmLogout: function() {
            if (!confirm('ログアウトしますか？')) return;
            try { window.location.href = '/logout'; } catch(e) { console.warn('confirmLogout fallback navigation error', e); }
        }
    };

})();

