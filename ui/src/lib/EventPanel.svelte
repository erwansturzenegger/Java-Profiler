<script>
  import { store } from './store.svelte.js';
  import { TYPE_LABEL, fmtMs, fmtTime, entityOf, causeOf, parseSnap, cellText, simple, reqKey } from './model.js';
  import { remote, qs } from './remote.svelte.js';
  import { to } from './router.svelte.js';
  import Link from './Link.svelte';
  import Tags from './Tags.svelte';

  let { event, onclose } = $props();
  let showAll = $state(false);

  // Packages applicatifs effectifs pour l'agent de l'événement (configuration + profils)
  const settings = $derived(store.settingsFor(event.agent));
  const pkgs = $derived(settings.callerPackages);
  const libs = $derived(settings.libraryPackages);
  const excludes = $derived(settings.callerExclude.map(globToRegex));
  const probeProfile = $derived(event.probe ? event.probe.split('/')[0] : null);

  function globToRegex(g) {
    const body = g.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*');
    return new RegExp('^' + body + (g.endsWith('.') ? '.*' : '(\\$.*)?') + '$');
  }

  function frameClass(f) {
    const cls = f.replace(/\.[^.]+$/, '');
    if (cls.includes('$HibernateProxy') || cls.includes('$$')) return 'lib';
    if (pkgs.length ? !pkgs.some((p) => cls.startsWith(p)) : libs.some((p) => cls.startsWith(p))) return 'lib';
    if (excludes.some((r) => r.test(cls))) return 'fw';
    return 'app';
  }

  // Pour une liste d'ids (UTIL sur une collection), l'historique porte sur le premier
  const ref = $derived.by(() => {
    const r = entityOf(event);
    return r ? { entity: r.entity, eid: String(r.eid).split(',')[0] } : null;
  });

  const cause = $derived(causeOf(event));
  const snap = $derived(parseSnap(event.snap));
  const rsnap = $derived(parseSnap(event.rsnap));

  // Les listes d'événements arrivent sans les piles (volume) : celle de l'événement affiché est chargée à la demande
  const full = remote(() => (event.stackN && !event.stack ? `/api/event?${qs({ key: reqKey(event), id: event.id })}` : null), { live: () => false });
  const stack = $derived(event.stack || (full.data?.id === event.id ? full.data.stack : null) || []);
  const frames = $derived(stack.map((f) => ({ f, k: frameClass(f) })));
  const visible = $derived(showAll ? frames : collapse(frames));

  /** Replie les suites de frames de librairies. */
  function collapse(list) {
    const out = [];
    let run = [];
    const flushRun = () => {
      if (run.length <= 3) out.push(...run);
      else out.push(run[0], { f: `… ${run.length - 2} frames de librairies`, k: 'gap' }, run[run.length - 1]);
      run = [];
    };
    for (const x of list) {
      if (x.k === 'lib') run.push(x);
      else {
        flushRun();
        out.push(x);
      }
    }
    flushRun();
    return out;
  }

  const fields = $derived(
    [
      ['Entité', event.type === 'UTIL' ? event.entity : null],
      ['Propriété', event.prop],
      ['Instance', event.inst ? (event.inst.startsWith('p') ? `proxy non initialisé @${event.inst.slice(1)}` : `@${event.inst}`) : null],
      ['Instance retournée', event.rinst ? `@${event.rinst} (≠ instance passée)` : null],
      ['Session', event.sess ? `@${event.sess}` : null],
      ['Appelant', event.caller],
      ['Frame applicatif', event.inner !== event.caller ? event.inner : null],
      ['Déclencheur', event.trigger],
      ['Expression EL', event.el],
      ['Phase JSF', event.phase],
      ['État retourné', event.state],
      ['Durée', event.dur != null ? fmtMs(event.dur) : null],
      ['Heure', `${new Date(event.t).toLocaleDateString('fr-CH')} ${fmtTime(event.t)}`],
      ['Thread', event.thread],
      ['Parent', event.parent],
    ].filter(([, v]) => v != null && v !== '')
  );
</script>

