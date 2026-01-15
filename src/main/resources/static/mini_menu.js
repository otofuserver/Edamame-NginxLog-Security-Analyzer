(function(){
  'use strict';

  function create(menuEl){
    if (!menuEl) return null;
    let outsideHandlerAttached = false;
    let suppressOutsideCount = 0;
    const outsideEvents = ['pointerdown','click'];
    const api = {
      show(opts){
        if (!opts) return;
        const { x=0, y=0, items=[] } = opts;
        // 既に表示中なら閉じるだけ（トグル動作）。再表示は次のクリックで行う。
        if (menuEl.classList.contains('show')) { hideMenu(); return; }
        // 既存の表示状態を確実にリセットしてから開く
        hideMenu();
        buildItems(items);
        position(x, y);
        menuEl.classList.add('show');
        menuEl.style.display = 'block';
        menuEl.style.visibility = 'visible';
        menuEl.setAttribute('aria-hidden','false');
        menuEl.inert = false;
        // このクリック起因の外側イベントは1回無視して、即時閉じを防ぐ
        suppressOutsideCount = outsideEvents.length; // pointerdown/click 1セットを無視
        if (!outsideHandlerAttached){
          // バブル段階で監視して、同一クリックでの再開を防止
          outsideEvents.forEach(ev => document.addEventListener(ev, onOutside, false));
          document.addEventListener('keydown', onEscape, true);
          outsideHandlerAttached = true;
        }
      },
      hide(){ hideMenu(); },
    };

    function hideMenu(){
      if (menuEl.contains(document.activeElement)) document.activeElement.blur();
      menuEl.classList.remove('show');
      menuEl.style.display = 'none';
      menuEl.style.visibility = 'hidden';
      menuEl.setAttribute('aria-hidden','true');
      menuEl.inert = true;
      outsideEvents.forEach(ev => document.removeEventListener(ev, onOutside, false));
      document.removeEventListener('keydown', onEscape, true);
      outsideHandlerAttached = false;
    }

    function onOutside(ev){
      if (!menuEl.classList.contains('show')) return;
      if (suppressOutsideCount > 0) { suppressOutsideCount--; return; }
      if (!menuEl.contains(ev.target)) hideMenu();
    }
    function onEscape(ev){ if (ev.key === 'Escape') hideMenu(); }

    function buildItems(items){
      const card = menuEl.querySelector('.mini-menu-card');
      if (!card) return;
      card.innerHTML = '';
      items.forEach(item => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'mini-menu-item';
        btn.textContent = item.label || '';
        if (item.disabled) btn.disabled = true;
        if (item.hidden) btn.style.display = 'none';
        btn.addEventListener('click', ev => {
          ev.stopPropagation();
          if (btn.disabled) return;
          if (typeof item.onClick === 'function') item.onClick();
          hideMenu();
        });
        card.appendChild(btn);
      });
    }

    function position(x,y){
      menuEl.style.visibility = 'hidden';
      menuEl.style.display = 'block';
      const w = menuEl.offsetWidth;
      const h = menuEl.offsetHeight;
      const scrollX = window.scrollX || document.documentElement.scrollLeft;
      const scrollY = window.scrollY || document.documentElement.scrollTop;
      const padding = 8;
      const offsetX = 10; // クリック点から右へ少しずらす
      const offsetY = 6;  // クリック点から下へ少しずらす

      const pageX = Number.isFinite(x) ? x : 0;
      const pageY = Number.isFinite(y) ? y : 0;

      const viewportRight = scrollX + window.innerWidth - padding;
      const viewportBottom = scrollY + window.innerHeight - padding;
      const viewportLeft = scrollX + padding;
      const viewportTop = scrollY + padding;

      // 右下に配置し、はみ出す場合はその辺でクランプ（反転はしない）
      let left = pageX + offsetX;
      left = Math.min(left, viewportRight - w);
      left = Math.max(left, viewportLeft);

      let top = pageY + offsetY;
      top = Math.min(top, viewportBottom - h);
      top = Math.max(top, viewportTop);

      menuEl.style.left = left + 'px';
      menuEl.style.top = top + 'px';
    }

    // stop propagation inside
    menuEl.addEventListener('click', e => e.stopPropagation());

    return api;
  }

  window.MiniMenu = { create };
})();
