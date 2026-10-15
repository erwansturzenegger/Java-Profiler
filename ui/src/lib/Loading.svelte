<script>
  // Indicateur discret de chargement / d'erreur d'une donnée distante (remote)
  let { res } = $props();
</script>

{#if res.error}
  <span class="state err" title={res.error}>erreur : {res.error}</span>
{:else if res.loading}
  <span class="state muted">chargement…</span>
{:else if res.ms != null}
  <span class="state faint" title="Temps de réponse du serveur">{res.ms} ms</span>
{/if}

<style>
  .state {
    font: 11px var(--mono);
    font-weight: normal;
    margin-left: 8px;
    vertical-align: middle;
  }

  .err {
    color: var(--t-lazy);
  }
</style>
