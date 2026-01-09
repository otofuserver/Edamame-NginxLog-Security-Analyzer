(function(){
    'use strict';

    // --- クライアント時計 ---
    function pad2(n) { return String(n).padStart(2, '0'); }
    function formatYMDHMS(d) {
        return d.getFullYear() + '-' + pad2(d.getMonth()+1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }

    function startClock() {
        try {
            if (window.__edamame_clock_started__) return;
            function tick() {
                const now = new Date();
                const s = formatYMDHMS(now);
                const els = document.querySelectorAll('.current-time');
                els.forEach(el => { try { el.textContent = s; } catch(_) {} });
            }
            tick();
            window.__edamame_clock_interval__ = setInterval(tick, 1000);
            window.__edamame_clock_started__ = true;
            if (window.dbg) window.dbg('client clock started');
        } catch(e) { console.warn('startClock error', e); }
    }

    // グローバルに公開
    window.startClock = startClock;
    window.formatYMDHMS = formatYMDHMS;
})();

