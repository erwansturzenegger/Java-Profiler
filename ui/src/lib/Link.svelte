<script>
  import { router } from './router.svelte.js';

  // Vrai lien : clic = navigation interne (historique), Ctrl/Cmd/Maj/molette = nouvel onglet ou fenêtre
  let { to, children, class: cls = '', title = undefined, newTab = false } = $props();

  function click(e) {
    if (newTab || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    e.stopPropagation();
    router.go(to);
  }
</script>

<a
  href={router.href(to)}
  class="lnk {cls}"
  {title}
  target={newTab ? '_blank' : undefined}
  rel={newTab ? 'noopener' : undefined}
  onclick={click}
  onauxclick={(e) => e.stopPropagation()}>{@render children?.()}</a
>
