<script>
  import { onMount } from 'svelte';
  import { store } from './store.svelte.js';
  import { to } from './router.svelte.js';
  import Link from './Link.svelte';

  let { onclose } = $props();
  let host = $state('');
  let jvms = $state.raw([]);
  let loading = $state(false);
  let error = $state('');
  let name = $state('');
  // Profils choisis pour le prochain attachement ; aucun = profils actifs dont « s'applique à » correspond au nom
  let chosen = $state([]);
  // pid -> 'attach' pendant l'attachement ; id d'agent -> 'detach' pendant la demande
  let busy = $state({});
  let rowError = $state({});

  onMount(refresh);

  async function refresh() {
    loading = true;
    error = '';
    try {
      const r = await store.listJvms();
      host = r.host;
      jvms = r.jvms;
    } catch (e) {
      error = e.message;
    } finally {
      loading = false;
    }
  }

  const pidOf = (a) => String(a.pid ?? a.id.split(':').pop());
  const shortHost = (h) => (h || '').toLowerCase().split('.')[0];

  /** Agent en ligne branché sur cette JVM locale (suivi en direct via le flux des agents). */
  function agentOf(j) {
    return store.agents.find((a) => a.online && pidOf(a) === j.pid && shortHost(a.host ?? a.id.split(':')[0]) === host);
  }

  async function run(key, kind, fn) {
    busy = { ...busy, [key]: kind };
    rowError = { ...rowError, [key]: '' };
    try {
      await fn();
    } catch (e) {
      rowError = { ...rowError, [key]: e.message };
    } finally {
      const { [key]: _, ...rest } = busy;
      busy = rest;
    }
  }

  const attach = (j) => run(j.pid, 'attach', () => store.attach(j.pid, name.trim(), chosen));

  function toggleChosen(n) {
    chosen = chosen.includes(n) ? chosen.filter((x) => x !== n) : [...chosen, n];
  }
  const detach = (a) => run(a.id, 'detach', () => store.detach(a.id));

  function onKey(e) {
    if (e.key === 'Escape') onclose();
  }
</script>

<svelte:window onkeydown={onKey} />

