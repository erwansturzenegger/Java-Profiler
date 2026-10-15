<script>
  // Démarre / arrête le mode focus sur une entité (id) ou sur tout un type (sans id)
  import { store } from './store.svelte.js';
  import { simple } from './model.js';

  let { entity, id = null } = $props();

  const on = $derived(
    !!store.focus && simple(store.focus.entity) === simple(entity) && String(store.focus.id ?? '') === String(id ?? '')
  );
  const what = $derived(id != null ? 'cette entité' : 'ce type');
</script>

{#if on}
  <span class="focus on">
    focus actif
    <button onclick={() => store.stopFocus()}>Arrêter le focus</button>
  </span>
{:else}
  <button
    class="focus"
    onclick={() => store.startFocus(entity, id, store.focus ? store.focus.access !== false : true)}
    title="N'enregistrer que les changements d'état et les accès de {what} (pas de SQL ni d'autres entités){store.focus ? ' — remplace le focus en cours' : ''}"
  >
    Focus sur {what}
  </button>
{/if}

<style>
  .focus {
    font: 12px var(--sans);
    font-weight: normal;
    margin-left: 10px;
    vertical-align: middle;
  }

  span.focus {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 2px 4px 2px 10px;
    border-radius: 6px;
    background: var(--sel);
    border: 1px solid var(--sel-border);
  }

  span.focus button {
    padding: 1px 8px;
    font-size: 12px;
  }
</style>
