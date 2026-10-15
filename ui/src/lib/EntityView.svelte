<script>
  import { router, to } from './router.svelte.js';
  import { entityHistory, lifecycle, causeOf, cellText, operation, fmtMs, fmtTime, shortFrame, simple, SPAN_TYPES } from './model.js';
  import { remote, qs } from './remote.svelte.js';
  import Link from './Link.svelte';
  import Loading from './Loading.svelte';
  import FocusButton from './FocusButton.svelte';
  import Icon from './Icon.svelte';

  let allAccess = $state(false);
  let allColumns = $state(false);
  let onlyChanges = $state(false);
  // Rendu par tranches : une entité très sollicitée a des milliers d'étapes
  const STEP_PAGE = 500;
  let stepLimit = $state(STEP_PAGE);
  let allFindings = $state(false);

  const entity = $derived(router.route.entity);
  const eid = $derived(router.route.id);
  // Le serveur renvoie les événements utiles (références, instances, sessions) ; l'analyse est faite ici
  const res = remote(() => `/api/entity?${qs({ entity, id: eid })}`, { interval: 3000 });
  const items = $derived.by(() => {
    const d = res.data;
    if (!d) return [];
    return d.events.map((e, i) => ({ e, req: d.reqs[d.rkeys[i]] || { key: d.rkeys[i] } }));
  });
  const h = $derived(entityHistory(items, entity, eid));
  const life = $derived(lifecycle(h, { allAccess, allColumns }));
  const steps = $derived(onlyChanges ? life.steps.filter((s) => s.notes.length || s.changed.size || s.attChanged || s.returned) : life.steps);
  const loads = $derived.by(() => {
    const c = { lazy: 0, wanted: 0 };
    for (const r of h.rows) {
      if (r.e.type !== 'LOAD') continue;
      const k = causeOf(r.e);
      if (k?.kind === 'lazy') c.lazy++;
      else if (k?.wanted) c.wanted++;
    }
    return c;
  });

  // Couleur stable par instance / session : i1, i2... S1, S2...
  const hue = (label) => (Number(String(label).slice(1)) * 137) % 360;

  function att(snap) {
    const a = snap?.att;
    if (!a) return null;
    if (a.kind === 'P') return { cls: 'proxy', text: 'proxy non initialisé' };
    const s = a.sess ? h.sessLabel(a.sess) : null;
    return a.kind === 'S' ? { cls: 'on', text: `attachée${s ? ' ' + s : ''}` } : { cls: 'off', text: `détachée${s ? ' de ' + s : ''}` };
  }

  function reqLabel(r) {
    return r.background ? r.uri : `${r.method || ''} ${r.uri || r.id}`;
  }
</script>

