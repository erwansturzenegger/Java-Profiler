<script>
  import { router, to } from './router.svelte.js';
  import { fmtMs, fmtTime, simple, subject, TYPE_SHORT } from './model.js';
  import { remote, qs } from './remote.svelte.js';
  import Link from './Link.svelte';
  import Tags from './Tags.svelte';
  import Loading from './Loading.svelte';
  import Icon from './Icon.svelte';

  const tag = $derived(router.route.tag || null);
  // Agrégé par le serveur ; un tag sélectionné restreint les méthodes aux événements qui le portent (propre ou hérité)
  const res = remote(() => `/api/measures?${qs({ tag })}`, { interval: 3000 });
  const tags = $derived(res.data?.tags || []);
  const probes = $derived(res.data?.probes || []);
  const current = $derived(tag ? tags.find((t) => t.tag === tag) : null);
  const slowest = $derived(res.data?.slowest || []);
</script>

<div class="page">
  <div class="head">
    <h1>
      {#if tag}<Link to={to.measures()}>Mesures</Link> › <span class="tag">#{tag}</span>{:else}Mesures{/if}
      <Loading {res} />
    </h1>
    <Link to={to.profiles()} class="small muted">Configurer les sondes et les tags <Icon name="arrow-right" size={12} /></Link>
  </div>
  <p class="muted small">
    Les tags viennent des sondes (profils) et des règles de tags du serveur. Un tag « propagé » marque aussi tout ce qui s'exécute
    pendant le span (en pointillé). Les durées ne comptent que les spans qui portent le tag eux-mêmes. Filtre dans une requête :
    <span class="mono">#tag</span>.
  </p>

  <h2>Tags</h2>
  <table class="grid">
    <thead>
      <tr>
        <th>Tag</th>
        <th class="num">Événements</th>
        <th class="num">dont hérités</th>
        <th>Types</th>
        <th class="num">Requêtes</th>
        <th class="num">Spans</th>
        <th class="num">Total</th>
        <th class="num">Moyenne</th>
        <th class="num">p95</th>
        <th class="num">Max</th>
        <th class="num">Erreurs</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      {#each tags as t (t.tag)}
        <tr class:sel={t.tag === tag}>
          <td><Link to={to.measures(t.tag)} class="tag">#{t.tag}</Link></td>
          <td class="num">{t.events}</td>
          <td class="num faint">{t.inherited || ''}</td>
          <td class="types">{#each Object.entries(t.types) as [ty, n]}<span class="badge t-{ty}" title="{ty} : {n}">{TYPE_SHORT[ty] || ty} {n}</span>{/each}</td>
          <td class="num">{t.reqs}</td>
          <td class="num">{t.spans || ''}</td>
          <td class="num">{t.spans ? fmtMs(t.total) : ''}</td>
          <td class="num">{t.spans ? fmtMs(t.avg) : ''}</td>
          <td class="num">{t.spans ? fmtMs(t.p95) : ''}</td>
          <td class="num">{t.spans ? fmtMs(t.max) : ''}</td>
          <td class="num err">{t.errors || ''}</td>
          <td><Link to={to.measures(t.tag)} newTab class="ext" title="Ouvrir dans un nouvel onglet"><Icon name="external" size={13} label="Nouvel onglet" /></Link></td>
        </tr>
      {:else}
        <tr><td colspan="12" class="muted">{res.data ? 'Aucun événement tagué. Ajoutez des tags aux sondes ou des règles de tags dans un profil.' : 'Chargement…'}</td></tr>
      {/each}
    </tbody>
  </table>

  <h2>Méthodes sondées{#if tag} <span class="muted small">— sous #{tag}</span>{/if}</h2>
  <table class="grid">
    <thead>
      <tr>
        <th>Méthode</th>
        <th>Sonde</th>
        <th>Tags</th>
        <th class="num">Appels</th>
        <th class="num">Requêtes</th>
        <th class="num">Total</th>
        <th class="num">Moyenne</th>
        <th class="num">p95</th>
        <th class="num">Max</th>
        <th class="num">Erreurs</th>
      </tr>
    </thead>
    <tbody>
      {#each probes as p (p.key)}
        <tr>
          <td class="mono" title="{p.entity}.{p.prop}">
            {#if p.slowest}
              <Link to={to.event(p.slowest.req, p.slowest.id)} title="Appel le plus lent">{p.label || `${simple(p.entity)}.${p.prop}`}</Link>
            {:else}{p.label || `${simple(p.entity)}.${p.prop}`}{/if}
          </td>
          <td class="mono small">{#if p.probe}<Link to={to.profiles(p.probe.split('/')[0])}>{p.probe}</Link>{/if}</td>
          <td>{#each p.tags as t}<Link to={to.measures(t)} class="tag">#{t}</Link>{/each}</td>
          <td class="num">{p.events}</td>
          <td class="num">{p.reqs}</td>
          <td class="num">{fmtMs(p.total)}</td>
          <td class="num">{fmtMs(p.avg)}</td>
          <td class="num">{fmtMs(p.p95)}</td>
          <td class="num">{fmtMs(p.max)}</td>
          <td class="num err">{p.errors || ''}</td>
        </tr>
      {:else}
        <tr><td colspan="10" class="muted">Aucune méthode sondée (sondes « method » des profils).</td></tr>
      {/each}
    </tbody>
  </table>

  {#if current}
    <h2>Spans les plus lents sous #{tag}</h2>
    <table class="grid">
      <thead><tr><th>Heure</th><th>Type</th><th>Sujet</th><th>Requête</th><th class="num">Durée</th></tr></thead>
      <tbody>
        {#each slowest as { e, req } (e.agent + e.id)}
          <tr>
            <td class="mono small faint">{fmtTime(e.t)}</td>
            <td><span class="badge t-{e.type}">{e.type}</span></td>
            <td class="mono small subj"><Link to={to.event(req.key, e.id)} title={subject(e)}>{subject(e)}</Link> <Tags event={e} /></td>
            <td class="mono small faint">{req.method || ''} {req.uri || ''}</td>
            <td class="num">{fmtMs(e.dur)}</td>
          </tr>
        {:else}
          <tr><td colspan="5" class="muted">Aucun span mesuré sous ce tag.</td></tr>
        {/each}
      </tbody>
    </table>
  {/if}
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

  h1 {
    font-size: 20px;
    margin: 6px 0;
  }

  h1 .tag {
    font-size: 16px;
  }

  h2 {
    font-size: 14px;
    margin: 22px 0 6px;
  }

  .small {
    font-size: 12px;
  }

  .types .badge {
    margin-right: 3px;
  }

  .subj {
    max-width: 640px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  tr.sel td {
    background: var(--sel);
  }

  .err {
    color: var(--t-lazy);
  }
</style>
