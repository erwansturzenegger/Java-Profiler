// Profils de profilage : server/profiles/<nom>.json. Un profil regroupe des sondes (quoi instrumenter),
// des réglages (packages applicatifs...) et des règles de tags. L'agent ne connaît aucune classe : il reçoit
// la spécification calculée ici pour lui, et la réinstalle à chaud quand elle change.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

/** Handlers disponibles : code injecté côté agent et interprétation de l'appel. */
export const HANDLERS = {
  method: { type: 'UTIL', help: 'Méthode quelconque : durée, arguments, entité ciblée (this ou 1er argument entité) et son état après. role "load" : rend « voulus » les chargements déclenchés.' },
  'proxy-init': { type: 'PROXY', help: "Initialisation d'un proxy lazy (Hibernate AbstractLazyInitializer#initialize)." },
  'collection-init': { type: 'COLLECTION', help: "Initialisation d'une collection lazy (listener d'InitializeCollectionEvent)." },
  'post-load': { type: 'LOAD', help: 'Entité hydratée (listener de PostLoadEvent : getEntity, getId, getSession).' },
  session: { type: 'SESSION', help: 'Opérations de session : find, get, refresh, merge, evict, detach, clear, close.' },
  query: { type: 'SESSION', help: "Exécution d'une requête (list()) : cause « requête » des LOAD hydratés." },
  'lazy-collection': { type: 'LAZY_ERROR', help: 'Collection lazy en échec : identifie l’entité de la LazyInitializationException qui suit.' },
  'lazy-exception': { type: 'LAZY_ERROR', help: "Constructeur d'une exception d'accès lazy (methods: [\"<init>\"])." },
  sql: { type: 'SQL', help: 'Exécution SQL : le premier argument String est le SQL, sinon le statement.' },
  'http-request': { type: 'REQ_START', help: 'Requête HTTP : service(request, response). Regroupe les événements par requête.' },
  el: { type: '—', help: 'Expression EL en cours d’évaluation (contexte des événements).' },
  getter: { type: 'ACCESS', help: 'Getters des entités (get*/is* sans argument).' },
};

const NAME = /^[\w.-]+$/;

