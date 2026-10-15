<script>
  import { tick } from 'svelte';
  import { store } from './store.svelte.js';
  import { router, to } from './router.svelte.js';
  import {
    TYPES, TYPE_LABEL, SPAN_TYPES, entityHistory, lifecycle, stateOrigins, causeOf, cellText, operation, subject,
    fmtMs, fmtTime, fmtDate, toLocalInput, hueOf, shortFrame, simple,
  } from './model.js';
  import { remote, qs } from './remote.svelte.js';
  import Link from './Link.svelte';
  import Loading from './Loading.svelte';
  import Tags from './Tags.svelte';
  import EventPanel from './EventPanel.svelte';
  import Icon from './Icon.svelte';

  // Chronologie de tous les événements (ou d'une entité), toutes requêtes et tous threads confondus, à la ms.
  // Les événements d'une même ms sont dans l'ordre d'émission de la JVM (compteur des ids de l'agent).
  const ALL = [...TYPES, 'REQ'];
  const ROW_PAGE = 1000;
  const GAP = 1000; // ms : au-delà, un séparateur signale le temps écoulé

  const route = $derived(router.route);
  const entity = $derived(route.entity || '');
  const eid = $derived(route.id || '');
  const lifeMode = $derived(!!(entity && eid));
  const types = $derived(new Set((route.types || '').split(',').filter(Boolean)));
  const q = $derived(route.q || '');
  const at = $derived(route.at ? Number(route.at) : null);
  const cursor = $derived(!!(route.before || route.after || route.at));

  let allAccess = $state(false);
  let rowLimit = $state(ROW_PAGE);
  let entityInput = $state(router.route.entity || '');
  let idInput = $state(router.route.id || '');
  let filterTimer;

  // Une entité précise : ses événements (références, instances Java, sessions) analysés comme dans le cycle de vie
  const life = remote(() => (lifeMode ? `/api/entity?${qs({ entity, id: eid })}` : null), { interval: 3000 });
  // Sinon : pages servies par la base, dans l'ordre (heure, ordre d'émission) ; suivi en direct sans position
  const page = remote(
    () => {
      if (lifeMode) return null;
      const tys = [...types].flatMap((t) => (t === 'REQ' ? ['REQ_START', 'REQ_END'] : [t]));
      return `/api/timeline?${qs({ before: route.before, after: route.after, at: route.at, types: tys.join(','), agent: route.agent, q, entity })}`;
    },
    { interval: 2000, live: () => !route.before }
  );
  const res = $derived(lifeMode ? life : page);

  const hist = $derived.by(() => {
    const d = life.data;
    if (!lifeMode || !d) return null;
    const items = d.events.map((e, i) => ({ e, req: d.reqs[d.rkeys[i]] || { key: d.rkeys[i] } }));
    return entityHistory(items, entity, eid);
  });
  const lc = $derived(hist ? lifecycle(hist, { allAccess: true }) : null);

  const rows = $derived.by(() => {
    if (lifeMode) {
      if (!lc) return [];
      const text = q.toLowerCase();
      return lc.steps
        .map((st, k) => ({ e: st.e, req: st.req, st, k }))
        .filter(({ e, st }) => {
          if (route.changes && !(st.notes.length || st.changed.size || st.attChanged || st.returned)) return false;
          if (!allAccess && e.type === 'ACCESS' && !e.state && !st.notes.length && !st.changed.size && !st.attChanged) return false;
          if (types.size && !types.has(e.type)) return false;
          if (route.agent && e.agent !== route.agent) return false;
          if (text && ![e.caller, e.inner, e.prop, e.thread, e.el, e.label, e.entity, e.eid, e.req, e.id].join(' ').toLowerCase().includes(text)) return false;
          return true;
        });
    }
    const d = page.data;
    if (!d) return [];
    return d.events.map((e, i) => ({ e, req: d.reqs[d.rkeys[i]] || { key: d.rkeys[i] } }));
  });

  const selIndex = $derived(route.ev ? rows.findIndex((r) => r.e.id === route.ev && !r.st?.returned) : -1);
  const selected = $derived(selIndex >= 0 ? rows[selIndex] : null);
  const origins = $derived(selected?.st && lc ? stateOrigins(lc.steps, selected.k) : null);
  const threads = $derived(new Set(rows.map((r) => r.e.thread)).size);
  const reqCount = $derived(new Set(rows.map((r) => r.req.key)).size);

  // Aller à une heure : en mode entité, défilement vers la première ligne à partir de cette heure
  $effect(() => {
    if (!lifeMode || at == null || !rows.length) return;
    const i = rows.findIndex((r) => r.e.t >= at);
    if (i < 0) return;
    if (i >= rowLimit) rowLimit = i + ROW_PAGE;
    tick().then(() => document.getElementById(`tl-${i}`)?.scrollIntoView({ block: 'center' }));
  });

  // L'événement sélectionné (lien partagé, retour arrière) est amené à l'écran
  $effect(() => {
    if (selIndex < 0) return;
    if (selIndex >= rowLimit) rowLimit = selIndex + ROW_PAGE;
    tick().then(() => document.getElementById(`tl-${selIndex}`)?.scrollIntoView({ block: 'nearest' }));
  });

  function patch(changes, replace = true) {
    router.patch(changes, replace);
  }

  function toggleType(t) {
    const s = new Set(types);
    s.has(t) ? s.delete(t) : s.add(t);
    patch({ types: [...s].join(','), before: null, after: null, at: null });
  }

  function onFilter(v) {
    clearTimeout(filterTimer);
    filterTimer = setTimeout(() => patch({ q: v, before: null, after: null, at: null }), 250);
  }

  function goTo(v) {
    const t = v ? new Date(v).getTime() : null;
    patch({ at: t == null || isNaN(t) ? null : String(t), before: null, after: null }, false);
  }

  function setEntity(ev) {
    ev.preventDefault();
    rowLimit = ROW_PAGE;
    router.go(to.timeline({ entity: entityInput.trim(), id: entityInput.trim() ? idInput.trim() : '', types: route.types, agent: route.agent }));
  }

  function select(r) {
    patch({ ev: route.ev === r.e.id ? null : r.e.id });
  }

  function reqLabel(r) {
    if (!r) return '';
    if (r.background) return r.uri || 'hors requête';
    return `${r.method || ''} ${r.uri || r.id || r.key}`;
  }

  function att(snap) {
    const a = snap?.att;
    if (!a) return null;
    if (a.kind === 'P') return { cls: 'proxy', text: 'proxy' };
    const s = a.sess ? hist.sessLabel(a.sess) : null;
    return a.kind === 'S' ? { cls: 'on', text: `managée${s ? ' ' + s : ''}` } : { cls: 'off', text: `détachée${s ? ' de ' + s : ''}` };
  }

  const day = (t) => new Date(t).toDateString();
  const hue = (label) => (Number(String(label).slice(1)) * 137) % 360;
