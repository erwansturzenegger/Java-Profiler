<script>
  import { tagsOf } from './model.js';
  import { to } from './router.svelte.js';
  import Link from './Link.svelte';

  /**
   * Tags d'un événement : propres (sonde, règles) en plein, hérités d'un span englobant en pointillé.
   * links=false dans un bouton (arbre) : un lien n'y est pas permis.
   */
  let { event, inherited = true, links = true } = $props();

  const own = $derived(tagsOf(event));
  const scope = $derived(inherited ? (event.scope || []).filter((t) => !own.includes(t)) : []);
</script>

{#if links}
  {#each own as t}<Link to={to.measures(t)} class="tag" title="Mesures du tag « {t} » (Ctrl+clic : nouvel onglet)">#{t}</Link>{/each}
  {#each scope as t}<Link to={to.measures(t)} class="tag inherited" title="Hérité d'un span englobant — mesures du tag « {t} »">#{t}</Link>{/each}
{:else}
  {#each own as t}<span class="tag">#{t}</span>{/each}
  {#each scope as t}<span class="tag inherited" title="Hérité d'un span englobant">#{t}</span>{/each}
{/if}