<div class="backdrop" onclick={onclose} role="presentation"></div>
<div class="drawer" role="dialog" aria-label="JVM et agents">
  <div class="top">
    <h2>JVM et agents</h2>
    <span class="muted small">Attachement à chaud, sans redémarrage</span>
    <button class="close" onclick={onclose} aria-label="Fermer">✕</button>
  </div>

  <section>
    <h3>Agents connectés</h3>
    {#if store.agents.length === 0}
      <p class="muted small">Aucun agent. Attachez-en un à une JVM ci-dessous.</p>
    {:else}
      <ul class="rows">
        {#each store.agents as a (a.id)}
          <li>
            <span class="dot" class:on={a.online && !a.detaching} class:warn={a.detaching}></span>
            <div class="what">
              <strong>{a.name}</strong>
              <span class="faint small mono">{a.id}</span>
              <span class="muted small">
                {#if a.detaching}détachement en cours…{:else if !a.online}hors ligne{:else}{a.received.toLocaleString('fr-CH')} reçus{#if a.dropped} · <span class="err">{a.dropped.toLocaleString('fr-CH')} perdus</span>{/if}{/if}
              </span>
              {#if a.online}
                <span class="small">
                  Profils :
                  {#each a.profiles || [] as p, i}{i ? ', ' : ''}<Link to={to.profiles(p)} class="mono">{p}</Link>{:else}<span class="muted">aucun</span>{/each}
                  {#if a.specPending}<span class="muted"> · installation des sondes…</span>{:else if a.specVersion}<span class="faint"> · {Object.values(a.probes || {}).reduce((s, x) => s + x.classes, 0)} classes instrumentées</span>{/if}
                  {#if a.specError}<span class="err"> · {a.specError}</span>{/if}
                </span>
              {/if}
              {#if rowError[a.id]}<span class="err small">{rowError[a.id]}</span>{/if}
            </div>
            <button
              onclick={() => detach(a)}
              disabled={!!busy[a.id] || a.detaching}
              title={a.online ? "Retirer l'instrumentation : les classes retrouvent leur bytecode d'origine" : "Retirer l'agent de la liste"}
            >
              {a.online ? 'Détacher' : 'Oublier'}
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section>
    <div class="head">
      <h3>JVM locales</h3>
      <button class="link" onclick={refresh} disabled={loading}>{loading ? 'Recherche…' : 'Rafraîchir'}</button>
    </div>
    <p class="muted small">
      JVM du même utilisateur sur la machine du serveur. Le serveur doit tourner avec un JDK (<span class="mono">JAVA_HOME</span>).
    </p>

    <label class="block small muted">
      Nom affiché pour le prochain attachement (facultatif)
      <input type="text" placeholder="ex. mon-app-dev — par défaut hôte:pid" bind:value={name} />
    </label>

    <div class="block small muted">
      Profils à appliquer
      <div class="profiles">
        {#each store.profiles as p (p.name)}
          <label class="pchip" class:on={chosen.includes(p.name)} title={p.description || ''}>
            <input type="checkbox" checked={chosen.includes(p.name)} onchange={() => toggleChosen(p.name)} />
            <span class="mono">{p.name}</span>{#if p.enabled === false}<span class="faint"> (inactif)</span>{/if}
          </label>
        {/each}
      </div>
      <span class="faint">
        {chosen.length ? 'Seuls ces profils seront appliqués.' : "Aucun coché : profils actifs dont « s'applique à » correspond au nom de l'agent."}
        Les sondes se modifient ensuite à chaud dans <Link to={to.profiles()}>Profils</Link>.
      </span>
    </div>

    {#if error}
      <p class="err box">{error}</p>
    {:else if !loading && jvms.length === 0}
      <p class="muted small">Aucune JVM trouvée.</p>
    {/if}

    <ul class="rows">
      {#each jvms as j (j.pid)}
        {@const a = agentOf(j)}
        <li class:attached={a}>
          <span class="pid mono">{j.pid}</span>
          <div class="what">
            <span class="mono small jvm-name" title={j.name}>{j.name || '(sans nom)'}</span>
            {#if a}<span class="small ok">profilée · {a.name}</span>{/if}
            {#if busy[j.pid] === 'attach'}<span class="muted small">Attachement et instrumentation des classes chargées…</span>{/if}
            {#if rowError[j.pid]}<span class="err small">{rowError[j.pid]}</span>{/if}
          </div>
          {#if a}
            <button onclick={() => detach(a)} disabled={!!busy[a.id] || a.detaching}>{a.detaching ? 'Détachement…' : 'Détacher'}</button>
          {:else}
            <button class="primary" onclick={() => attach(j)} disabled={!!busy[j.pid]}>{busy[j.pid] ? 'Attachement…' : 'Attacher'}</button>
          {/if}
        </li>
      {/each}
    </ul>
  </section>
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

  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
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

  .block {
    display: block;
    margin: 8px 0;
  }

  .block input {
    display: block;
    width: 100%;
    margin-top: 4px;
  }

  .link {
    border: none;
    background: none;
    color: var(--muted);
    text-decoration: underline;
    font-size: 12px;
  }

  .profiles {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin: 6px 0 4px;
  }

  .pchip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px 2px 4px;
    border: 1px solid var(--border);
    border-radius: 12px;
    color: var(--text);
    cursor: pointer;
  }

  .pchip.on {
    background: var(--sel);
    border-color: var(--sel-border);
  }

  .rows {
    list-style: none;
    margin: 6px 0;
    padding: 0;
    border: 1px solid var(--border);
    border-radius: 6px;
  }

  .rows:empty {
    display: none;
  }

  .rows li {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 10px;
    border-bottom: 1px solid var(--border);
  }

  .rows li:last-child {
    border-bottom: none;
  }

  .rows li.attached {
    background: var(--panel-2);
  }

  .what {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }

  .jvm-name {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .pid {
    min-width: 52px;
    color: var(--muted);
  }

  .dot {
    flex: none;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--faint);
  }

  .dot.on {
    background: var(--t-util);
  }

  .dot.warn {
    background: var(--t-proxy);
  }

  .ok {
    color: var(--t-util);
  }

  .err {
    color: var(--t-lazy);
  }

  .box {
    padding: 8px 10px;
    border: 1px solid var(--t-lazy);
    border-radius: 6px;
    font-size: 12px;
    word-break: break-word;
  }

  button:disabled {
    opacity: 0.6;
    cursor: default;
  }
</style>
