import { store } from './store.svelte.js';

export { qs } from './store.svelte.js';

/**
 * Données chargées depuis le serveur pour une vue : rechargées quand l'URL change, et quand de nouveaux
 * événements arrivent (au plus toutes les `interval` ms, et seulement tant que la vue est affichée).
 * À appeler pendant l'initialisation d'un composant.
 */
export function remote(urlFn, { interval = 3000, live = () => true } = {}) {
  const r = new Remote();
  let url = null;
  let seen = -1;
  let last = 0;
  let timer = null;
  let ticket = 0;

  async function load(u) {
    const mine = ++ticket;
    r.loading = true;
    const t0 = performance.now();
    try {
      const res = await fetch(u);
      const data = await res.json().catch(() => null);
      if (mine !== ticket) return;
      if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);
      r.data = data;
      r.error = null;
      r.ms = Math.round(performance.now() - t0);
    } catch (e) {
      if (mine === ticket) r.error = e.message;
    } finally {
      if (mine === ticket) {
        r.loading = false;
        last = Date.now();
      }
    }
  }

  $effect(() => {
    const u = urlFn();
    const v = store.version;
    const follow = live();
    clearTimeout(timer);
    if (!u) {
      url = null;
      r.data = null;
      return;
    }
    if (u !== url) {
      // Les données précédentes restent affichées pendant le chargement (pas de clignotement en filtrant)
      url = u;
      seen = v;
      load(u);
      return;
    }
    if (v === seen || !follow) return;
    // Nouveaux événements : rechargement espacé (les agrégats lourds ne sont pas recalculés à chaque lot)
    timer = setTimeout(() => {
      seen = v;
      load(u);
    }, Math.max(0, interval - (Date.now() - last)));
    return () => clearTimeout(timer);
  });

  r.reload = () => url && load(url);
  return r;
}

class Remote {
  data = $state.raw(null);
  loading = $state(false);
  error = $state(null);
  ms = $state(null);
}