export function globToRegex(glob) {
  return new RegExp('^' + glob.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$');
}

const regexCache = new Map();

function globMatch(globs, value) {
  if (value == null) return false;
  return [].concat(globs).some((g) => {
    let re = regexCache.get(g);
    if (!re) regexCache.set(g, (re = globToRegex(g)));
    return re.test(value);
  });
}

export function validateProfile(p) {
  const errors = [];
  if (!p || typeof p !== 'object') return ['profil invalide'];
  const ids = new Set();
  for (const [i, pr] of (p.probes || []).entries()) {
    const where = `sonde ${pr.id || '#' + (i + 1)}`;
    if (!pr.id) errors.push(`${where} : id manquant`);
    else if (ids.has(pr.id)) errors.push(`${where} : id en double`);
    ids.add(pr.id);
    if (pr.handler && !HANDLERS[pr.handler]) errors.push(`${where} : handler inconnu « ${pr.handler} »`);
    const has = (k) => (Array.isArray(pr[k]) ? pr[k].length : pr[k]);
    if (!has('class') && !has('extends') && !has('annotated')) errors.push(`${where} : class, extends ou annotated requis`);
    if (pr.role && !['load', 'measure'].includes(pr.role)) errors.push(`${where} : role "load" ou "measure"`);
  }
  for (const [i, r] of (p.tagRules || []).entries()) if (!r.tag) errors.push(`règle de tag #${i + 1} : tag manquant`);
  for (const [i, r] of (p.settings?.entityIds || []).entries()) {
    const has = (k) => (Array.isArray(r[k]) ? r[k].length : !!r[k]);
    if (!has('class') && !has('extends')) errors.push(`identifiant #${i + 1} : class ou extends requis`);
    if (!r.id || !/^[\w$]+(\(\))?$/.test(String(r.id).trim())) errors.push(`identifiant #${i + 1} : id = nom de champ ou méthode() requis`);
  }
  return errors;
}

export class Profiles {
  constructor(dir, onChange) {
    this.dir = dir;
    this.onChange = onChange;
    this.map = new Map();
    this.ownWrite = 0;
    fs.mkdirSync(dir, { recursive: true });
    this.load();
    try {
      let timer;
      fs.watch(dir, () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
          if (Date.now() - this.ownWrite < 1000) return; // nos propres écritures (UI) sont déjà prises en compte
          this.load();
          this.onChange();
        }, 300);
      });
    } catch (e) {
      console.warn('profiles: watch unavailable:', e.message);
    }
  }

  load() {
    const map = new Map();
    for (const f of fs.readdirSync(this.dir).filter((f) => f.endsWith('.json')).sort()) {
      const name = f.slice(0, -5);
      try {
        const p = JSON.parse(fs.readFileSync(path.join(this.dir, f), 'utf8'));
        const errors = validateProfile(p);
        map.set(name, { ...p, name, errors });
      } catch (e) {
        map.set(name, { name, probes: [], errors: [`JSON invalide : ${e.message}`] });
      }
    }
    this.map = map;
  }

  list() {
    return [...this.map.values()];
  }

  save(name, profile) {
    if (!NAME.test(name)) throw new Error('nom de profil invalide (lettres, chiffres, . _ -)');
    const errors = validateProfile(profile);
    if (errors.length) throw new Error(errors.join(' ; '));
    const { name: _n, errors: _e, ...clean } = profile;
    this.ownWrite = Date.now();
    fs.writeFileSync(path.join(this.dir, `${name}.json`), JSON.stringify(clean, null, 2) + '\n');
    this.load();
  }

  remove(name) {
    if (!NAME.test(name) || !this.map.has(name)) throw new Error('profil inconnu');
    this.ownWrite = Date.now();
    fs.unlinkSync(path.join(this.dir, `${name}.json`));
    this.load();
  }

  /** Profils appliqués à un agent : ceux qu'il demande (profiles=...), sinon les actifs dont appliesTo lui correspond. */
  forAgent(agent) {
    if (agent?.profiles?.length) return agent.profiles.map((n) => this.map.get(n)).filter(Boolean);
    return this.list().filter((p) => p.enabled !== false && globMatch(p.appliesTo?.length ? p.appliesTo : ['*'], agent?.name || agent?.id || ''));
  }

  /** Spécification envoyée à l'agent : sondes actives des profils, identifiées "profil/sonde". */
  specFor(agent) {
    const probes = [];
    for (const p of this.forAgent(agent)) {
      if (p.errors?.length) continue;
      for (const pr of p.probes || []) {
        if (pr.enabled === false) continue;
        const { enabled: _e, description: _d, ...rest } = pr;
        probes.push({ ...rest, handler: pr.handler || 'method', id: `${p.name}/${pr.id}` });
      }
    }
    const version = crypto.createHash('sha1').update(JSON.stringify(probes)).digest('hex').slice(0, 12);
    return { version, probes };
  }

  /** Réglages des profils, ajoutés à ceux de la configuration (listes fusionnées). */
  settingsFor(agent, config) {
    const out = {};
    for (const k of ['callerPackages', 'callerExclude', 'accessExclude', 'libraryPackages']) {
      const all = new Set(config[k] || []);
      for (const p of this.forAgent(agent)) for (const v of p.settings?.[k] || []) all.add(v);
      out[k] = [...all];
    }
    // Lecture de l'id des entités : règles des profils, dans l'ordre (la première qui correspond l'emporte)
    out.entityIds = this.forAgent(agent).flatMap((p) => p.settings?.entityIds || []);
    return out;
  }

  rulesFor(agent) {
    return this.forAgent(agent).flatMap((p) => (p.tagRules || []).filter((r) => r.tag && r.enabled !== false));
  }
}

const simpleName = (s) => (s ? s.slice(s.lastIndexOf('.') + 1) : s);

function ruleMatches(r, e) {
  if (r.types?.length && !r.types.includes(e.type)) return false;
  if (r.entity) {
    const ent = e.tentity || e.entity;
    if (!globMatch(r.entity, ent) && !globMatch(r.entity, simpleName(ent))) return false;
  }
  if (r.prop && !globMatch(r.prop, e.prop)) return false;
  if (r.caller && !globMatch(r.caller, e.caller)) return false;
  if (r.probe && !globMatch(r.probe, e.probe)) return false;
  if (r.text && !String(e.info || '').toLowerCase().includes(String(r.text).toLowerCase())) return false;
  if (r.minDur != null && !(e.dur >= r.minDur)) return false;
  if (r.error === true && !e.error) return false;
  if (r.cause && !String(e.cause || '').startsWith(r.cause + '|')) return false;
  return true;
}

/** Tags des règles (rtags), recalculés sur l'événement. */
export function applyRules(rules, e) {
  const tags = [];
  for (const r of rules) if (!tags.includes(r.tag) && ruleMatches(r, e)) tags.push(r.tag);
  if (tags.length) e.rtags = tags;
  else delete e.rtags;
}
