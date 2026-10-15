<script>
  import { untrack } from 'svelte';
  import { store } from './store.svelte.js';
  import { fmtMs, shortFrame, subject, causeOf, SPAN_TYPES } from './model.js';
  import { to } from './router.svelte.js';
  import Link from './Link.svelte';
  import TreeNode from './TreeNode.svelte';
  import Tags from './Tags.svelte';

  let { node, depth, t0 } = $props();
  let open = $state(untrack(() => depth < 2));
  let groupOpen = $state(false);
  // Enfants affichés : par tranches, une collection de milliers d'éléments ne gèle pas la page
  const CHILD_LIMIT = 300;
  let shown = $state(CHILD_LIMIT);

  const ev = $derived(node.ev);
  const hasChildren = $derived(!node.group && node.children?.length > 0);
  const childCount = $derived(hasChildren ? countAll(node.children) : 0);

  function countAll(list) {
    let n = 0;
    for (const c of list) n += c.group ? c.items.length : 1 + (c.children ? countAll(c.children) : 0);
    return n;
  }
</script>

<div class="row" class:sel={store.selectedEvent === ev} style="padding-left: {8 + depth * 18}px">
  {#if hasChildren}
    <button class="tw" onclick={() => (open = !open)} aria-label={open ? 'Replier' : 'Déplier'}>{open ? '▾' : '▸'}</button>
  {:else if node.group}
    <button class="tw" onclick={() => (groupOpen = !groupOpen)} aria-label="Détail">{groupOpen ? '▾' : '▸'}</button>
  {:else}
    <span class="tw"></span>
  {/if}
  <button class="main" onclick={() => (store.selectedEvent = ev)}>
    <span class="off mono faint">+{Math.max(0, ev.t - t0)}</span>
    <span class="badge t-{ev.type}">{ev.type}</span>
    {#if node.group}
      <span class="mono"><b>{node.items.length} ×</b> {ev.type === 'ACCESS' ? subject({ ...ev, eid: null }) : ev.entity?.split('.').pop()}</span>
    {:else}
      <span class="mono subj">{subject(ev)}</span>
    {/if}
    {#if ev.state}<span class="badge t-PROXY">{ev.state}</span>{/if}
    {#if causeOf(ev) && ev.type !== 'LOAD'}<span class="badge {causeOf(ev).cls}" title={causeOf(ev).help}>{causeOf(ev).label}</span>{/if}
    {#if ev.type === 'LOAD' && causeOf(ev)?.kind === 'lazy'}<span class="badge t-PROXY" title={causeOf(ev).detail}>lazy</span>{/if}
    {#if ev.error && ev.type !== 'LAZY_ERROR'}<span class="badge t-LAZY_ERROR">erreur</span>{/if}
    {#if !node.group}<Tags event={ev} inherited={false} links={false} />{/if}
    <span class="where mono">
      {#if ev.el}<span class="el">{ev.el}</span>{/if}
      <span>{shortFrame(ev.caller)}</span>
      {#if ev.trigger}<span class="faint">via {ev.trigger}</span>{/if}
    </span>
    {#if hasChildren && !open}<span class="faint small">{childCount} enfants</span>{/if}
    {#if SPAN_TYPES.has(ev.type)}<span class="dur mono" class:slow={ev.dur > 50}>{fmtMs(ev.dur)}</span>{/if}
  </button>
</div>

{#if node.group && groupOpen}
  <div class="ids mono faint" style="padding-left: {44 + depth * 18}px">
    ids :
    {#each node.items.filter((e) => e.eid != null) as e, k}{k ? ', ' : ''}<Link to={to.entity(e.entity, e.eid)} class="id" title="Cycle de vie de cette entité (Ctrl+clic : nouvel onglet)">{e.eid}</Link>{/each}
  </div>
{/if}

{#if hasChildren && open}
  {#each node.children.slice(0, shown) as child}
    <TreeNode node={child} depth={depth + 1} {t0} />
  {/each}
  {#if node.children.length > shown}
    <div class="more-children" style="padding-left: {44 + depth * 18}px">
      <button onclick={() => (shown += CHILD_LIMIT * 3)}>… {node.children.length - shown} de plus</button>
    </div>
  {/if}
{/if}

<style>
  .ids :global(a.id) {
    color: inherit;
    text-decoration: underline dotted;
  }

  .ids :global(a.id:hover) {
    color: var(--text);
  }

  .row {
    display: flex;
    align-items: center;
    gap: 2px;
    border-bottom: 1px solid color-mix(in srgb, var(--border) 50%, transparent);
  }

  .row:hover {
    background: var(--panel-2);
  }

  .row.sel {
    background: var(--sel);
  }

  .tw {
    width: 18px;
    flex: none;
    border: none;
    background: none;
    padding: 0;
    color: var(--muted);
    text-align: center;
  }

  .main {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 8px;
    border: none;
    background: none;
    text-align: left;
    padding: 4px 12px 4px 0;
    border-radius: 0;
    overflow: hidden;
  }

  .off {
    width: 46px;
    flex: none;
    text-align: right;
    font-size: 11px;
  }

  .subj {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 50%;
  }

  .where {
    display: flex;
    gap: 8px;
    font-size: 11.5px;
    color: var(--muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    min-width: 0;
    flex: 1;
  }

  .el {
    color: var(--t-collection);
  }

  .dur {
    margin-left: auto;
    font-size: 12px;
    color: var(--muted);
    flex: none;
  }

  .dur.slow {
    color: var(--t-lazy);
    font-weight: 600;
  }

  .small {
    font-size: 11px;
    flex: none;
  }

  .more-children button {
    font-size: 11px;
    margin: 3px 0;
  }

  .ids {
    font-size: 11px;
    padding: 2px 12px 6px;
    word-break: break-all;
  }
</style>
