<script>
  import Icon from './Icon.svelte';

  // Système -> clair -> sombre ; mémorisé dans ce navigateur (appliqué avant le rendu par index.html)
  const ORDER = ['system', 'light', 'dark'];
  const LABEL = { system: 'Thème : système', light: 'Thème : clair', dark: 'Thème : sombre' };
  const ICON = { system: 'monitor', light: 'sun', dark: 'moon' };

  let mode = $state(read());

  function read() {
    try {
      const t = localStorage.getItem('ep-theme');
      return t === 'light' || t === 'dark' ? t : 'system';
    } catch {
      return 'system';
    }
  }

  function next() {
    mode = ORDER[(ORDER.indexOf(mode) + 1) % ORDER.length];
    if (mode === 'system') delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = mode;
    try {
      if (mode === 'system') localStorage.removeItem('ep-theme');
      else localStorage.setItem('ep-theme', mode);
    } catch {}
  }
</script>

<button class="ghost icon-btn" onclick={next} title="{LABEL[mode]} (cliquer pour changer)" aria-label={LABEL[mode]}>
  <Icon name={ICON[mode]} size={15} />
</button>
