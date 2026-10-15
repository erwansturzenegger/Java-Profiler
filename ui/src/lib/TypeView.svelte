<script>
  import { router, to } from './router.svelte.js';
  import { fmtMs, fmtTime, simple } from './model.js';
  import { remote, qs } from './remote.svelte.js';
  import Link from './Link.svelte';
  import Loading from './Loading.svelte';
  import FocusButton from './FocusButton.svelte';
  import Icon from './Icon.svelte';

  let filter = $state('');
  let applied = $state('');
  let timer;
  function onFilter(v) {
    filter = v;
    clearTimeout(timer);
    timer = setTimeout(() => (applied = v.trim()), 250);
  }
  const LIMIT = 500;

  const entity = $derived(router.route.entity);
  // Agrégé par le serveur sur tous les événements du type
  const res = remote(() => `/api/entities/type?${qs({ entity, filter: applied, limit: LIMIT })}`, { interval: 5000 });
  const shown = $derived(res.data?.ids || []);
  const props = $derived(res.data?.props || []);
  const totals = $derived(res.data?.totals || { ids: 0, LOAD: 0, lazyLoad: 0, wantedLoad: 0, LAZY_ERROR: 0, multi: 0 });
</script>

<div class="page">
  <div class="crumbs small"><Link to={to.entities()}>Entités</Link> › <span class="mono">{simple(entity)}</span></div>
  <h1 class="mono">{simple(entity)} <Loading {res} /><FocusButton {entity} /></h1>
  <div class="meta small muted">
    <span>{totals.ids} id{totals.ids > 1 ? 's' : ''}</span>
    <span>LOAD {totals.LOAD} : <b class="lazy">{totals.lazyLoad} lazy implicites</b> · <b>{totals.wantedLoad} voulus</b></span>
    {#if totals.multi}<span><b>{totals.multi}</b> id{totals.multi > 1 ? 's' : ''} avec plusieurs instances Java</span>{/if}
    {#if totals.LAZY_ERROR}<span class="err">{totals.LAZY_ERROR} LazyInitializationException</span>{/if}
    <span class="faint mono">{entity}</span>
  </div>

  <div class="cols">
    <section>
      <div class="head">
        <h3>Identifiants</h3>
        <input type="search" placeholder="Filtrer un id…" value={filter} oninput={(e) => onFilter(e.target.value)} />
      </div>
      <table class="grid">
        <thead>
          <tr>
            <th>Id</th>
            <th class="num" title="LOAD lazy implicites (getter, itération)">Load lazy</th>
            <th class="num" title="LOAD voulus : requête, find, refresh, initialize…">Load voulus</th>
            <th class="num" title="Initialisations de proxy / collection">Init.</th>
            <th class="num" title="Getters ayant renvoyé une relation non initialisée">Accès lazy</th>
            <th class="num">Lazy exc.</th>
            <th class="num" title="Objets Java distincts">Instances</th>
            <th class="num">Sessions</th>
            <th class="num">Requêtes</th>
            <th class="num">Vu</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {#each shown as x (x.eid)}
            <tr class:warn={x.insts > 1 || x.LAZY_ERROR}>
              <td class="mono"><Link to={to.entity(entity, x.eid)}>#{x.eid}</Link></td>
              <td class="num lazy">{x.lazyLoad || ''}</td>
              <td class="num">{x.wantedLoad || ''}</td>
              <td class="num">{x.PROXY + x.COLLECTION || ''}</td>
              <td class="num">{x.lazyAccess || ''}</td>
              <td class="num err">{x.LAZY_ERROR || ''}</td>
              <td class="num" class:hot={x.insts > 1}>{x.insts || ''}</td>
              <td class="num">{x.sessions || ''}</td>
              <td class="num">{x.reqs}</td>
              <td class="num faint">{fmtTime(x.last)}</td>
              <td><Link to={to.entity(entity, x.eid)} newTab class="ext" title="Ouvrir dans un nouvel onglet"><Icon name="external" size={13} label="Nouvel onglet" /></Link></td>
            </tr>
          {:else}
            <tr><td colspan="11" class="muted">{res.data ? 'Aucun id.' : 'Chargement…'}</td></tr>
          {/each}
        </tbody>
      </table>
      {#if res.data && res.data.matching > shown.length}<p class="muted small">… {res.data.matching - shown.length} ids de plus : filtrez.</p>{/if}
    </section>

    <section>
      <h3>Propriétés</h3>
      <table class="grid">
        <thead>
          <tr><th>Propriété</th><th class="num">Accès</th><th class="num">dont lazy</th><th class="num">Proxy</th><th class="num">Coll.</th><th class="num">Lazy exc.</th><th class="num">Temps</th></tr>
        </thead>
        <tbody>
          {#each props as p}
            <tr>
              <td class="mono"><b>{p.prop}</b></td>
              <td class="num">{p.ACCESS || ''}</td>
              <td class="num lazy">{p.lazy || ''}</td>
              <td class="num">{p.PROXY || ''}</td>
              <td class="num">{p.COLLECTION || ''}</td>
              <td class="num err">{p.LAZY_ERROR || ''}</td>
              <td class="num">{p.time ? fmtMs(p.time) : ''}</td>
            </tr>
          {:else}
            <tr><td colspan="7" class="muted">Aucune.</td></tr>
          {/each}
        </tbody>
      </table>
    </section>
  </div>
</div>

<style>
  .page {
    padding: 12px 20px 32px;
    overflow: auto;
    height: 100%;
    background: var(--panel);
  }

  .crumbs,
  .small {
    font-size: 12px;
  }

  .crumbs {
    color: var(--muted);
  }

  h1 {
    font-size: 20px;
    margin: 6px 0 4px;
  }

  h3 {
    font-size: 13px;
    margin: 18px 0 6px;
  }

  .meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 16px;
  }

  .meta b {
    color: var(--text);
  }

  .lazy,
  .meta b.lazy {
    color: var(--t-proxy);
  }

  .err {
    color: var(--t-lazy);
  }

  .hot {
    color: var(--accent);
    font-weight: 700;
  }

  tr.warn td:first-child {
    box-shadow: inset 3px 0 var(--accent);
  }

  .cols {
    display: grid;
    grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
    gap: 24px;
  }

  @media (max-width: 1200px) {
    .cols {
      grid-template-columns: 1fr;
    }
  }

  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
  }

  .head input {
    max-width: 220px;
  }
</style>
