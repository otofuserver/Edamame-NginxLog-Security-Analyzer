(function(){
    'use strict';

    /**
     * 一覧画面の共通ロジックを提供するシンプルなヘルパー。
     * URLクエリを単一のソース・オブ・トゥルースとして、検索/ソート/ページ位置を復元・反映する。
     * config には以下を渡す:
     *  - headerSelector: ソート可能ヘッダ(th)のセレクタ。data-column にソートキーを入れる
     *  - pagerEl: ページネーションを描画する要素
     *  - fetcher(params): { items, total, page, size, totalPages } を返す Promise 関数
     *  - renderRows(items, state): 行描画を行うコールバック
     *  - applyStateToUi(state): 入力値や選択値へ state を反映するコールバック
     *  - extractFilters(): オプションフィルタをオブジェクトで返すコールバック
     *  - onStateChange?(state): 状態更新後に呼ばれるオプションフック
     */
    function createListView(config){
        if (!config || typeof config.fetcher !== 'function' || typeof config.renderRows !== 'function') {
            throw new Error('ListViewCore: fetcher と renderRows は必須です');
        }
        const state = {
            q: '',
            sort: null,
            order: 'asc',
            page: 1,
            size: 20,
            total: 0,
            totalPages: 1
        };

        function readFromUrl(){
            const params = new URLSearchParams(window.location.search);
            state.q = params.get('q') || '';
            state.sort = params.get('sort') || state.sort;
            const order = params.get('order');
            if (order === 'desc' || order === 'asc') state.order = order;
            const page = Number(params.get('page'));
            if (!Number.isNaN(page) && page > 0) state.page = page;
        }

        function writeToUrl(){
            const params = new URLSearchParams(window.location.search);
            params.set('page', String(state.page));
            params.set('size', String(state.size));
            if (state.q) params.set('q', state.q); else params.delete('q');
            if (state.sort) params.set('sort', state.sort); else params.delete('sort');
            params.set('order', state.order);
            const filters = typeof config.extractFilters === 'function' ? config.extractFilters() : {};
            Object.entries(filters || {}).forEach(([k,v])=>{
                if (v === undefined || v === null || v === '') params.delete(k); else params.set(k, v);
            });
            const newUrl = window.location.pathname + '?' + params.toString();
            window.history.replaceState({}, '', newUrl);
        }

        function updateSortIndicators(){
            if (!config.headerSelector) return;
            document.querySelectorAll(config.headerSelector).forEach(th => {
                const col = th.getAttribute('data-column') || '';
                const active = col === state.sort;
                if (!th.dataset.labelOriginal) th.dataset.labelOriginal = th.textContent.trim();
                const base = th.dataset.labelOriginal;
                const arrow = active ? (state.order === 'desc' ? ' ▼' : ' ▲') : '';
                th.textContent = base + arrow;
                th.classList.toggle('active-sort', active);
            });
        }

        function renderPager(){
            const el = config.pagerEl;
            if (!el) return;
            el.innerHTML = '';
            let totalPages = state.totalPages;
            if (!totalPages || totalPages < 1) {
                const calc = Math.ceil(state.total / state.size);
                totalPages = calc && calc > 0 ? calc : 1;
            }
            if (totalPages <= 1) return;
            const mk = (label, target, disabled) => {
                const btn = document.createElement('button');
                btn.textContent = label;
                btn.disabled = !!disabled;
                btn.addEventListener('click', () => { if (!btn.disabled) reload(target); });
                return btn;
            };
            el.appendChild(mk('«', 1, state.page <= 1));
            el.appendChild(mk('‹', Math.max(1, state.page - 1), state.page <= 1));
            const info = document.createElement('span');
            info.textContent = ` ${state.page} / ${totalPages} （全 ${state.total} 件） `;
            el.appendChild(info);
            el.appendChild(mk('›', Math.min(totalPages, state.page + 1), state.page >= totalPages));
            el.appendChild(mk('»', totalPages, state.page >= totalPages));
        }

        async function reload(nextPage){
            if (nextPage) state.page = nextPage;
            writeToUrl();
            try {
                const filters = typeof config.extractFilters === 'function' ? config.extractFilters() : {};
                const data = await config.fetcher(Object.assign({
                    q: state.q,
                    sort: state.sort,
                    order: state.order,
                    page: state.page,
                    size: state.size
                }, filters || {}));
                const items = data && data.items ? data.items : [];
                state.total = (data && typeof data.total === 'number') ? data.total : 0;
                state.page = (data && typeof data.page === 'number' && data.page > 0) ? data.page : state.page;
                state.size = (data && typeof data.size === 'number' && data.size > 0) ? data.size : state.size;
                state.totalPages = (data && typeof data.totalPages === 'number' && data.totalPages > 0)
                    ? data.totalPages
                    : Math.max(1, Math.ceil(state.total / state.size));
                config.renderRows(items, state);
                renderPager();
                updateSortIndicators();
                if (typeof config.onStateChange === 'function') config.onStateChange(state);
            } catch (e) {
                console.error('ListViewCore reload error', e);
            }
        }

        function bindHeaderSort(){
            if (!config.headerSelector) return;
            document.querySelectorAll(config.headerSelector).forEach(th => {
                th.__list_view_core__ && th.removeEventListener('click', th.__list_view_core__);
                th.__list_view_core__ = function(){
                    const col = th.getAttribute('data-column');
                    if (!col) return;
                    if (state.sort === col) state.order = state.order === 'asc' ? 'desc' : 'asc';
                    else { state.sort = col; state.order = 'asc'; }
                    state.page = 1;
                    reload(1);
                };
                th.addEventListener('click', th.__list_view_core__);
            });
        }

        function bindSearch(inputEl){
            if (!inputEl) return;
            let timer = null; const DEBOUNCE = 250;
            const schedule = () => {
                if (timer) clearTimeout(timer);
                timer = setTimeout(() => { state.q = inputEl.value || ''; state.page = 1; reload(1); }, DEBOUNCE);
            };
            inputEl.addEventListener('input', schedule);
            inputEl.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); schedule(); } });
        }

        function init(){
            readFromUrl();
            if (typeof config.applyStateToUi === 'function') config.applyStateToUi(state);
            bindHeaderSort();
            if (config.searchInput) bindSearch(config.searchInput);
            reload(state.page);
        }

        return { init, reload, state };
    }

    window.ListViewCore = { createListView };
})();
