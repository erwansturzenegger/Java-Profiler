<script>
  import { store } from './store.svelte.js';
  import {
    TYPES, TYPE_LABEL, SPAN_TYPES, fmtMs, fmtTime, simple, shortFrame, subject,
    buildTree, byEntity, byProperty, byCaller, bySql, detectNPlusOne, causeOf, eventMatcher,
  } from './model.js';
  import Tags from './Tags.svelte';
  import TreeNode from './TreeNode.svelte';
  import EventPanel from './EventPanel.svelte';
  import Link from './Link.svelte';
  import { router, to } from './router.svelte.js';

  // Onglet et filtre portés par l'URL : précédent / suivant les restaurent
  const tab = $derived(router.route.tab || 'summary');
  let showAccess = $state(true);
  let showLoad = $state(true);
  let eventFilter = $state(router.route.q || '');
  $effect(() => {
    eventFilter = router.route.q || '';
  });

  function setTab(id) {
    router.patch({ tab: id === 'summary' ? undefined : id, ev: undefined });
  }

  function onFilter(v) {
    eventFilter = v;
    router.patch({ q: v || undefined }, true);
  }
  let typeFilter = $state(new Set());
  const ROW_LIMIT = 1500;

  const r = $derived(store.selected);
  const events = $derived(r ? r.events : []);

  const nplus = $derived(detectNPlusOne(events));
  const entities = $derived(tab === 'entities' || tab === 'summary' ? byEntity(events) : []);
  const props = $derived(tab === 'props' ? byProperty(events) : []);
  const callers = $derived(tab === 'callers' || tab === 'summary' ? byCaller(events) : []);
  const sqls = $derived(tab === 'sql' || tab === 'summary' ? bySql(events) : []);
  const tree = $derived(tab === 'tree' ? buildTree(events, { showAccess, showLoad }) : []);
  const lazyErrors = $derived(events.filter((e) => e.type === 'LAZY_ERROR'));
  const lazyAccess = $derived(events.filter((e) => e.type === 'ACCESS' && e.state));

  const filtered = $derived.by(() => {
    if (tab !== 'events') return [];
    const match = eventMatcher(eventFilter);
    const out = [];
    for (const e of events) {
      if (typeFilter.size && !typeFilter.has(e.type)) continue;
      if (match && !match(e)) continue;
      out.push(e);
    }
    return out;
  });

  function showEvents(text, types) {
    typeFilter = new Set(types || []);
    router.patch({ tab: 'events', q: text || undefined, ev: undefined });
  }

  function toggleType(t) {
    const s = new Set(typeFilter);
    s.has(t) ? s.delete(t) : s.add(t);
    typeFilter = s;
  }

  const tabs = $derived([
    ['summary', 'Synthèse', nplus.length + lazyErrors.length || null],
    ['tree', 'Arbre', null],
    ['entities', 'Entités', null],
    ['props', 'Propriétés', null],
    ['callers', 'Appelants', null],
    ['sql', 'SQL', r?.counts.SQL || null],
    ['events', 'Événements', events.length],
  ]);
</script>

