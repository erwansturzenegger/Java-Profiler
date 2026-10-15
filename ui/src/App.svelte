<script>
  import { onMount } from 'svelte';
  import { store } from './lib/store.svelte.js';
  import { simple, TYPE_SHORT } from './lib/model.js';
  import RequestList from './lib/RequestList.svelte';
  import RequestDetail from './lib/RequestDetail.svelte';
  import ConfigDrawer from './lib/ConfigDrawer.svelte';
  import JvmDrawer from './lib/JvmDrawer.svelte';
  import EntityView from './lib/EntityView.svelte';
  import TypeView from './lib/TypeView.svelte';
  import EntitiesView from './lib/EntitiesView.svelte';
  import MeasuresView from './lib/MeasuresView.svelte';
  import ProfilesView from './lib/ProfilesView.svelte';
  import TimelineView from './lib/TimelineView.svelte';
  import Link from './lib/Link.svelte';
  import { router, to } from './lib/router.svelte.js';

  let showConfig = $state(false);
  let showJvms = $state(false);
  let fileInput;

  onMount(() => store.connect());

  const online = $derived(store.agents.filter((a) => a.online));
  const entityLabel = $derived.by(() => {
    const list = store.config?.entities || [];
    if (!list.length) return 'aucune';
    if (list.includes('*')) return 'toutes';
    return list.map((e) => (e.includes('*') ? e : simple(e))).join(', ');
  });
  const enabledTypes = $derived(Object.entries(store.config?.types || {}).filter(([, v]) => v).map(([k]) => k));
</script>