<div class="page">
  <div class="crumbs small">
    <Link to={to.entities()}>Entités</Link> ›
    <Link to={to.type(entity)}>{simple(entity)}</Link> ›
    <span class="mono">#{eid}</span>
  </div>

  <h1 class="mono">{simple(entity)}<span class="faint">#</span>{eid} <Loading {res} /><FocusButton {entity} id={eid} /></h1>
  <p class="small"><Link to={to.timeline({ entity, id: eid })}>Chronologie de l'entité : threads, requêtes et origine de chaque état <Icon name="arrow-right" size={12} /></Link></p>
  <div class="meta small muted">
    <span>{h.rows.length} événements</span>
    <span>{h.instances} instance{h.instances > 1 ? 's' : ''} Java</span>
    <span>{h.sessions} session{h.sessions > 1 ? 's' : ''}</span>
    <span>LOAD : <b class="lazy">{loads.lazy} lazy implicite{loads.lazy > 1 ? 's' : ''}</b> · <b>{loads.wanted} voulu{loads.wanted > 1 ? 's' : ''}</b></span>
    <span class="faint mono">{entity}</span>
  </div>

  {#if res.data?.truncated}
    <p class="small warn">Entité très sollicitée : seuls ses événements les plus récents sont analysés.</p>
  {/if}
  {#if !h.rows.length}
    <p class="muted">{res.data ? "Aucun événement pour cette entité dans l'historique." : 'Chargement…'}</p>
  {:else}
    {#if h.findings.length}
      <section class="findings">
        <h3>À retenir</h3>
        <ul>
          {#each allFindings ? h.findings : h.findings.slice(0, 20) as f}
            <li>
              <Link to={to.event(f.row.req.key, f.row.e.id)}>
                <span class="mono faint">{fmtTime(f.row.e.t)}</span> {f.text}
                <span class="faint">· {shortFrame(f.row.e.caller)}</span>
              </Link>
            </li>
          {/each}
        </ul>
        {#if h.findings.length > 20}
          <button class="small more" onclick={() => (allFindings = !allFindings)}>{allFindings ? 'Réduire' : `Voir les ${h.findings.length}`}</button>
        {/if}
      </section>
    {/if}

    <div class="head">
      <h3>Chemin de vie <span class="muted small">— état de l'instance après chaque opération</span></h3>
      <div class="opts small muted">
        <label><input type="checkbox" bind:checked={onlyChanges} /> seulement les changements</label>
        <label><input type="checkbox" bind:checked={allAccess} /> tous les accès</label>
        <label><input type="checkbox" bind:checked={allColumns} /> toutes les associations{#if !allColumns && life.hidden} ({life.hidden} masquées){/if}</label>
      </div>
    </div>

    <div class="legend small muted">
      <span class="cell c-I">chargée</span> <span class="cell c-L">lazy / proxy non initialisé</span> <span class="cell c-N">null</span>
      <span class="cell changed">a changé</span> · i1, i2 = objets Java distincts · S1, S2 = sessions
    </div>

    <div class="scroll">
      <table class="life">
        <thead>
          <tr>
            <th class="sticky c0">Heure</th>
            <th class="sticky c1">Opération</th>
            <th>Instance</th>
            <th>Session</th>
            {#each life.columns as col}<th class="mono col" title={col}>{col}</th>{/each}
          </tr>
        </thead>
        <tbody>
          {#each steps.slice(0, stepLimit) as st, k}
            {@const e = st.e}
            {@const prev = steps[k - 1]}
            {@const cause = causeOf(e)}
            {@const a = att(st.snap)}
            {#if !prev || prev.req !== st.req}
              <tr class="req-row">
                <td colspan={4 + life.columns.length}>
                  <Link to={to.request(st.req.key)} title={st.req.uri}><span class="mono">{reqLabel(st.req)}</span></Link>
                  {#if st.req.source}<span class="faint mono small"> · {st.req.source}</span>{/if}
                </td>
              </tr>
            {/if}
            <tr class:returned={st.returned} class:noted={st.notes.length}>
              <td class="sticky c0 mono faint small">{st.returned ? '' : fmtTime(e.t)}</td>
              <td class="sticky c1">
                {#if st.returned}
                  <span class="muted small"><Icon name="corner-down-right" size={12} /> instance retournée</span>
                {:else}
                  <Link to={to.event(st.req.key, e.id)} title="Ouvrir l'événement dans sa requête">
                    <span class="badge t-{e.type}">{e.type}</span>
                    <span class="op">{operation(e)}</span>
                  </Link>
                  {#if cause}<span class="badge cause {cause.cls}" title="{cause.help}{cause.detail ? ' — ' + cause.detail : ''}">{cause.label}</span>{/if}
                  {#if e.state}<span class="badge t-PROXY">{e.state}</span>{/if}
                  {#if SPAN_TYPES.has(e.type) && e.dur != null}<span class="faint mono small"> {fmtMs(e.dur)}</span>{/if}
                  <div class="where faint mono small" title={e.caller}>
                    {#if cause?.detail}<span class="cause-detail">{cause.detail}</span> · {/if}{shortFrame(e.caller)}{#if e.trigger} · via {e.trigger}{/if}
                  </div>
                {/if}
              </td>
              <td>
                {#if st.i}<span class="chip mono" style="--h: {hue(st.i)}" title={e.inst ? '@' + e.inst : ''}>{st.i}</span>{/if}
                {#if st.r}<span class="faint"><Icon name="arrow-right" size={11} /></span><span class="chip mono" style="--h: {hue(st.r)}">{st.r}</span>{/if}
              </td>
              <td class="small">
                {#if a}<span class="att {a.cls}" class:changed={st.attChanged}>{a.text}</span>
                {:else if st.s}<span class="chip sess mono" style="--h: {hue(st.s)}">{st.s}</span>{/if}
              </td>
              {#each life.columns as col}
                {@const c = st.snap?.props.get(col)}
                {@const m = st.mark?.prop === col ? st.mark : null}
                <td class="cellwrap">
                  {#if c}<span class="cell c-{c.kind === 'N' ? 'N' : c.state}" class:changed={st.changed.has(col)} title={cellText(c)}>{cellText(c)}</span>{/if}
                  {#if m}<span class="mark m-{m.kind}">{m.text}</span>{/if}
                </td>
              {/each}
            </tr>
            {#each st.notes as n}
              <tr class="note-row"><td class="sticky c0"></td><td colspan={3 + life.columns.length} class="note small">{n}</td></tr>
            {/each}
          {/each}
        </tbody>
      </table>
    </div>
    {#if steps.length > stepLimit}
      <button class="small more" onclick={() => (stepLimit += STEP_PAGE)}>Afficher {Math.min(STEP_PAGE, steps.length - stepLimit)} étapes de plus (sur {steps.length - stepLimit} restantes)</button>
    {/if}
    {#if !life.columns.length}
      <p class="muted small">Aucun instantané d'état reçu : agent antérieur, ou option « Instantanés d'état » désactivée dans la configuration.</p>
    {/if}
  {/if}
</div>

<style>
  .page {
    padding: 12px 20px 32px;
    overflow: auto;
    height: 100%;
    background: var(--panel);
  }

  .crumbs {
    color: var(--muted);
  }

  .warn {
    color: var(--t-proxy);
  }

  .more {
    margin-top: 6px;
  }

  h1 {
    font-size: 20px;
    margin: 6px 0 4px;
  }

  h3 {
    font-size: 13px;
    margin: 18px 0 6px;
  }

  .small {
    font-size: 12px;
  }

  .meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 16px;
  }

  .meta b {
    color: var(--text);
  }

  .meta b.lazy {
    color: var(--t-proxy);
  }

  .findings ul {
    margin: 0;
    padding: 0;
    list-style: none;
    border: 1px solid var(--sel-border);
    background: var(--sel);
    border-radius: 6px;
    max-width: 1100px;
  }

  .findings li {
    padding: 5px 10px;
  }

  .findings li + li {
    border-top: 1px solid var(--sel-border);
  }

  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
  }

  .opts {
    display: flex;
    gap: 14px;
  }

  .legend {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
    margin-bottom: 8px;
  }

  .scroll {
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: 6px;
    max-height: calc(100vh - 260px);
  }

  table.life {
    border-collapse: separate;
    border-spacing: 0;
    min-width: 100%;
  }

  .life th {
    position: sticky;
    top: 0;
    z-index: 2;
    background: var(--panel);
    text-align: left;
    font-size: 11px;
    font-weight: 600;
    color: var(--muted);
    padding: 6px 8px;
    border-bottom: 1px solid var(--border);
    white-space: nowrap;
  }

  .life th.col {
    text-transform: none;
    max-width: 140px;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .life td {
    padding: 4px 8px;
    border-bottom: 1px solid var(--border);
    vertical-align: top;
    background: var(--panel);
  }

  .sticky {
    position: sticky;
    z-index: 1;
  }

  .c0 {
    left: 0;
    min-width: 92px;
  }

  .c1 {
    left: 92px;
    min-width: 300px;
    max-width: 420px;
    border-right: 1px solid var(--border);
  }

  th.sticky {
    z-index: 3;
  }

  .req-row td {
    background: var(--panel-2);
    font-size: 12px;
    padding: 5px 8px;
    position: sticky;
    left: 0;
  }

  tr.noted > td {
    background: color-mix(in srgb, var(--accent) 5%, var(--panel));
  }

  tr.returned > td {
    background: var(--panel-2);
  }

  .note-row td {
    border-bottom: 1px solid var(--border);
    background: color-mix(in srgb, var(--accent) 5%, var(--panel));
  }

  .note {
    color: var(--accent);
    padding-top: 0;
  }

  .op {
    font-weight: 600;
  }

  .where {
    margin-top: 2px;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .cause {
    margin-left: 4px;
  }

  .cause-detail {
    color: var(--muted);
  }

  .chip {
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 10px;
    color: hsl(var(--h) 55% 38%);
    background: hsl(var(--h) 70% 50% / 0.14);
  }

  .chip.sess {
    border-radius: 3px;
  }

  @media (prefers-color-scheme: dark) {
    .chip {
      color: hsl(var(--h) 70% 72%);
    }
  }

  .att {
    white-space: nowrap;
    padding: 1px 6px;
    border-radius: 4px;
  }

  .att.on {
    color: var(--t-util);
    background: color-mix(in srgb, var(--t-util) 12%, transparent);
  }

  .att.off {
    color: var(--t-lazy);
    background: color-mix(in srgb, var(--t-lazy) 12%, transparent);
  }

  .att.proxy {
    color: var(--t-proxy);
    background: color-mix(in srgb, var(--t-proxy) 12%, transparent);
  }

  .cellwrap {
    white-space: nowrap;
  }

  .cell {
    display: inline-block;
    font: 11px var(--mono);
    padding: 1px 6px;
    border-radius: 4px;
    max-width: 160px;
    overflow: hidden;
    text-overflow: ellipsis;
    vertical-align: middle;
  }

  .cell.c-I {
    color: var(--t-util);
    background: color-mix(in srgb, var(--t-util) 12%, transparent);
  }

  .cell.c-L {
    color: var(--muted);
    background: var(--panel-2);
  }

  .cell.c-N {
    color: var(--faint);
  }

  .cell.changed {
    outline: 2px solid var(--accent);
    outline-offset: -1px;
    font-weight: 700;
  }

  .mark {
    display: block;
    font-size: 11px;
    margin-top: 2px;
  }

  .m-lazyread {
    color: var(--t-proxy);
  }

  .m-error {
    color: var(--t-lazy);
    font-weight: 700;
  }

  .m-init {
    color: var(--t-collection);
  }

  .m-read {
    color: var(--muted);
  }
</style>
