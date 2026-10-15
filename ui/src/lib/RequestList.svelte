<script>
  import { store } from './store.svelte.js';
  import { router } from './router.svelte.js';
  import { TYPES, TYPE_SHORT, fmtMs, fmtTime } from './model.js';

  // Filtres appliqués par le serveur (saisie espacée pour ne pas recharger à chaque touche)
  let filter = $state(store.listFilter.q);
  let timer;
  function onFilter(v) {
    filter = v;
    clearTimeout(timer);
    timer = setTimeout(() => store.setListFilter({ q: v }), 250);
  }

  function path(uri) {
    if (!uri) return '…';
    const q = uri.indexOf('?');
    return q > 0 ? uri.slice(0, q) : uri;
  }
</script>

<div class="toolbar">
  <input type="search" placeholder="Filtrer (URL, composant JSF…)" value={filter} oninput={(e) => onFilter(e.target.value)} />
  <div class="opts">
    <label><input type="checkbox" checked={store.listFilter.hideEmpty} onchange={(e) => store.setListFilter({ hideEmpty: e.target.checked })} /> masquer vides</label>
    <label><input type="checkbox" checked={store.listFilter.problems} onchange={(e) => store.setListFilter({ problems: e.target.checked })} /> N+1 / lazy</label>
    <label title="Sélectionner automatiquement la dernière requête reçue"><input type="checkbox" checked={store.follow && !router.route.req} onchange={(e) => store.setFollow(e.target.checked)} /> suivre</label>
  </div>
</div>

<ul class="list">
  {#each store.requests as r (r.key)}
    <li>
      <button class="req" class:selected={r.key === store.selectedKey} class:bg={r.background} onclick={() => store.select(r.key)}>
        <div class="line1">
          <span class="method">{r.method || '…'}</span>
          <span class="uri" title={r.uri}>{path(r.uri)}</span>
          <span class="dur" class:slow={r.dur > 1000}>{r.done ? fmtMs(r.dur) : 'en cours'}</span>
        </div>
        {#if r.source}
          <div class="source mono" title="Composant JSF à l'origine de la requête">{r.source}</div>
        {/if}
        <div class="line2">
          <span class="time faint mono">{fmtTime(r.t)}</span>
          {#each TYPES as t}
            {#if r.counts[t]}
              <span class="badge t-{t}" title={t}>{TYPE_SHORT[t]} {r.counts[t]}</span>
            {/if}
          {/each}
          {#if r.nplus}<span class="badge flag" title="N+1 probables">N+1 ×{r.nplus}</span>{/if}
          {#if r.status && r.status !== '200'}<span class="badge t-LAZY_ERROR">{r.status}</span>{/if}
          {#if r.error}<span class="badge t-LAZY_ERROR" title={r.error}>exception</span>{/if}
        </div>
      </button>
    </li>
  {:else}
    <li class="none muted">Aucune requête</li>
  {/each}
  {#if store.requests.length < store.requestTotal}
    <li class="more">
      <button onclick={() => store.moreRequests()}>Afficher plus</button>
      <span class="muted small">{store.requests.length.toLocaleString('fr-CH')} / {store.requestTotal.toLocaleString('fr-CH')}</span>
    </li>
  {/if}
</ul>

<style>
  .toolbar {
    padding: 10px;
    border-bottom: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .opts {
    display: flex;
    gap: 12px;
    font-size: 12px;
    color: var(--muted);
  }

  .opts label {
    display: flex;
    align-items: center;
    gap: 4px;
    cursor: pointer;
  }

  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    overflow: auto;
    flex: 1;
  }

  .req {
    display: block;
    width: 100%;
    text-align: left;
    border: none;
    border-bottom: 1px solid var(--border);
    border-left: 3px solid transparent;
    border-radius: 0;
    padding: 8px 10px 8px 9px;
    background: transparent;
  }

  .req:hover {
    background: var(--panel-2);
  }

  .req.selected {
    background: var(--sel);
    border-left-color: var(--accent);
  }

  .req.bg .uri {
    font-style: italic;
    color: var(--muted);
  }

  .line1 {
    display: flex;
    gap: 8px;
    align-items: baseline;
  }

  .method {
    font: 600 11px var(--mono);
    color: var(--muted);
    min-width: 34px;
  }

  .uri {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 500;
  }

  .dur {
    font: 12px var(--mono);
    color: var(--muted);
  }

  .dur.slow {
    color: var(--t-lazy);
    font-weight: 600;
  }

  .source {
    color: var(--muted);
    margin: 2px 0 0 42px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 11px;
  }

  .line2 {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    align-items: center;
    margin-top: 5px;
    padding-left: 42px;
  }

  .time {
    font-size: 11px;
    margin-right: 4px;
  }

  .flag {
    --c: var(--t-lazy);
  }

  .none {
    padding: 20px;
    text-align: center;
  }

  .more {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    padding: 10px;
  }

  .small {
    font-size: 12px;
  }
</style>