</script>

<div class="page" class:with-panel={selected}>
  <div class="main">
    <div class="toolbar">
      <div class="line">
        <h1>
          Chronologie
          {#if entity}<span class="mono ent">› {simple(entity)}{eid ? `#${eid}` : ''}</span>{/if}
          <Loading {res} />
        </h1>
        <form class="ent-form" onsubmit={setEntity}>
          <input class="mono" list="tl-entities" placeholder="Entité (vide = tout)" bind:value={entityInput} />
          <input class="mono id" placeholder="id" bind:value={idInput} disabled={!entityInput.trim()} />
          <datalist id="tl-entities">{#each store.entities as e}<option value={e}></option>{/each}</datalist>
          <button type="submit">Afficher</button>
          {#if entity}<button type="button" onclick={() => ((entityInput = ''), (idInput = ''), router.go(to.timeline()))}>Tout</button>{/if}
        </form>
        <label class="goto small muted">
          Aller à
          <input type="datetime-local" step="0.001" value={at != null ? toLocalInput(at) : ''} onchange={(e) => goTo(e.target.value)} />
        </label>
      </div>
      <div class="line">
        <input type="search" class="filter" placeholder={lifeMode ? 'Filtrer : appelant, propriété, thread, EL…' : 'Filtrer : entité, propriété, id, appelant, sonde… #tag'} value={q} oninput={(e) => onFilter(e.target.value)} />
        <div class="chips">
          {#each ALL as t}
            {#if !(lifeMode && t === 'REQ')}
              <button class="badge t-{t}" class:off={types.size && !types.has(t)} onclick={() => toggleType(t)}>{t === 'REQ' ? 'Requêtes' : TYPE_LABEL[t]}</button>
            {/if}
          {/each}
        </div>
        {#if store.agents.length > 1}
          <select value={route.agent || ''} onchange={(e) => patch({ agent: e.target.value, before: null, after: null, at: null })}>
            <option value="">Tous les agents</option>
            {#each store.agents as a}<option value={a.id || a.name}>{a.name}</option>{/each}
          </select>
        {/if}
        {#if lifeMode}
          <label class="small muted"><input type="checkbox" checked={!!route.changes} onchange={(e) => patch({ changes: e.target.checked ? '1' : null })} /> seulement les changements d'état</label>
          <label class="small muted"><input type="checkbox" bind:checked={allAccess} /> tous les accès</label>
        {/if}
      </div>
      <div class="line small muted">
        {#if lifeMode}
          <span>{rows.length} étapes · {hist?.instances || 0} instance(s) Java · {hist?.sessions || 0} session(s) · {threads} thread(s) · {reqCount} requête(s)</span>
          <Link to={to.entity(entity, eid)}>Cycle de vie (tableau) <Icon name="arrow-right" size={12} /></Link>
          {#if life.data?.truncated}<span class="warn">entité très sollicitée : seuls ses événements les plus récents sont analysés</span>{/if}
        {:else}
          <span>
            {rows.length} événements{#if rows.length}, du {fmtDate(rows[0].e.t)} {fmtTime(rows[0].e.t)} au {fmtTime(rows[rows.length - 1].e.t)}{/if}
            · {threads} thread(s) · {reqCount} requête(s)
          </span>
          <span class="pager">
            <button disabled={!page.data?.hasOlder} onclick={() => patch({ before: page.data.first, after: null, at: null }, false)}><Icon name="arrow-left" />Plus anciens</button>
            <button disabled={!page.data?.hasNewer} onclick={() => patch({ after: page.data.last, before: null, at: null }, false)}>Plus récents<Icon name="arrow-right" /></button>
            <button disabled={!cursor} onclick={() => patch({ before: null, after: null, at: null }, false)} title="Derniers événements, suivis en direct"><Icon name="skip-forward" />Derniers</button>
            {#if !cursor}<span class="live"><span class="live-dot"></span>en direct</span>{/if}
          </span>
        {/if}
      </div>
    </div>

    <table class="grid tl">
      <thead>
        <tr>
          <th>Heure</th>
          <th class="num" title="Temps écoulé depuis la ligne précédente">+Δ</th>
          <th>Thread</th>
          <th>Requête</th>
          <th>Événement</th>
          {#if lifeMode}<th>Instance</th><th>État après</th>{/if}
          <th>Emplacement dans le code</th>
          <th class="num">Durée</th>
        </tr>
      </thead>
      <tbody>
        {#each rows.slice(0, rowLimit) as r, i (r.st ? `${r.k}` : r.e.agent + '|' + r.e.id + '|' + r.e.type)}
          {@const e = r.e}
          {@const prev = rows[i - 1]?.e}
          {@const d = prev ? e.t - prev.t : null}
          {@const cause = causeOf(e)}
          {@const st = r.st}
          {@const a = st ? att(st.snap) : null}
          {@const cols = lifeMode ? 9 : 7}
          {#if !prev || day(prev.t) !== day(e.t)}
            <tr class="sep day"><td colspan={cols}>{fmtDate(e.t)}</td></tr>
          {:else if d > GAP}
            <tr class="sep gap"><td colspan={cols}><Icon name="clock" size={12} /> {fmtMs(d)} plus tard</td></tr>
          {/if}
          <tr
            id="tl-{i}"
            class="clickable"
            class:sel={selected === r}
            class:returned={st?.returned}
            class:noted={st?.notes.length}
            class:at={at != null && e.t >= at && (!prev || prev.t < at)}
            onclick={() => select(r)}
          >
            <td class="mono time" title={new Date(e.t).toISOString()}>{st?.returned ? '' : fmtTime(e.t)}</td>
            <td class="num faint">{d == null || st?.returned ? '' : `+${d}`}</td>
            <td class="thread">
              {#if !st?.returned && e.thread}
                {@const switched = prev && prev.thread !== e.thread}
                <span class="chip mono" style="--h: {hueOf(e.thread)}" class:switched title={switched ? `Changement de thread (avant : ${prev.thread})` : e.thread}>
                  {#if switched}<Icon name="switch" size={11} />{/if}{e.thread}
                </span>
              {/if}
            </td>
            <td class="req">
              {#if !st?.returned}
                {@const changed = !prev || rows[i - 1].req.key !== r.req.key}
                <Link to={to.event(r.req.key, e.id)} class="mono small {changed ? '' : 'same'}" title="{reqLabel(r.req)} — ouvrir l'événement dans sa requête">
                  {#if changed}{reqLabel(r.req)}{:else}<span class="same" aria-label="même requête"></span>{/if}
                </Link>
              {/if}
            </td>
            <td class="what">
              {#if st?.returned}
                <span class="muted small"><Icon name="corner-down-right" size={12} /> instance retournée</span>
              {:else}
                <span class="badge t-{e.type.startsWith('REQ') ? 'REQ' : e.type}">{e.type === 'REQ_START' ? 'DÉBUT' : e.type === 'REQ_END' ? 'FIN' : e.type}</span>
                <span class="mono small">{lifeMode ? operation(e) : e.type.startsWith('REQ') ? reqLabel(r.req) : subject(e)}</span>
                {#if cause}<span class="badge {cause.cls}" title="{cause.help}{cause.detail ? ' — ' + cause.detail : ''}">{cause.label}</span>{/if}
                {#if e.state}<span class="badge t-PROXY">{e.state}</span>{/if}
                {#if e.error && e.type !== 'LAZY_ERROR'}<span class="badge t-LAZY_ERROR">erreur</span>{/if}
                <Tags event={e} links={false} />
              {/if}
            </td>
            {#if lifeMode}
              <td>
                {#if st.i}<span class="chip mono" style="--h: {hue(st.i)}" title={e.inst ? '@' + e.inst : ''}>{st.i}</span>{/if}
                {#if st.r}<span class="faint"><Icon name="arrow-right" size={11} /></span><span class="chip mono" style="--h: {hue(st.r)}">{st.r}</span>{/if}
              </td>
              <td class="small state">
                {#if a}<span class="att {a.cls}" class:changed={st.attChanged}>{a.text}</span>{/if}
                {#each [...st.changed] as name}
                  <span class="prop changed mono">{name} : {cellText(st.snap.props.get(name))}</span>
                {/each}
                {#if st.mark && st.mark.kind !== 'read'}<span class="prop mark-{st.mark.kind} mono">{st.mark.prop} {st.mark.text}</span>{/if}
              </td>
            {/if}
            <td class="mono small where" title={e.caller}>
              {#if e.el}<span class="el">{e.el}</span>{/if}
              {shortFrame(e.caller)}{#if e.inner && e.inner !== e.caller}<span class="faint"> · {shortFrame(e.inner)}</span>{/if}
              {#if e.trigger}<span class="faint"> via {e.trigger}</span>{/if}
            </td>
            <td class="num">{SPAN_TYPES.has(e.type) || e.type === 'REQ_END' ? fmtMs(e.dur) : ''}</td>
          </tr>
          {#if st?.notes.length}
            {#each st.notes as n}
              <tr class="note-row"><td colspan="2"></td><td colspan={cols - 2} class="note small">{n}</td></tr>
            {/each}
          {/if}
        {:else}
          <tr><td colspan="9" class="muted">{res.data ? 'Aucun événement' + (q || types.size ? ' pour ces filtres.' : '.') : 'Chargement…'}</td></tr>
        {/each}
      </tbody>
    </table>
    {#if rows.length > rowLimit}
      <button class="more small" onclick={() => (rowLimit += ROW_PAGE)}>Afficher {Math.min(ROW_PAGE, rows.length - rowLimit)} lignes de plus (sur {rows.length - rowLimit})</button>
    {/if}
    {#if !lifeMode && page.data?.hasNewer && cursor}
      <button class="more small" onclick={() => patch({ after: page.data.last, before: null, at: null }, false)}>Plus récents<Icon name="arrow-right" /></button>
    {/if}
  </div>

  {#if selected}
    <div class="side">
      {#if origins}
        <section class="origins">
          <h3>Pourquoi cet état ici ?</h3>
          <p class="small muted">
            Instance <span class="chip mono" style="--h: {hue(selected.st.i)}">{selected.st.i}</span> au moment de cette étape
            ({fmtTime(selected.e.t)}, thread <span class="mono">{selected.e.thread || '?'}</span>). Chaque état est rattaché à l'étape qui
            l'a établi, quel que soit le thread ou la requête.
          </p>
          <ul class="orig">
            {#if origins.inst}
              {@render origin('Instance', origins.inst.step.e.type === 'LOAD' ? 'chargée' : 'apparue', origins.inst.step, origins.inst.established)}
            {/if}
            {#if origins.att}
              {@render origin('Attachement', origins.att.text, origins.att.step, origins.att.established)}
            {/if}
            {#each [...origins.props] as [name, o]}
              {@render origin(name, cellText(o.cell), o.step, o.established)}
            {/each}
          </ul>
          {#if origins.trail.length > 1}
            <h4>Changements de cette instance jusqu'ici</h4>
            <ol class="trail small">
              {#each origins.trail as t}
                {@const sw = t.step.e.thread !== selected.e.thread || t.step.req.key !== selected.req.key}
                <li class:current={t.current}>
                  <button class="linklike" onclick={() => patch({ ev: t.step.e.id })}>
                    <span class="mono faint">{fmtTime(t.step.e.t)}</span>
                    <span class="badge t-{t.step.e.type}">{t.step.e.type}</span>
                    <span class="mono">{operation(t.step.e)}</span>
                  </button>
                  {#if sw}<span class="other" title="Autre thread ou autre requête que l'étape sélectionnée"><Icon name="switch" size={11} /> {t.step.e.thread} · {reqLabel(t.step.req)}</span>{/if}
                  {#if t.what.length}<div class="faint">{t.what.join(' · ')}</div>{/if}
                  <div class="mono faint">{shortFrame(t.step.e.caller)}</div>
                </li>
              {/each}
            </ol>
          {/if}
        </section>
      {/if}
      <EventPanel event={selected.e} onclose={() => patch({ ev: null })} />
    </div>
  {/if}
</div>

{#snippet origin(label, value, step, established)}
  {@const e = step.e}
  {@const other = e.thread !== selected.e.thread || step.req.key !== selected.req.key}
  <li>
    <div><span class="mono lbl">{label}</span> <b>{value}</b></div>
    <div class="small">
      {established ? 'établi par' : 'constaté dès'}
      <button class="linklike" onclick={() => patch({ ev: e.id })}>
        <span class="badge t-{e.type}">{e.type}</span> <span class="mono">{operation(e)}</span>
      </button>
      <span class="mono faint">{fmtDate(e.t)} {fmtTime(e.t)}</span>
    </div>
    <div class="small mono where-o" title={e.caller}>{e.el ? e.el + ' · ' : ''}{shortFrame(e.caller)}</div>
    <div class="small" class:other>
      {#if other}<Icon name="switch" size={11} /> {/if}thread <span class="mono">{e.thread || '?'}</span> · <Link to={to.event(step.req.key, e.id)} class="mono">{reqLabel(step.req)}</Link>
    </div>
    {#if !established}<div class="small faint">L'opération qui a produit cet état n'a pas été capturée (type désactivé, sonde absente ou antérieure à l'enregistrement).</div>{/if}
  </li>
{/snippet}

<style>
  .page {
    height: 100%;
    display: grid;
    grid-template-columns: 1fr;
    background: var(--panel);
  }

  .page.with-panel {
    grid-template-columns: 1fr minmax(340px, 32%);
  }

  .main {
    overflow: auto;
    min-width: 0;
  }

  .side {
    overflow: auto;
    min-width: 0;
    border-left: 1px solid var(--border);
    display: flex;
    flex-direction: column;
  }

  .side :global(.panel) {
    border-left: none;
    overflow: visible;
  }

  .toolbar {
    position: sticky;
    top: 0;
    z-index: 2;
    background: var(--panel);
    border-bottom: 1px solid var(--border);
    padding: 8px 14px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  }

  h1 {
    font-size: 18px;
    margin: 0 8px 0 0;
  }

  .ent {
    font-size: 15px;
    font-weight: normal;
  }

  .ent-form {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .ent-form input {
    width: 260px;
  }

  .ent-form input.id {
    width: 90px;
  }

  .goto {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .filter {
    max-width: 360px;
  }

  .chips {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
  }

  .chips .badge {
    min-height: 0;
    border: none;
    cursor: pointer;
    padding: 4px 7px;
  }

  .chips .badge.off {
    opacity: 0.35;
  }

  .pager {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    margin-left: auto;
  }

  .live {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--ok);
    font-weight: 500;
  }

  .live-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--ok);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--ok) 20%, transparent);
  }

  /* Même requête que la ligne précédente : un filet de continuité au lieu de répéter le libellé */
  .same {
    display: inline-block;
    width: 1px;
    height: 14px;
    margin-left: 8px;
    vertical-align: middle;
    background: var(--border-strong);
  }

  .chip :global(.icon) {
    margin-right: 3px;
    vertical-align: -0.1em;
  }

  .warn {
    color: var(--t-proxy);
  }

  .tl th {
    top: 0;
  }

  .tl td {
    padding: 3px 8px;
  }

  .time {
    white-space: nowrap;
  }

  tr.sel td {
    background: var(--sel);
  }

  tr.at td {
    box-shadow: inset 0 2px 0 var(--accent);
  }

  tr.sep td {
    background: var(--panel-2);
    font-size: 11px;
    color: var(--muted);
    padding: 2px 8px;
  }

  tr.sep.day td {
    font-weight: 600;
    color: var(--text);
  }

  tr.returned td {
    border-top: none;
  }

  tr.noted td {
    border-bottom: none;
  }

  .note-row td {
    border-top: none;
  }

  .note {
    color: var(--t-proxy);
    padding-top: 0 !important;
  }

  .chip {
    display: inline-block;
    padding: 0 5px;
    border-radius: 4px;
    font-size: 11px;
    line-height: 17px;
    white-space: nowrap;
    color: hsl(var(--h) 55% 40%);
    background: hsl(var(--h) 60% 50% / 0.13);
  }

  .thread .chip {
    max-width: 180px;
    overflow: hidden;
    text-overflow: ellipsis;
    vertical-align: middle;
  }

  .chip.switched {
    outline: 1px solid hsl(var(--h) 55% 45%);
    font-weight: 600;
  }

  .req :global(a) {
    display: inline-block;
    max-width: 200px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    vertical-align: middle;
  }

  .req :global(a.same) {
    color: var(--faint);
  }

  .what {
    white-space: nowrap;
  }

  .where {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 26vw;
  }

  .el {
    color: var(--t-util);
    margin-right: 6px;
  }

  .state .att,
  .state .prop {
    display: inline-block;
    margin: 0 4px 1px 0;
    padding: 0 4px;
    border-radius: 3px;
    white-space: nowrap;
  }

  .att.on {
    color: var(--t-util);
  }

  .att.off {
    color: var(--t-lazy);
  }

  .att.proxy {
    color: var(--t-proxy);
  }

  .changed {
    background: color-mix(in srgb, var(--accent) 16%, transparent);
    font-weight: 600;
  }

  .prop.mark-init {
    color: var(--t-collection);
  }

  .prop.mark-lazyread,
  .prop.mark-error {
    color: var(--t-lazy);
  }

  .more {
    margin: 10px 14px;
  }

  .origins {
    padding: 10px 14px 4px;
    border-bottom: 1px solid var(--border);
  }

  .origins h3 {
    font-size: 14px;
    margin: 0 0 4px;
  }

  .origins h4 {
    font-size: 12px;
    margin: 12px 0 4px;
    color: var(--muted);
  }

  .orig {
    list-style: none;
    padding: 0;
    margin: 8px 0 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .orig li {
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: 6px;
  }

  .lbl {
    color: var(--muted);
  }

  .where-o {
    color: var(--muted);
    word-break: break-all;
  }

  .other {
    color: var(--t-proxy);
  }

  .trail {
    padding-left: 18px;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: 5px;
  }

  .trail li.current {
    font-weight: 600;
  }

  .linklike {
    min-height: 0;
    box-shadow: none;
    font-weight: inherit;
    background: none;
    border: none;
    padding: 0;
    cursor: pointer;
    color: inherit;
    text-align: left;
  }

  .linklike:hover {
    text-decoration: underline;
  }

  .small {
    font-size: 12px;
  }
</style>