<aside class="panel">
  <div class="top">
    <span class="badge t-{event.type}">{TYPE_LABEL[event.type] || event.type}</span>
    <span class="faint mono id">{event.id}</span>
    <button class="close" onclick={onclose} aria-label="Fermer">✕</button>
  </div>

  {#if event.error}
    <div class="error mono">{event.error}</div>
  {/if}

  {#if ref}
    <div class="refs">
      <Link to={to.entity(ref.entity, ref.eid)} class="btn" title="Cycle de vie : état de l'instance après chaque opération">
        Cycle de vie de <span class="mono">{simple(ref.entity)}#{ref.eid}</span>
      </Link>
      <Link to={to.entity(ref.entity, ref.eid)} newTab class="btn icon" title="Ouvrir dans un nouvel onglet">↗</Link>
      <Link to={to.type(ref.entity)} class="btn" title="Tous les ids de ce type">Type <span class="mono">{simple(ref.entity)}</span></Link>
      <Link to={to.type(ref.entity)} newTab class="btn icon" title="Ouvrir dans un nouvel onglet">↗</Link>
      <Link to={to.timeline({ entity: ref.entity, id: ref.eid, ev: event.id })} class="btn" title="Toutes les étapes de cette entité, tous threads et requêtes confondus, et l'origine de chaque état">Chronologie de l'entité</Link>
    </div>
  {/if}

  {#if event.tags || event.rtags || event.scope || event.probe}
    <div class="tags-row">
      {#if event.probe}
        <span class="small muted">Sonde</span>
        <Link to={to.profiles(probeProfile)} class="mono small" title="Profil qui définit cette sonde">{event.probe}</Link>
      {/if}
      <Tags {event} />
    </div>
  {/if}

  {#if cause}
    <div class="cause">
      <span class="badge {cause.cls}">{cause.label}</span>
      <span class="small">{cause.help}</span>
      {#if cause.detail}<div class="mono small detail">{cause.detail}</div>{/if}
    </div>
  {/if}

  {#if event.info}
    <pre class="info mono">{event.info}</pre>
  {/if}

  <dl>
    {#each fields as [k, v]}
      <dt>{k}</dt>
      <dd class="mono">{v}</dd>
    {/each}
  </dl>

  {#each [['État après', snap], ['Instance retournée', rsnap]] as [title, sn]}
    {#if sn}
      <h4>{title}</h4>
      <dl class="snap">
        {#if sn.att}
          <dt>session</dt>
          <dd class="mono">{sn.att.kind === 'S' ? `attachée @${sn.att.sess}` : sn.att.kind === 'P' ? 'proxy non initialisé' : `détachée${sn.att.sess ? ' de @' + sn.att.sess : ''}`}</dd>
        {/if}
        {#each [...sn.props] as [name, c]}
          <dt>{name}</dt>
          <dd class="mono c-{c.kind === 'N' ? 'N' : c.state}">{cellText(c)}</dd>
        {/each}
      </dl>
    {/if}
  {/each}

  {#if frames.length}
    <div class="stack-head">
      <h4>Pile</h4>
      <label class="muted"><input type="checkbox" bind:checked={showAll} /> tout afficher</label>
    </div>
    <ol class="stack mono">
      {#each visible as x}
        <li class={x.k} class:caller={x.f === event.caller}>{x.f}</li>
      {/each}
    </ol>
  {:else if event.stackN}
    <p class="muted small">{full.error ? `Pile indisponible : ${full.error}` : 'Chargement de la pile…'}</p>
  {:else if event.type === 'ACCESS' || event.type === 'LOAD'}
    <p class="muted small">Pile non capturée pour ce type (activer « piles ACCESS / LOAD » dans la configuration).</p>
  {/if}
</aside>

<style>
  .refs {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin: 8px 0;
  }

  .tags-row {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 6px;
    margin: 8px 0;
  }

  .refs :global(a.btn) {
    text-decoration: none;
    color: inherit;
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 3px 8px;
    font-size: 12px;
  }

  .refs :global(a.btn:hover) {
    background: var(--panel-2);
  }

  .cause {
    margin: 8px 0;
  }

  .cause .detail {
    margin-top: 4px;
    color: var(--muted);
    word-break: break-word;
  }

  .small {
    font-size: 12px;
  }

  .snap dd.c-I {
    color: var(--t-util);
  }

  .snap dd.c-L {
    color: var(--muted);
  }

  .snap dd.c-N {
    color: var(--faint);
  }

  .panel {
    border-left: 1px solid var(--border);
    background: var(--panel);
    overflow: auto;
    padding: 10px 14px 24px;
    min-width: 0;
  }

  .top {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .id {
    font-size: 11px;
  }

  .close {
    margin-left: auto;
    border: none;
    background: none;
    color: var(--muted);
  }

  .error {
    margin-top: 10px;
    padding: 8px;
    border-radius: 6px;
    background: color-mix(in srgb, var(--t-lazy) 12%, transparent);
    color: var(--t-lazy);
    word-break: break-word;
  }

  .info {
    margin: 10px 0 0;
    padding: 8px;
    background: var(--panel-2);
    border-radius: 6px;
    white-space: pre-wrap;
    word-break: break-word;
  }

  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 4px 12px;
    margin: 12px 0;
  }

  dt {
    color: var(--muted);
    font-size: 12px;
  }

  dd {
    margin: 0;
    word-break: break-all;
  }

  .stack-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 6px;
  }

  h4 {
    margin: 0;
    font-size: 12px;
  }

  .stack-head label {
    font-size: 12px;
    display: flex;
    gap: 4px;
    align-items: center;
  }

  .stack {
    margin: 6px 0 0;
    padding: 0 0 0 4px;
    list-style: none;
    font-size: 11.5px;
  }

  .stack li {
    padding: 1px 0;
    word-break: break-all;
  }

  .stack li.lib {
    color: var(--faint);
  }

  .stack li.gap {
    color: var(--faint);
    font-style: italic;
  }

  .stack li.fw {
    color: var(--muted);
  }

  .stack li.app {
    color: var(--app-frame);
  }

  .stack li.caller {
    font-weight: 700;
    background: var(--sel);
  }

  .small {
    font-size: 12px;
  }
</style>