{#if r}
  <div class="head">
    <div class="title">
      <span class="method">{r.method || ''}</span>
      <span class="uri mono">{r.uri || ''}</span>
    </div>
    <div class="meta">
      <span class="mono">{fmtTime(r.t)}</span>
      {#if r.done && r.dur != null}<span>Durée <b class="mono">{fmtMs(r.dur)}</b></span>{/if}
      {#if r.status}<span>HTTP <b class="mono">{r.status}</b></span>{/if}
      {#if r.counts.SQL}<span>SQL <b class="mono">{r.counts.SQL}</b> · {fmtMs(r.sqlTime)}</span>{/if}
      {#if r.counts.PROXY || r.counts.COLLECTION}
        <span>Initialisations lazy <b class="mono">{(r.counts.PROXY || 0) + (r.counts.COLLECTION || 0)}</b> · {fmtMs(r.initTime)}</span>
      {/if}
      {#if r.counts.LOAD}<span>Entités chargées <b class="mono">{r.counts.LOAD}</b></span>{/if}
      {#if r.source}<span class="mono source">{r.source}</span>{/if}
      <span class="faint">{r.agent}</span>
    </div>
    {#if r.error}<div class="error mono">{r.error}</div>{/if}
    {#if r.truncated}
      <div class="warn small">Requête très volumineuse : seuls ses {events.length.toLocaleString('fr-CH')} premiers événements sont chargés (sur {r.n.toLocaleString('fr-CH')}).</div>
    {/if}
  </div>

  <nav class="tabs">
    {#each tabs as [id, label, n]}
      <button class:active={tab === id} onclick={() => setTab(id)}>
        {label}{#if n}<span class="n">{n}</span>{/if}
      </button>
    {/each}
  </nav>

  <div class="body" class:with-panel={store.selectedEvent}>
    <div class="content">
      {#if tab === 'summary'}
        <div class="summary">
          <section>
            <h3>N+1 probables <span class="muted">(même relation initialisée ≥ 3 fois au même endroit)</span></h3>
            {#if nplus.length}
              <table class="grid">
                <thead><tr><th class="num">×</th><th>Quoi</th><th>Où</th><th class="num">Temps</th></tr></thead>
                <tbody>
                  {#each nplus as n}
                    <tr class="clickable" onclick={() => (store.selectedEvent = n.sample)}>
                      <td class="num"><b>{n.count}</b></td>
                      <td><span class="badge t-{n.type}">{n.type}</span> <span class="mono">{n.what}</span>
                        {#if n.ids.length}<div class="faint mono small">ids {n.ids.slice(0, 12).join(', ')}{n.ids.length > 12 ? '…' : ''}</div>{/if}
                      </td>
                      <td class="mono small">
                        {#if n.el}<div class="el">{n.el}</div>{/if}
                        <div>{shortFrame(n.caller)}</div>
                        {#if n.trigger}<div class="faint">via {n.trigger}</div>{/if}
                      </td>
                      <td class="num">{n.time ? fmtMs(n.time) : ''}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            {:else}
              <p class="muted">Aucun.</p>
            {/if}
          </section>

          <section>
            <h3>Accès lazy en échec <span class="muted">(LazyInitializationException)</span></h3>
            {#if lazyErrors.length}
              <table class="grid">
                <tbody>
                  {#each lazyErrors as e}
                    <tr class="clickable" onclick={() => (store.selectedEvent = e)}>
                      <td><span class="mono">{subject(e)}</span></td>
                      <td class="mono small">{#if e.el}<div class="el">{e.el}</div>{/if}{shortFrame(e.caller)}{#if e.trigger}<span class="via faint">via {e.trigger}</span>{/if}</td>
                      <td class="small">{e.phase || ''}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            {:else}
              <p class="muted">Aucun.</p>
            {/if}
          </section>

          {#if lazyAccess.length}
            <section>
              <h3>Getters ayant renvoyé une relation non initialisée <span class="muted">({lazyAccess.length})</span></h3>
              <p class="muted small">La relation sera chargée au premier usage (PROXY / COLLECTION) ou lèvera une LazyInitializationException si l'entité est détachée.</p>
              <button onclick={() => showEvents('', ['ACCESS'])}>Voir les accès</button>
            </section>
          {/if}

          <div class="two">
            <section>
              <h3>Entités</h3>
              <table class="grid">
                <thead><tr><th>Entité</th><th class="num">Load</th><th class="num">Proxy</th><th class="num">Coll.</th><th class="num">Accès</th></tr></thead>
                <tbody>
                  {#each entities.slice(0, 12) as x}
                    <tr class="clickable" onclick={() => showEvents(simple(x.entity))}>
                      <td class="mono"><Link to={to.type(x.entity)} title="Vue du type (Ctrl+clic : nouvel onglet)">{simple(x.entity)}</Link></td>
                      <td class="num">{x.LOAD || ''}</td>
                      <td class="num">{x.PROXY || ''}</td>
                      <td class="num">{x.COLLECTION || ''}</td>
                      <td class="num">{x.ACCESS || ''}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </section>
            <section>
              <h3>Appelants</h3>
              <table class="grid">
                <thead><tr><th>Appelant</th><th class="num">SQL</th><th class="num">Init.</th><th class="num">Temps</th></tr></thead>
                <tbody>
                  {#each callers.slice(0, 12) as x}
                    <tr class="clickable" onclick={() => showEvents(x.caller === '(inconnu)' ? '' : x.caller)}>
                      <td class="mono small" title={x.caller}>{shortFrame(x.caller)}</td>
                      <td class="num">{x.SQL || ''}</td>
                      <td class="num">{x.PROXY + x.COLLECTION || ''}</td>
                      <td class="num">{x.time ? fmtMs(x.time) : ''}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </section>
          </div>
        </div>
      {:else if tab === 'tree'}
        <div class="tree-opts">
          <label><input type="checkbox" bind:checked={showLoad} /> LOAD</label>
          <label><input type="checkbox" bind:checked={showAccess} /> ACCESS</label>
          <span class="faint small">Les spans (Proxy, Collection, Util, SQL) contiennent ce qu'ils ont déclenché.</span>
        </div>
        <div class="tree">
          {#each tree.slice(0, ROW_LIMIT) as node}
            <TreeNode {node} depth={0} t0={r.t} />
          {/each}
          {#if tree.length > ROW_LIMIT}<div class="muted more">… {tree.length - ROW_LIMIT} nœuds de plus</div>{/if}
        </div>
      {:else if tab === 'entities'}
        <table class="grid">
          <thead>
            <tr><th>Entité</th><th class="num">Load</th><th class="num" title="LOAD lazy implicites">dont lazy</th><th class="num" title="LOAD voulus : requête, find, refresh, initialize…">dont voulus</th><th class="num">Proxy init</th><th class="num">Collection init</th><th class="num">Accès</th><th class="num">Accès lazy</th><th class="num">Lazy exc.</th><th class="num">Instances</th><th class="num">Temps init</th></tr>
          </thead>
          <tbody>
            {#each entities as x}
              <tr class="clickable" onclick={() => showEvents(simple(x.entity))}>
                <td class="mono" title={x.entity}><Link to={to.type(x.entity)}>{simple(x.entity)}</Link></td>
                <td class="num">{x.LOAD || ''}</td>
                <td class="num lazy-n">{x.lazyLoad || ''}</td>
                <td class="num">{x.wantedLoad || ''}</td>
                <td class="num">{x.PROXY || ''}</td>
                <td class="num">{x.COLLECTION || ''}</td>
                <td class="num">{x.ACCESS || ''}</td>
                <td class="num">{x.lazyAccess || ''}</td>
                <td class="num err">{x.LAZY_ERROR || ''}</td>
                <td class="num">{x.ids.size || ''}</td>
                <td class="num">{x.time ? fmtMs(x.time) : ''}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      {:else if tab === 'props'}
        <table class="grid">
          <thead>
            <tr><th>Propriété</th><th class="num">Accès</th><th class="num">dont lazy</th><th class="num">Proxy init</th><th class="num">Coll. init</th><th class="num">Lazy exc.</th><th class="num">Appelants</th><th class="num">Temps</th></tr>
          </thead>
          <tbody>
            {#each props as x}
              <tr class="clickable" onclick={() => showEvents(x.prop, [])}>
                <td class="mono">{simple(x.entity)}.<b>{x.prop}</b></td>
                <td class="num">{x.ACCESS || ''}</td>
                <td class="num">{x.lazy || ''}</td>
                <td class="num">{x.PROXY || ''}</td>
                <td class="num">{x.COLLECTION || ''}</td>
                <td class="num err">{x.LAZY_ERROR || ''}</td>
                <td class="num">{x.callers.size}</td>
                <td class="num">{x.time ? fmtMs(x.time) : ''}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      {:else if tab === 'callers'}
        <table class="grid">
          <thead>
            <tr><th>Appelant</th><th class="num">SQL</th><th class="num">Load</th><th class="num">Proxy</th><th class="num">Coll.</th><th class="num">Accès</th><th class="num">Util</th><th class="num">Lazy exc.</th><th class="num">Temps</th></tr>
          </thead>
          <tbody>
            {#each callers as x}
              <tr class="clickable" onclick={() => showEvents(x.caller === '(inconnu)' ? '' : x.caller)}>
                <td class="mono small" title={x.caller}>
                  {shortFrame(x.caller)}
                  {#if x.els.size}<div class="el">{[...x.els].slice(0, 3).join('  ')}</div>{/if}
                  {#if x.triggers.size}<div class="faint">via {[...x.triggers].slice(0, 4).join(', ')}</div>{/if}
                </td>
                <td class="num">{x.SQL || ''}</td>
                <td class="num">{x.LOAD || ''}</td>
                <td class="num">{x.PROXY || ''}</td>
                <td class="num">{x.COLLECTION || ''}</td>
                <td class="num">{x.ACCESS || ''}</td>
                <td class="num">{x.UTIL || ''}</td>
                <td class="num err">{x.LAZY_ERROR || ''}</td>
                <td class="num">{x.time ? fmtMs(x.time) : ''}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      {:else if tab === 'sql'}
        <table class="grid">
          <thead><tr><th class="num">×</th><th>Requête (normalisée)</th><th class="num">Total</th><th class="num">Max</th><th>Appelants</th></tr></thead>
          <tbody>
            {#each sqls as x}
              <tr class="clickable" class:hot={x.count >= 3} onclick={() => (store.selectedEvent = x.sample)}>
                <td class="num"><b>{x.count}</b></td>
                <td class="mono small sql">{x.sql}</td>
                <td class="num">{x.time ? fmtMs(x.time) : ''}</td>
                <td class="num">{fmtMs(x.max)}</td>
                <td class="mono small">{[...x.callers].slice(0, 3).map(shortFrame).join(', ')}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      {:else if tab === 'events'}
        <div class="ev-toolbar">
          <input type="search" placeholder="Filtrer : entité, propriété, id, appelant, EL… #tag" value={eventFilter} oninput={(e) => onFilter(e.target.value)} />
          <div class="chips">
            {#each TYPES as t}
              <button class="badge t-{t}" class:off={typeFilter.size && !typeFilter.has(t)} onclick={() => toggleType(t)}>{TYPE_LABEL[t]}</button>
            {/each}
          </div>
        </div>
        <table class="grid">
          <thead><tr><th class="num">+ms</th><th>Type</th><th>Sujet</th><th>Appelant / EL</th><th>Phase</th><th class="num">Durée</th></tr></thead>
          <tbody>
            {#each filtered.slice(0, ROW_LIMIT) as e (e.id)}
              <tr class="clickable" class:sel={store.selectedEvent === e} onclick={() => (store.selectedEvent = e)}>
                <td class="num faint">{Math.max(0, e.t - r.t)}</td>
                <td><span class="badge t-{e.type}">{e.type}</span></td>
                <td class="mono small subj" title={subject(e)}>
                  {subject(e)}
                  {#if e.state}<span class="badge t-PROXY">{e.state}</span>{/if}
                  {#if causeOf(e)}<span class="badge {causeOf(e).cls}" title={causeOf(e).detail}>{causeOf(e).label}</span>{/if}
                  {#if e.error && e.type !== 'LAZY_ERROR'}<span class="badge t-LAZY_ERROR">erreur</span>{/if}
                  <Tags event={e} links={false} />
                </td>
                <td class="mono small where" title={e.caller}>
                  {#if e.el}<div class="el">{e.el}</div>{/if}
                  {shortFrame(e.caller)}{#if e.trigger}<span class="via faint">via {e.trigger}</span>{/if}
                </td>
                <td class="small faint">{e.phase || ''}</td>
                <td class="num">{SPAN_TYPES.has(e.type) ? fmtMs(e.dur) : ''}</td>
              </tr>
            {/each}
          </tbody>
        </table>
        {#if filtered.length > ROW_LIMIT}<div class="muted more">… {filtered.length - ROW_LIMIT} événements de plus (affinez le filtre)</div>{/if}
      {/if}
    </div>

    {#if store.selectedEvent}
      <EventPanel event={store.selectedEvent} onclose={() => (store.selectedEvent = null)} />
    {/if}
  </div>
{/if}

<style>
  .lazy-n {
    color: var(--t-proxy);
  }

  .head {
    padding: 12px 16px 10px;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
  }

  .title {
    display: flex;
    gap: 10px;
    align-items: baseline;
    font-size: 15px;
  }

  .method {
    font: 600 12px var(--mono);
    color: var(--muted);
  }

  .uri {
    font-size: 14px;
    font-weight: 600;
    word-break: break-all;
  }

  .meta {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 16px;
    margin-top: 6px;
    color: var(--muted);
    font-size: 12px;
  }

  .meta b {
    color: var(--text);
  }

  .source {
    color: var(--text);
  }

  .error {
    margin-top: 6px;
    color: var(--t-lazy);
  }

  .warn {
    margin-top: 6px;
    color: var(--t-proxy);
  }

  .tabs {
    display: flex;
    gap: 2px;
    padding: 0 12px;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
    overflow-x: auto;
  }

  .tabs button {
    border: none;
    border-bottom: 2px solid transparent;
    border-radius: 0;
    background: transparent;
    padding: 8px 10px;
    color: var(--muted);
    white-space: nowrap;
  }

  .tabs button.active {
    color: var(--text);
    border-bottom-color: var(--accent);
    font-weight: 600;
  }

  .n {
    margin-left: 5px;
    font: 11px var(--mono);
    color: var(--faint);
  }

  .body {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr;
  }

  .body.with-panel {
    grid-template-columns: 1fr minmax(320px, 40%);
  }

  .content {
    overflow: auto;
    min-width: 0;
    background: var(--panel);
  }

  .summary {
    padding: 4px 16px 24px;
  }

  .summary h3 {
    font-size: 13px;
    margin: 18px 0 8px;
  }

  .summary h3 .muted {
    font-weight: normal;
    font-size: 12px;
  }

  .two {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 24px;
  }

  @media (max-width: 1100px) {
    .two {
      grid-template-columns: 1fr;
    }
  }

  .small {
    font-size: 11.5px;
  }

  .el {
    color: var(--t-collection);
  }

  .err {
    color: var(--t-lazy);
  }

  .tree-opts,
  .ev-toolbar {
    display: flex;
    gap: 12px;
    align-items: center;
    padding: 8px 12px;
    border-bottom: 1px solid var(--border);
    position: sticky;
    top: 0;
    background: var(--panel);
    z-index: 2;
    flex-wrap: wrap;
  }

  .ev-toolbar input {
    max-width: 420px;
  }

  .tree-opts label {
    display: flex;
    gap: 4px;
    align-items: center;
    font-size: 12px;
  }

  .tree {
    padding: 6px 0 24px;
  }

  .chips {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
  }

  .chips .badge {
    border: none;
    cursor: pointer;
    padding: 4px 7px;
  }

  .chips .badge.off {
    opacity: 0.35;
  }

  .subj,
  .where {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 34vw;
  }

  .where {
    max-width: 28vw;
  }

  .sql {
    word-break: break-word;
  }

  tr.hot td:first-child {
    color: var(--t-lazy);
  }

  tr.sel td {
    background: var(--sel) !important;
  }

  .more {
    padding: 10px 16px;
  }
</style>
