import { untrack } from 'svelte';
import { router } from './router.svelte.js';

// Requêtes chargées par page dans la liste
const PAGE = 300;

/**
 * État global : connexion SSE au serveur, liste des requêtes (une page), requête affichée, config dynamique.
 * L'historique complet reste sur le serveur (SQLite) : le navigateur ne charge que ce qu'il affiche. Le flux SSE
 * ne transporte que des notifications (« ces requêtes ont changé »), jamais les événements eux-mêmes.
 */
class Store {
  connected = $state(false);
  config = $state(null);
  agents = $state.raw([]);
  entities = $state.raw([]);
  /** Profils du serveur (server/profiles/*.json) et handlers disponibles. */
  profiles = $state.raw([]);
  handlers = $state.raw({});
  /** Volumes stockés sur le serveur. */
  stats = $state.raw({ events: 0, requests: 0 });
  /** Incrémenté à chaque notification du serveur : les vues ouvertes se rechargent (espacé). */
  version = $state(0);

  /** Liste des requêtes : page chargée, nombre total correspondant au filtre. */
  requests = $state.raw([]);
  requestTotal = $state(0);
  listFilter = $state.raw({ q: '', hideEmpty: true, problems: false });
  listLimit = $state(PAGE);

  /** Suit la dernière requête reçue tant qu'aucune requête n'est choisie (ni dans l'URL). */
  follow = $state(!router.route.req);
  #latest = $state(null);
  /** Requête affichée : résumé + événements ({ ...résumé, events, truncated }). */
  #detail = $state.raw(null);
  detailLoading = $state(false);
  detailError = $state(null);

  #source = null;
  #list = { busy: false, again: false, last: 0, timer: null };
  #det = { ticket: 0, last: 0, timer: null };

  constructor() {
    // Chargement de la requête affichée quand la sélection change (URL, mode suivi)
    $effect.root(() => {
      $effect(() => {
        const key = this.selectedKey;
        untrack(() => this.#loadDetail(key));
      });
    });
  }

  connect() {
    this.#source?.close();
    const es = new EventSource('/api/stream');
    this.#source = es;
    es.addEventListener('hello', (m) => {
      const s = JSON.parse(m.data);
      this.connected = true;
      this.config = s.config;
      this.agents = s.agents;
      this.entities = s.entities;
      this.profiles = s.profiles || [];
      this.handlers = s.handlers || {};
      this.stats = s.stats || this.stats;
      this.#refresh(true);
    });
    // Nouveaux événements stockés : keys = requêtes modifiées (null = trop nombreuses)
    es.addEventListener('changed', (m) => {
      const d = JSON.parse(m.data);
      this.stats = { events: d.events, requests: d.requests };
      this.version++;
      this.#refreshList();
      const key = this.#detail?.key;
      if (key && (!d.keys || d.keys.includes(key))) this.#scheduleDetail();
    });
    // Historique vidé, importé ou retagué : tout est rechargé
    es.addEventListener('reload', (m) => {
      const d = JSON.parse(m.data || '{}');
      if (d.events != null) this.stats = d;
      this.#refresh(true);
    });
    es.addEventListener('config', (m) => (this.config = JSON.parse(m.data)));
    es.addEventListener('agents', (m) => {
      const d = JSON.parse(m.data);
      this.agents = d.agents;
      this.entities = d.entities;
    });
    es.addEventListener('profiles', (m) => {
      const d = JSON.parse(m.data);
      this.profiles = d.profiles;
      this.handlers = d.handlers;
    });
    es.onerror = () => (this.connected = false);
  }

  #refresh(now) {
    this.version++;
    this.#refreshList(now);
    this.#loadDetail(this.selectedKey);
  }

  // ------------------------------------------------------------------ liste des requêtes

