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
  import Icon from './lib/Icon.svelte';
  import ThemeToggle from './lib/ThemeToggle.svelte';
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
    <a class="brand" href={router.href({})} onclick={(e) => { if (!e.ctrlKey && !e.metaKey) { e.preventDefault(); router.go({}); } }}>
      <svg class="logo" viewBox="0 0 16 16" aria-hidden="true"><rect width="16" height="16" rx="3.5" /><path d="M5 11.5v-7h5.5M5 8h4.5M5 11.5h5.5" /></svg>
      <span>Entity Profiler</span>
    </a>

    <nav class="views" aria-label="Vues">
      <Link to={store.follow ? {} : to.request(store.selectedKey)} class={router.view === 'requests' ? 'active' : ''}>Requêtes</Link>
      <Link to={to.timeline()} class={router.view === 'timeline' ? 'active' : ''}>Chronologie</Link>
      <Link to={to.entities()} class={['entities', 'type', 'entity'].includes(router.view) ? 'active' : ''}>Entités</Link>
      <Link to={to.measures()} class={router.view === 'measures' ? 'active' : ''}>Mesures</Link>
      <Link to={to.profiles()} class={router.view === 'profiles' ? 'active' : ''}>Profils</Link>
    </nav>

    <span class="divider" aria-hidden="true"></span>

    <button class="agents ghost" onclick={() => (showJvms = true)} title={store.agents.length ? store.agents.map((a) => `${a.name} — ${a.online ? 'en ligne' : 'hors ligne'}, reçus ${a.received}, perdus ${a.dropped}`).join('\n') : 'Lister les JVM et attacher un agent'}>
      <span class="dot" class:on={store.connected && online.length > 0} class:warn={store.connected && online.length === 0}></span>
      <span class="agents-txt">
        {#if !store.connected}
          serveur injoignable
        {:else if online.length === 0}
          aucun agent
        {:else}
          {online.map((a) => a.name).join(', ')}
          {#if online.some((a) => a.dropped > 0)}<span class="dropped">· {online.reduce((s, a) => s + a.dropped, 0)} perdus</span>{/if}
        {/if}
      </span>
      <span class="jvm-btn"><Icon name="cpu" size={13} />JVM</span>
    </button>

    {#if store.focus}
      {@const f = store.focus}
      <span class="focus" title="Mode focus : seuls les changements d'état{f.access !== false ? ' et les accès' : ''} de cette entité sont enregistrés">
        <Icon name="crosshair" size={13} />
        <span>Focus</span>
        <Link to={f.id ? to.entity(f.entity, f.id) : to.type(f.entity)} class="mono">{f.entity.includes('*') ? f.entity : simple(f.entity)}{f.id ? `#${f.id}` : ''}</Link>
        <button class="focus-stop ghost" onclick={() => store.stopFocus()} title="Arrêter le focus : retour à la configuration normale">Arrêter</button>
      </span>
    {:else}
      <button class="filter-summary ghost" onclick={() => (showConfig = true)} title="Modifier les entités et types profilés">
        <span class="muted">Entités</span> <span class="mono ent">{entityLabel}</span>
        <span class="types">
          {#each enabledTypes as t}<span class="badge t-{t}">{TYPE_SHORT[t] || t[0]}</span>{/each}
        </span>
      </button>
    {/if}

    <div class="spacer"></div>

    <span class="count" title="Stockés par le serveur (SQLite)">
      <b>{store.stats.events.toLocaleString('fr-CH')}</b> événements · <b>{store.stats.requests.toLocaleString('fr-CH')}</b> requêtes
    </span>

    {#if store.config}
      <button
        class="rec"
        class:recording={store.config.recording}
        onclick={() => store.updateConfig({ recording: !store.config.recording })}
        title="Activer / suspendre l'enregistrement sur tous les agents"
        aria-pressed={store.config.recording}
      >
        <span class="rec-dot"></span>{store.config.recording ? 'Enregistrement' : 'En pause'}
      </button>
    {/if}

    <div class="actions">
      <button class="ghost danger" onclick={() => store.clear()} title="Vider l'historique du serveur" aria-label="Vider l'historique"><Icon name="trash" /><span class="lbl">Vider</span></button>
      <a class="ghost btn" href="/api/export" download title="Télécharger tout l'historique en JSON" aria-label="Exporter l'historique"><Icon name="download" /><span class="lbl">Exporter</span></a>
      <button class="ghost" onclick={() => fileInput.click()} title="Recharger un export JSON" aria-label="Importer un export"><Icon name="upload" /><span class="lbl">Importer</span></button>
      <input bind:this={fileInput} type="file" accept=".json" hidden onchange={(e) => e.target.files[0] && store.importFile(e.target.files[0])} />
      <ThemeToggle />
    </div>
    <button class="primary" onclick={() => (showConfig = true)}><Icon name="settings" />Configuration</button>
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
        <div class="empty">
          <h2>Requête introuvable</h2>
          <p class="muted">Elle a peut-être été purgée, ou l'historique a été vidé.</p>
          <p class="faint mono small">{store.detailError}</p>
        </div>
      {:else}
        <div class="empty">
          {#if !store.connected}
            <h2>Le serveur de collecte ne répond pas</h2>
            <p class="muted">Démarrez-le, l'interface se reconnecte toute seule :</p>
            <code class="cmd">cd server &amp;&amp; node server.js</code>
          {:else if store.agents.length === 0}
            <h2>En attente d'un agent</h2>
            <p class="muted">Attachez l'agent à une JVM en cours d'exécution, ou lancez-la avec l'option <span class="mono">-javaagent</span>.</p>
            <p><button class="primary" onclick={() => (showJvms = true)}><Icon name="cpu" />Attacher à une JVM…</button></p>
            <div class="cmds">
              <span class="small muted">Au démarrage</span>
              <code class="cmd">-javaagent:entity-profiler-agent.jar=server=http://{location.hostname}:7070</code>
              <span class="small muted">À chaud</span>
              <code class="cmd">java -jar entity-profiler-agent.jar &lt;pid&gt; "server=http://{location.hostname}:7070"</code>
            </div>
          {:else}
            <h2>Prêt</h2>
            <p class="muted">Faites une action dans l'application : les requêtes apparaissent à gauche, en direct.</p>
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
    gap: 8px;
    height: 48px;
    padding: 0 12px 0 14px;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
    box-shadow: var(--shadow-1);
    position: relative;
    z-index: 3;
    min-width: 0;
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13.5px;
    font-weight: 650;
    letter-spacing: -0.01em;
    color: var(--text);
    text-decoration: none;
    white-space: nowrap;
    margin-right: 6px;
  }

  .logo {
    width: 18px;
    height: 18px;
  }

  .logo rect {
    fill: var(--accent);
  }

  .logo path {
    stroke: var(--on-accent);
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
    fill: none;
  }

  .views {
    display: flex;
    gap: 2px;
  }

  .views :global(a) {
    display: inline-flex;
    align-items: center;
    height: 30px;
    padding: 0 10px;
    border-radius: var(--r);
    color: var(--muted);
    font-weight: 500;
    text-decoration: none;
    white-space: nowrap;
    transition: background-color 120ms var(--ease), color 120ms var(--ease);
  }

  .views :global(a:hover) {
    background: var(--panel-2);
    color: var(--text);
    text-decoration: none;
  }

  .views :global(a.active) {
    background: var(--panel-3);
    color: var(--text);
    font-weight: 600;
  }

  .divider {
    width: 1px;
    height: 20px;
    background: var(--border);
    margin: 0 4px;
  }

  .agents {
    gap: 7px;
    font-size: 12px;
    flex: none;
  }

  .agents-txt {
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 220px;
  }

  .jvm-btn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 1px 6px;
    border-radius: var(--r-sm);
    background: var(--panel-3);
    color: var(--text);
    font-size: 11px;
    font-weight: 600;
  }

  .dropped {
    color: var(--danger);
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex: none;
    background: var(--danger);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--danger) 18%, transparent);
  }

  .dot.on {
    background: var(--ok);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--ok) 18%, transparent);
  }

  .dot.warn {
    background: var(--faint);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--faint) 18%, transparent);
  }

  .filter-summary {
    gap: 6px;
    flex: 0 1 auto;
    min-width: 140px;
    max-width: 420px;
    overflow: hidden;
    justify-content: flex-start;
  }

  .filter-summary .ent {
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .types {
    display: inline-flex;
    gap: 2px;
  }

  .types .badge {
    padding: 2px 4px;
    font-size: 10px;
  }

  .focus {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    height: var(--h-ctl);
    padding: 0 2px 0 9px;
    border-radius: var(--r);
    background: var(--sel);
    box-shadow: inset 0 0 0 1px var(--sel-border);
    color: var(--accent-text);
    font-weight: 600;
    white-space: nowrap;
  }

  .focus :global(a) {
    color: var(--text);
  }

  .focus-stop {
    min-height: 22px;
    padding: 0 8px;
    font-size: 12px;
  }

  .spacer {
    flex: 1;
    min-width: 8px;
  }

  .count {
    font-size: 12px;
    color: var(--muted);
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
  }

  .count b {
    color: var(--text);
    font-weight: 600;
  }

  .rec {
    gap: 7px;
  }

  .rec-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--faint);
  }

  .rec.recording {
    color: var(--danger);
    border-color: color-mix(in srgb, var(--danger) 35%, var(--border-strong));
  }

  .rec.recording .rec-dot {
    background: var(--danger);
    animation: pulse 1.6s ease-in-out infinite;
  }

  @keyframes pulse {
    50% {
      opacity: 0.3;
    }
  }

  .actions {
    display: flex;
    align-items: center;
    gap: 2px;
    padding-left: 6px;
    border-left: 1px solid var(--border);
    margin-left: 2px;
  }

  a.btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: var(--h-ctl);
    padding: 0 10px;
    border-radius: var(--r);
    font-weight: 500;
    text-decoration: none;
    transition: background-color 120ms var(--ease), color 120ms var(--ease);
  }

  main {
    display: grid;
    grid-template-columns: minmax(300px, 380px) 1fr;
    min-height: 0;
  }

  main.full {
    display: block;
    overflow: hidden;
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
    max-width: 620px;
    padding: 32px 24px;
  }

  .empty h2 {
    font-size: 17px;
    font-weight: 650;
    margin: 0 0 6px;
  }

  .empty p {
    margin: 0 0 14px;
  }

  .cmds {
    display: grid;
    gap: 4px;
    justify-items: center;
    margin-top: 18px;
  }

  .cmds .small {
    margin-top: 6px;
  }

  .cmd {
    display: inline-block;
    font: 12px/1.5 var(--mono);
    padding: 6px 10px;
    border-radius: var(--r);
    background: var(--panel-2);
    border: 1px solid var(--border);
    color: var(--text);
    user-select: all;
    word-break: break-all;
  }

  .small {
    font-size: 12px;
  }

  @media (max-width: 1640px) {
    .actions .lbl {
      display: none;
    }

    .actions :global(button),
    .actions a.btn {
      width: var(--h-ctl);
      padding: 0;
      justify-content: center;
    }
  }

  @media (max-width: 1440px) {
    .count {
      display: none;
    }
  }

  @media (max-width: 1180px) {
    .filter-summary {
      display: none;
    }
  }

  @media (max-width: 900px) {
    header {
      height: auto;
      flex-wrap: wrap;
      padding: 8px 12px;
    }

    .divider,
    .agents-txt {
      display: none;
    }

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
