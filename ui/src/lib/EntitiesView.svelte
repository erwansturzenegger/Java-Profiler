<script>
  import { to } from './router.svelte.js';
  import { fmtMs, simple } from './model.js';
  import { remote } from './remote.svelte.js';
  import Link from './Link.svelte';
  import Loading from './Loading.svelte';
  import Icon from './Icon.svelte';

  let filter = $state('');
  // Compteurs tenus à jour par le serveur : instantané quel que soit le volume
  const res = remote(() => '/api/entities', { interval: 2000 });
  const rows = $derived.by(() => {
    const all = res.data || [];
    const f = filter.trim().toLowerCase();
    return f ? all.filter((x) => x.entity.toLowerCase().includes(f)) : all;
  });
</script>

<div class="page">
  <div class="head">
    <h1>Entités <Loading {res} /></h1>
    <input type="search" placeholder="Filtrer un type…" bind:value={filter} />
  </div>
  <p class="muted small">
    Toutes requêtes confondues. <b class="lazy">Load lazy</b> : chargement implicite (getter, itération) ; <b>Load voulus</b> : requête,
    find, refresh, initialize… Ctrl+clic pour ouvrir dans un nouvel onglet.
  </p>
  <table class="grid">
    <thead>
      <tr>
        <th>Entité</th>
        <th class="num">Ids</th>
        <th class="num">Load lazy</th>
        <th class="num">Load voulus</th>
        <th class="num">Proxy init</th>
        <th class="num">Coll. init</th>
        <th class="num">Accès lazy</th>
        <th class="num">Session</th>
        <th class="num">Lazy exc.</th>
        <th class="num">Temps init</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      {#each rows as x (x.entity)}
        <tr>
          <td class="mono" title={x.entity}><Link to={to.type(x.entity)}>{simple(x.entity)}</Link> <span class="faint small">{x.entity.slice(0, -simple(x.entity).length - 1)}</span></td>
          <td class="num">{x.ids || ''}</td>
          <td class="num lazy">{x.lazyLoad || ''}</td>
          <td class="num">{x.wantedLoad || ''}</td>
          <td class="num">{x.PROXY || ''}</td>
          <td class="num">{x.COLLECTION || ''}</td>
          <td class="num">{x.lazyAccess || ''}</td>
          <td class="num">{x.SESSION || ''}</td>
          <td class="num err">{x.LAZY_ERROR || ''}</td>
          <td class="num">{x.time ? fmtMs(x.time) : ''}</td>
          <td><Link to={to.type(x.entity)} newTab class="ext" title="Ouvrir dans un nouvel onglet"><Icon name="external" size={13} label="Nouvel onglet" /></Link></td>
        </tr>
      {:else}
        <tr><td colspan="11" class="muted">{res.data ? "Aucune entité dans l'historique." : 'Chargement…'}</td></tr>
      {/each}
    </tbody>
  </table>
</div>

<style>
  .page {
    padding: 12px 20px 32px;
    overflow: auto;
    height: 100%;
    background: var(--panel);
  }

  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
  }

  .head input {
    max-width: 260px;
  }

  h1 {
    font-size: 20px;
    margin: 6px 0;
  }

  .small {
    font-size: 12px;
  }

  .lazy {
    color: var(--t-proxy);
  }

  .err {
    color: var(--t-lazy);
  }
</style>
