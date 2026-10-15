<script>
  import { store } from './store.svelte.js';
  import { TYPES, TYPE_LABEL, simple } from './model.js';
  import { to } from './router.svelte.js';
  import Link from './Link.svelte';

  let { onclose } = $props();
  let query = $state('');
  let manual = $state('');
  let focusEntity = $state(store.focus?.entity || '');
  let focusId = $state(store.focus?.id || '');
  let focusAccess = $state(store.focus ? store.focus.access !== false : true);

  const cfg = $derived(store.config);
  const selected = $derived(cfg?.entities || []);
  const all = $derived(selected.includes('*'));
  // Réglages ajoutés par les profils actifs (information : modifiables dans la page Profils)
  const fromProfiles = $derived(
    store.profiles
      .filter((p) => p.enabled !== false)
      .flatMap((p) => ['callerPackages', 'callerExclude', 'accessExclude'].flatMap((k) => (p.settings?.[k] || []).map((v) => ({ name: p.name, k, v }))))
  );

  const TYPE_HELP = {
    SQL: 'Requêtes SQL exécutées, avec durée',
    LOAD: 'Chaque entité hydratée (requête, find, fetch, proxy, collection)',
    PROXY: 'Proxy non initialisé accédé → chargement lazy',
    COLLECTION: 'Collection lazy initialisée',
    ACCESS: 'Appels aux getters des entités listées (volumineux)',
    UTIL: 'Méthodes des sondes « method » des profils (services, utilitaires de chargement…)',
    SESSION: "session.find / get / refresh / merge / evict / clear / close, et exécution des requêtes",
    LAZY_ERROR: 'LazyInitializationException levées',
  };

  const suggestions = $derived.by(() => {
    const q = query.trim().toLowerCase();
    const list = store.entities.filter((e) => !selected.includes(e));
    return (q ? list.filter((e) => e.toLowerCase().includes(q)) : list).slice(0, 60);
  });

  function setEntities(list) {
    store.updateConfig({ entities: list });
  }

  function add(e) {
    const v = e.trim();
    if (!v) return;
    setEntities([...selected.filter((x) => x !== '*'), v]);
  }

  function remove(e) {
    setEntities(selected.filter((x) => x !== e));
  }

  function lines(text) {
    return text
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);
  }

  let timer;
  function debounced(key, text) {
    clearTimeout(timer);
    timer = setTimeout(() => store.updateConfig({ [key]: lines(text) }), 500);
  }

  function onKey(e) {
    if (e.key === 'Escape') onclose();
  }
</script>

<svelte:window onkeydown={onKey} />