  /** Recharge la page de la liste (au plus une fois par seconde, sauf now). */
  #refreshList(now = false) {
    const s = this.#list;
    if (s.busy) {
      s.again = true;
      return;
    }
    clearTimeout(s.timer);
    const wait = now ? 0 : Math.max(0, 1000 - (Date.now() - s.last));
    s.timer = setTimeout(async () => {
      s.busy = true;
      s.again = false;
      try {
        const { q, hideEmpty, problems } = this.listFilter;
        const d = await api(`/api/requests?${qs({ q: q.trim(), hideEmpty: hideEmpty ? 1 : 0, problems, limit: this.listLimit })}`);
        this.requests = d.items;
        this.requestTotal = d.total;
        this.#latest = d.latest;
      } catch {
        // serveur indisponible : la connexion SSE le signale
      } finally {
        s.busy = false;
        s.last = Date.now();
        if (s.again) this.#refreshList();
      }
    }, wait);
  }

  setListFilter(patch) {
    this.listFilter = { ...this.listFilter, ...patch };
    this.listLimit = PAGE;
    this.#refreshList(true);
  }

  moreRequests() {
    this.listLimit += PAGE;
    this.#refreshList(true);
  }

  // ------------------------------------------------------------------ requête affichée

  /** Requête affichée : celle de l'URL, sinon la dernière reçue en mode suivi. */
  get selectedKey() {
    return router.route.req ?? (this.follow ? this.#latest : null);
  }

  get selected() {
    const d = this.#detail;
    return d && d.key === this.selectedKey ? d : null;
  }

  async #loadDetail(key) {
    clearTimeout(this.#det.timer);
    const ticket = ++this.#det.ticket;
    if (!key) {
      this.#detail = null;
      this.detailLoading = false;
      return;
    }
    const changing = this.#detail?.key !== key;
    if (changing) this.detailLoading = true;
    try {
      const d = await api(`/api/request?${qs({ key })}`);
      if (ticket !== this.#det.ticket) return;
      this.#detail = { ...d.req, events: d.events, truncated: d.truncated };
      this.detailError = null;
    } catch (e) {
      if (ticket !== this.#det.ticket) return;
      if (changing) this.#detail = null;
      this.detailError = e.message;
    } finally {
      if (ticket === this.#det.ticket) {
        this.detailLoading = false;
        this.#det.last = Date.now();
      }
    }
  }

  /** La requête affichée a reçu des événements : rechargement espacé selon sa taille. */
  #scheduleDetail() {
    const s = this.#det;
    if (s.timer) return;
    const size = this.#detail?.events.length || 0;
    const wait = Math.max(0, 1000 + size / 20 - (Date.now() - s.last));
    s.timer = setTimeout(() => {
      s.timer = null;
      this.#loadDetail(this.selectedKey);
    }, wait);
  }

  /** Événement sélectionné (paramètre ev de l'URL), retrouvé dans la requête affichée. */
  get selectedEvent() {
    const id = router.route.ev;
    const r = this.selected;
    if (!id || !r) return null;
    if (r._byIdLen !== r.events.length) {
      r._byId = new Map(r.events.map((e) => [e.id, e]));
      r._byIdLen = r.events.length;
    }
    return r._byId.get(id) || null;
  }

  /** Sélection d'un événement : l'URL est mise à jour sans créer d'entrée d'historique. */
  set selectedEvent(e) {
    if (e && !router.route.req) {
      // En mode suivi, on fige la requête : sinon l'événement disparaîtrait à la requête suivante
      this.follow = false;
      router.patch({ req: this.selectedKey, ev: e.id }, true);
      return;
    }
    router.patch({ ev: e?.id }, true);
  }

  select(key) {
    this.follow = false;
    router.go({ req: key, tab: router.route.tab, q: router.route.q });
  }

  setFollow(on) {
    this.follow = on;
    if (on) router.go({});
  }

  /**
   * Réglages effectifs pour un agent : configuration + réglages des profils qui lui sont appliqués
   * (même fusion que le serveur).
   */
  settingsFor(agentId) {
    const agent = this.agents.find((a) => a.id === agentId);
    const applied = this.profiles.filter((p) => agent?.profiles?.includes(p.name));
    const out = {};
    for (const k of ['callerPackages', 'callerExclude', 'accessExclude', 'libraryPackages']) {
      const all = new Set(this.config?.[k] || []);
      for (const p of applied) for (const v of p.settings?.[k] || []) all.add(v);
      out[k] = [...all];
    }
    return out;
  }

  async updateConfig(patch) {
    this.config = { ...this.config, ...patch, types: { ...this.config.types, ...(patch.types || {}) } };
    await fetch('/api/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    });
  }

  /** Mode focus actif : { entity, id?, access } ou null. */
  get focus() {
    return this.config?.focus?.entity ? this.config.focus : null;
  }

  /** Mode focus : seuls les changements d'état et les accès de cette entité (id facultatif) sont enregistrés. */
  startFocus(entity, id = null, access = true) {
    const v = id == null ? '' : String(id).trim();
    return this.updateConfig({ focus: { entity, id: v || null, access }, recording: true });
  }

  stopFocus() {
    return this.updateConfig({ focus: null });
  }

  async resetConfig() {
    this.config = await fetch('/api/config/reset', { method: 'POST' }).then((r) => r.json());
  }

  async clear() {
    await fetch('/api/clear', { method: 'POST' });
    this.setFollow(true);
  }

  /** JVM locales au serveur : { host, jvms: [{ pid, name, agent }] }. Lève une Error avec le message serveur. */
  async listJvms() {
    return api('/api/jvms');
  }

  async attach(pid, name, profiles) {
    return api('/api/jvms/attach', { pid, name, profiles });
  }

  /** Crée ou remplace un profil ; lève une Error avec le message de validation du serveur. */
  async saveProfile(name, profile) {
    const d = await api(`/api/profiles/${encodeURIComponent(name)}`, profile, 'PUT');
    this.profiles = d.profiles;
  }

  async deleteProfile(name) {
    const d = await api(`/api/profiles/${encodeURIComponent(name)}`, undefined, 'DELETE');
    this.profiles = d.profiles;
  }

  async detach(id) {
    return api('/api/agents/detach', { id });
  }

  async importFile(file) {
    await fetch('/api/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: file });
  }
}

async function api(url, body, method) {
  const init = body === undefined ? { method: method || 'GET' } : { method: method || 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  const r = await fetch(url, init);
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `HTTP ${r.status}`);
  return data;
}

/** Paramètres de requête, sans les valeurs vides. */
export function qs(params) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) p.set(k, v === true ? '1' : String(v));
  return p.toString();
}

export const store = new Store();