<div class="app">
  <header>
    <div class="brand">
      <span class="logo" aria-hidden="true"></span>
      <strong>Entity Profiler</strong>
    </div>

    <nav class="views" aria-label="Vues">
      <Link to={store.follow ? {} : to.request(store.selectedKey)} class={router.view === 'requests' ? 'active' : ''}>Requêtes</Link>
      <Link to={to.timeline()} class={router.view === 'timeline' ? 'active' : ''}>Chronologie</Link>
      <Link to={to.entities()} class={['entities', 'type', 'entity'].includes(router.view) ? 'active' : ''}>Entités</Link>
      <Link to={to.measures()} class={router.view === 'measures' ? 'active' : ''}>Mesures</Link>
      <Link to={to.profiles()} class={router.view === 'profiles' ? 'active' : ''}>Profils</Link>
    </nav>

    <button class="agents" onclick={() => (showJvms = true)} title={store.agents.map((a) => `${a.name} — ${a.online ? 'en ligne' : 'hors ligne'}, reçus ${a.received}, perdus ${a.dropped}`).join('\n')}>
      <span class="dot" class:on={store.connected && online.length > 0} class:warn={store.connected && online.length === 0}></span>
      {#if !store.connected}
        serveur injoignable
      {:else if online.length === 0}
        aucun agent connecté
      {:else}
        {online.map((a) => a.name).join(', ')}
        {#if online.some((a) => a.dropped > 0)}<span class="dropped">· {online.reduce((s, a) => s + a.dropped, 0)} perdus</span>{/if}
      {/if}
      <span class="jvm-btn">JVM…</span>
    </button>

    {#if store.focus}
      {@const f = store.focus}
      <span class="focus" title="Mode focus : seuls les changements d'état{f.access !== false ? ' et les accès' : ''} de cette entité sont enregistrés">
        <span class="focus-dot"></span>
        <span>Focus</span>
        <Link to={f.id ? to.entity(f.entity, f.id) : to.type(f.entity)} class="mono">{f.entity.includes('*') ? f.entity : simple(f.entity)}{f.id ? `#${f.id}` : ''}</Link>
        <button class="focus-stop" onclick={() => store.stopFocus()} title="Arrêter le focus : retour à la configuration normale">Arrêter</button>
      </span>
    {:else}
      <button class="filter-summary" onclick={() => (showConfig = true)} title="Modifier les entités et types profilés">
        <span class="muted">Entités</span> <span class="mono">{entityLabel}</span>
        <span class="types">
          {#each enabledTypes as t}<span class="badge t-{t}">{TYPE_SHORT[t] || t[0]}</span>{/each}
        </span>
      </button>
    {/if}

    <div class="spacer"></div>

    <span class="muted count" title="Stockés par le serveur (SQLite)">{store.stats.events.toLocaleString('fr-CH')} événements · {store.stats.requests.toLocaleString('fr-CH')} requêtes</span>

    {#if store.config}
      <button
        class="rec"
        class:recording={store.config.recording}
        onclick={() => store.updateConfig({ recording: !store.config.recording })}
        title="Activer / suspendre l'enregistrement sur tous les agents"
      >
        <span class="rec-dot"></span>{store.config.recording ? 'Enregistrement' : 'En pause'}
      </button>
    {/if}
    <button onclick={() => store.clear()} title="Vider l'historique du serveur">Vider</button>
    <a class="btn" href="/api/export" download title="Télécharger tout l'historique en JSON">Exporter</a>
    <button onclick={() => fileInput.click()} title="Recharger un export JSON">Importer</button>
    <input bind:this={fileInput} type="file" accept=".json" hidden onchange={(e) => e.target.files[0] && store.importFile(e.target.files[0])} />
    <button class="primary" onclick={() => (showConfig = true)}>Configuration</button>
  </header>

  {#if router.view === 'timeline'}
    <main class="full">{#key (router.route.entity || '') + '#' + (router.route.id || '')}<TimelineView />{/key}</main>
  {:else if router.view === 'entity'}
    <main class="full">{#key router.route.entity + '#' + router.route.id}<EntityView />{/key}</main>
  {:else if router.view === 'type'}
    <main class="full">{#key router.route.entity}<TypeView />{/key}</main>
  {:else if router.view === 'entities'}
    <main class="full"><EntitiesView /></main>
  {:else if router.view === 'measures'}
    <main class="full"><MeasuresView /></main>
  {:else if router.view === 'profiles'}
    <main class="full"><ProfilesView /></main>
  {:else}
  <main>
    <aside>
      <RequestList />
    </aside>
    <section class="detail">
      {#if store.selected}
        <RequestDetail />
      {:else if store.detailLoading}
        <div class="empty muted">Chargement de la requête…</div>
      {:else if router.route.req && store.detailError}
        <div class="empty"><p>Requête introuvable : elle a peut-être été purgée ou l'historique vidé.</p><p class="muted mono small">{store.detailError}</p></div>
      {:else}
        <div class="empty">
          {#if !store.connected}
            <p>Le serveur de collecte ne répond pas.</p>
            <p class="muted mono">cd server &amp;&amp; node server.js</p>
          {:else if store.agents.length === 0}
            <p>En attente d'un agent.</p>
            <p><button class="primary" onclick={() => (showJvms = true)}>Attacher à une JVM…</button></p>
            <p class="muted mono">-javaagent:entity-profiler-agent.jar=server=http://{location.hostname}:7070</p>
            <p class="muted">ou à chaud : <span class="mono">java -jar entity-profiler-agent.jar &lt;pid&gt; "server=http://{location.hostname}:7070"</span></p>
          {:else}
            <p>Faites une action dans l'application : les requêtes apparaissent à gauche en direct.</p>
          {/if}
        </div>
      {/if}
    </section>
  </main>
  {/if}

  {#if showJvms}
    <JvmDrawer onclose={() => (showJvms = false)} />
  {/if}

  {#if showConfig}
    <ConfigDrawer onclose={() => (showConfig = false)} />
  {/if}
</div>

<style>
  .app {
    display: grid;
    grid-template-rows: auto 1fr;
    height: 100%;
  }

  header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 14px;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
    flex-wrap: wrap;
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
  }

  .logo {
    width: 14px;
    height: 14px;
    border-radius: 3px;
    background: linear-gradient(135deg, var(--t-proxy) 0 50%, var(--t-collection) 50%);
  }

  .agents {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--muted);
    font-size: 12px;
    background: none;
    border-color: transparent;
  }

  .agents:hover {
    border-color: var(--border);
  }

  .jvm-btn {
    color: var(--text);
    text-decoration: underline;
    text-underline-offset: 2px;
  }

  .dropped {
    color: var(--t-lazy);
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--t-lazy);
  }

  .dot.on {
    background: var(--t-util);
  }

  .dot.warn {
    background: var(--t-proxy);
  }

  .filter-summary {
    display: flex;
    align-items: center;
    gap: 6px;
    max-width: 520px;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    background: var(--panel-2);
    border-color: transparent;
  }

  .types {
    display: inline-flex;
    gap: 2px;
  }

  .focus {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 3px 4px 3px 10px;
    border-radius: 6px;
    background: var(--sel);
    border: 1px solid var(--sel-border);
    white-space: nowrap;
  }

  .focus-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    border: 2px solid var(--accent);
    box-shadow: 0 0 0 2px var(--sel), 0 0 0 3px var(--accent);
  }

  .focus-stop {
    padding: 2px 8px;
    font-size: 12px;
  }

  .spacer {
    flex: 1;
  }

  .count {
    font-size: 12px;
  }

  .rec {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .rec-dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--faint);
  }

  .rec.recording .rec-dot {
    background: var(--t-lazy);
    animation: pulse 1.4s ease-in-out infinite;
  }

  @keyframes pulse {
    50% {
      opacity: 0.35;
    }
  }

  a.btn {
    text-decoration: none;
    color: inherit;
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 4px 10px;
    background: var(--panel);
  }

  main {
    display: grid;
    grid-template-columns: minmax(300px, 400px) 1fr;
    min-height: 0;
  }

  main.full {
    display: block;
    overflow: hidden;
  }

  .views {
    display: flex;
    gap: 2px;
    margin-left: 6px;
  }

  .views :global(a) {
    padding: 4px 10px;
    border-radius: 6px;
    color: var(--muted);
    text-decoration: none;
  }

  .views :global(a:hover) {
    background: var(--panel-2);
    color: var(--text);
  }

  .views :global(a.active) {
    background: var(--sel);
    color: var(--text);
    font-weight: 600;
  }

  aside {
    border-right: 1px solid var(--border);
    background: var(--panel);
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .detail {
    min-height: 0;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }

  .empty {
    margin: auto;
    text-align: center;
    max-width: 640px;
    padding: 24px;
  }

  @media (max-width: 800px) {
    main {
      grid-template-columns: 1fr;
      grid-template-rows: 40% 1fr;
    }

    aside {
      border-right: none;
      border-bottom: 1px solid var(--border);
    }
  }
</style>