<div class="backdrop" onclick={onclose} role="presentation"></div>
<div class="drawer" role="dialog" aria-label="Configuration">
  <div class="top">
    <h2>Configuration</h2>
    <span class="muted small">Appliquée à chaud sur tous les agents (≤ 1 s)</span>
    <button class="close" onclick={onclose} aria-label="Fermer">✕</button>
  </div>

  {#if cfg}
    <section class="focus-box" class:active={!!store.focus}>
      <h3>Mode focus <span class="muted small">— une seule entité</span></h3>
      <p class="muted small">
        N'enregistre que les changements d'état d'une entité (chargement, initialisation des proxies et collections,
        attachement / détachement : persist, merge, refresh, evict, clear, close…, méthodes qui la ciblent) et ses accès.
        Pas de SQL ; seules les requêtes HTTP qui la touchent apparaissent. Remplace les entités et types ci-dessous tant qu'il est actif.
      </p>
      <form class="focus-form" onsubmit={(e) => { e.preventDefault(); if (focusEntity.trim()) store.startFocus(focusEntity.trim(), focusId, focusAccess); }}>
        <input type="text" list="focus-entities" placeholder="Type : Patient, com.acme.Patient" bind:value={focusEntity} class="mono" />
        <input type="text" placeholder="id (facultatif)" bind:value={focusId} class="mono id" />
        <label class="row"><input type="checkbox" bind:checked={focusAccess} /> accès (getters)</label>
        <button class="primary" type="submit" disabled={!focusEntity.trim()}>{store.focus ? 'Modifier' : 'Démarrer'}</button>
        {#if store.focus}<button type="button" onclick={() => store.stopFocus()}>Arrêter</button>{/if}
        <datalist id="focus-entities">
          {#each store.entities as e}<option value={e}></option>{/each}
        </datalist>
      </form>
    </section>

    <section class:dimmed={!!store.focus}>
      <h3>Entités profilées</h3>
      <p class="muted small">
        Filtre appliqué aux LOAD, PROXY, COLLECTION, ACCESS, SESSION et LAZY_ERROR. Les sous-classes sont incluses.
        Accepte un FQCN, un nom simple (<span class="mono">Patient</span>) ou un glob (<span class="mono">com.acme.model.stay.*</span>).
      </p>

      <label class="row">
        <input type="checkbox" checked={all} onchange={(e) => setEntities(e.target.checked ? ['*'] : [])} />
        Toutes les entités
      </label>

      {#if !all}
        <div class="chips">
          {#each selected as e}
            <span class="chip mono" title={e}>{e.includes('*') ? e : simple(e)}<button onclick={() => remove(e)} aria-label="Retirer {e}">✕</button></span>
          {:else}
            <span class="muted small">Aucune entité : rien ne sera enregistré pour ces types.</span>
          {/each}
          {#if selected.length}<button class="link" onclick={() => setEntities([])}>tout retirer</button>{/if}
        </div>

        <div class="pick">
          <input type="search" placeholder="Rechercher parmi {store.entities.length} entités connues des agents…" bind:value={query} />
          <ul>
            {#each suggestions as e}
              <li><button onclick={() => add(e)}><span class="mono">{simple(e)}</span> <span class="faint small">{e.slice(0, e.length - simple(e).length - 1)}</span></button></li>
            {:else}
              <li class="muted small pad">{store.entities.length ? 'Aucune correspondance' : 'Aucune entité connue (les agents les envoient au démarrage)'}</li>
            {/each}
          </ul>
          <form class="manual" onsubmit={(e) => { e.preventDefault(); add(manual); manual = ''; }}>
            <input type="text" placeholder="ou saisir un nom / glob puis Entrée" bind:value={manual} />
          </form>
        </div>
      {/if}
    </section>

    <section class:dimmed={!!store.focus}>
      <h3>Événements</h3>
      <div class="types">
        {#each TYPES as t}
          <label class="type">
            <input type="checkbox" checked={cfg.types[t]} onchange={(e) => store.updateConfig({ types: { [t]: e.target.checked } })} />
            <span class="badge t-{t}">{TYPE_LABEL[t]}</span>
            <span class="muted small">{TYPE_HELP[t]}</span>
          </label>
        {/each}
      </div>
    </section>

    <section>
      <h3>Accès aux propriétés</h3>
      <label class="row"><input type="checkbox" checked={cfg.snapshots !== false} onchange={(e) => store.updateConfig({ snapshots: e.target.checked })} /> Instantanés d'état après chaque opération (cycle de vie des entités)</label>
      <label class="row"><input type="checkbox" checked={cfg.ignoreHibernateAccess} onchange={(e) => store.updateConfig({ ignoreHibernateAccess: e.target.checked })} /> Ignorer les getters appelés par Hibernate lui-même (dirty checking…)</label>
      <label class="row"><input type="checkbox" checked={cfg.accessStacks} onchange={(e) => store.updateConfig({ accessStacks: e.target.checked })} /> Capturer la pile des ACCESS</label>
      <label class="row"><input type="checkbox" checked={cfg.loadStacks} onchange={(e) => store.updateConfig({ loadStacks: e.target.checked })} /> Capturer la pile des LOAD</label>
      <label class="block">
        <span class="small muted">Propriétés ignorées (une par ligne)</span>
        <textarea rows="3" class="mono" oninput={(e) => debounced('accessExclude', e.target.value)} value={(cfg.accessExclude || []).join('\n')}></textarea>
      </label>
    </section>

    <section>
      <h3>Attribution de l'appelant</h3>
      <p class="muted small">
        L'« appelant » est le premier frame de la pile dans les packages applicatifs, hors exclusions. Les frames exclus (framework, utils)
        apparaissent comme déclencheur. Sans package applicatif, tout ce qui n'est pas une bibliothèque compte.
        Ces listes s'appliquent à tous les agents ; chaque <Link to={to.profiles()}>profil</Link> peut y ajouter les siennes.
      </p>
      {#if fromProfiles.length}
        <p class="small">
          Ajouté par les profils :
          {#each fromProfiles as p}<span class="mono chip-ro" title={p.k}>{p.name} : {p.v}</span>{/each}
        </p>
      {/if}
      <label class="block">
        <span class="small muted">Packages applicatifs</span>
        <textarea rows="2" class="mono" oninput={(e) => debounced('callerPackages', e.target.value)} value={(cfg.callerPackages || []).join('\n')}></textarea>
      </label>
      <label class="block">
        <span class="small muted">Exclusions (préfixe terminé par « . », classe, ou glob)</span>
        <textarea rows="3" class="mono" oninput={(e) => debounced('callerExclude', e.target.value)} value={(cfg.callerExclude || []).join('\n')}></textarea>
      </label>
      <label class="block">
        <span class="small muted">Bibliothèques (jamais appelant quand aucun package applicatif n'est défini)</span>
        <textarea rows="3" class="mono" oninput={(e) => debounced('libraryPackages', e.target.value)} value={(cfg.libraryPackages || []).join('\n')}></textarea>
      </label>
    </section>

    <section class="foot">
      <button onclick={() => store.resetConfig()}>Valeurs par défaut</button>
    </section>
  {/if}
</div>

<style>
  .backdrop {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 0.25);
    z-index: 10;
  }

  .drawer {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(560px, 100vw);
    background: var(--panel);
    border-left: 1px solid var(--border);
    z-index: 11;
    overflow: auto;
    padding: 0 18px 24px;
    box-shadow: -8px 0 24px rgb(0 0 0 / 0.12);
  }

  .top {
    display: flex;
    align-items: baseline;
    gap: 10px;
    position: sticky;
    top: 0;
    background: var(--panel);
    padding: 14px 0 10px;
    border-bottom: 1px solid var(--border);
    z-index: 1;
  }

  h2 {
    margin: 0;
    font-size: 16px;
  }

  h3 {
    font-size: 13px;
    margin: 18px 0 6px;
  }

  .close {
    margin-left: auto;
    border: none;
    background: none;
    color: var(--muted);
  }

  .small {
    font-size: 12px;
  }

  .row {
    display: flex;
    gap: 6px;
    align-items: center;
    margin: 6px 0;
    cursor: pointer;
  }

  .block {
    display: block;
    margin-top: 8px;
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin: 8px 0;
    align-items: center;
  }

  .chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 4px 3px 8px;
    border-radius: 12px;
    background: var(--sel);
    border: 1px solid var(--sel-border);
    font-size: 12px;
  }

  .chip button {
    border: none;
    background: none;
    padding: 0 4px;
    color: var(--muted);
    font-size: 11px;
  }

  .link {
    border: none;
    background: none;
    color: var(--muted);
    text-decoration: underline;
    font-size: 12px;
  }

  .pick ul {
    list-style: none;
    margin: 6px 0;
    padding: 0;
    max-height: 240px;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: 6px;
  }

  .pick li button {
    display: block;
    width: 100%;
    text-align: left;
    border: none;
    border-radius: 0;
    border-bottom: 1px solid var(--border);
    background: none;
    padding: 5px 8px;
  }

  .pick li button:hover {
    background: var(--panel-2);
  }

  .pad {
    padding: 8px;
  }

  .types {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .type {
    display: grid;
    grid-template-columns: auto 120px 1fr;
    gap: 8px;
    align-items: center;
    cursor: pointer;
  }

  .type .badge {
    justify-content: center;
  }

  textarea {
    margin-top: 4px;
    resize: vertical;
  }

  .chip-ro {
    display: inline-block;
    margin: 2px 4px 2px 0;
    padding: 1px 6px;
    border-radius: 8px;
    background: var(--panel-2);
    font-size: 11px;
  }

  .foot {
    margin-top: 24px;
  }

  .focus-box.active {
    margin-top: 12px;
    padding: 2px 12px 10px;
    border-radius: 8px;
    background: var(--sel);
    border: 1px solid var(--sel-border);
  }

  .focus-form {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  .focus-form input[type='text'] {
    flex: 1 1 200px;
  }

  .focus-form .id {
    flex: 0 1 120px;
  }

  .focus-form .row {
    margin: 0;
    font-size: 12px;
  }

  .dimmed {
    opacity: 0.5;
  }
</style>
