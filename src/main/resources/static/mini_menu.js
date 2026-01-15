(function(){
  'use strict';

  function create(menuEl){
    if (!menuEl) return null;
    let outsideHandlerAttached = false;
    const api = {
      show(opts){
        if (!opts) return;
        const { x=0, y=0, items=[] } = opts;
        buildItems(items);
        position(x, y);
        menuEl.classList.add('show');
        menuEl.style.display = 'block';
        menuEl.style.visibility = 'visible';
        menuEl.setAttribute('aria-hidden','false');
        menuEl.inert = false;
        if (!outsideHandlerAttached){
          document.addEventListener('pointerdown', onOutside, true);
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
      document.removeEventListener('pointerdown', onOutside, true);
      document.removeEventListener('keydown', onEscape, true);
      outsideHandlerAttached = false;
    }

    function onOutside(ev){
      if (!menuEl.classList.contains('show')) return;
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
      let left = x + scrollX - w + 12;
      let top = y + scrollY + 6;
      const maxLeft = scrollX + window.innerWidth - w - padding;
      const minLeft = scrollX + padding;
      const maxTop = scrollY + window.innerHeight - h - padding;
      const minTop = scrollY + padding;
      left = Math.min(Math.max(left, minLeft), maxLeft);
      if (top > maxTop) top = y + scrollY - h - 6;
      top = Math.min(Math.max(top, minTop), maxTop);
      menuEl.style.left = left + 'px';
      menuEl.style.top = top + 'px';
    }

    // stop propagation inside
    menuEl.addEventListener('click', e => e.stopPropagation());

    return api;
  }

  window.MiniMenu = { create };
})();
