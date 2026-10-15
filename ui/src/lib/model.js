// Fonctions pures : regroupement des événements par requête, arbre d'appels, agrégats, détection N+1.

export const TYPES = ['SQL', 'LOAD', 'PROXY', 'COLLECTION', 'ACCESS', 'UTIL', 'SESSION', 'LAZY_ERROR'];

export const TYPE_LABEL = {
  SQL: 'SQL',
  LOAD: 'Load',
  PROXY: 'Proxy init',
  COLLECTION: 'Collection init',
  ACCESS: 'Accès',
  UTIL: 'Méthode',
  SESSION: 'Session',
  LAZY_ERROR: 'Lazy exception',
};

export const TYPE_SHORT = { SQL: 'SQL', LOAD: 'L', PROXY: 'P', COLLECTION: 'C', ACCESS: 'A', UTIL: 'M', SESSION: 'Ses', LAZY_ERROR: '!' };

export const SPAN_TYPES = new Set(['PROXY', 'COLLECTION', 'UTIL', 'SQL', 'SESSION']);

export function simple(fqcn) {
  if (!fqcn) return '';
  const i = fqcn.lastIndexOf('.');
  return i >= 0 ? fqcn.slice(i + 1) : fqcn;
}

/** com.acme.web.CustomerBean.load:42 -> CustomerBean.load:42 */
export function shortFrame(frame) {
  if (!frame) return '';
  if (frame.startsWith('[')) return frame;
  const m = frame.match(/^(.*)\.([^.]+?)(:\d+)?$/);
  if (!m) return frame;
  return `${simple(m[1])}.${m[2]}${m[3] || ''}`;
}

export function fmtMs(ms) {
  if (ms == null) return '';
  if (ms < 1) return `${ms.toFixed(2)} ms`;
  if (ms < 100) return `${ms.toFixed(1)} ms`;
  if (ms < 10000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

export function fmtTime(t) {
  const d = new Date(t);
  return d.toLocaleTimeString('fr-CH', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

export function fmtDate(t) {
  return new Date(t).toLocaleDateString('fr-CH', { weekday: 'short', day: '2-digit', month: '2-digit', year: 'numeric' });
}

/** Date et heure locales au format d'un <input type="datetime-local"> (à la ms). */
export function toLocalInput(t) {
  const d = new Date(t);
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

/** Couleur stable (teinte) d'un libellé : thread, requête... */
export function hueOf(text) {
  let h = 0;
  for (const c of String(text)) h = (h * 31 + c.charCodeAt(0)) | 0;
  return Math.abs(h) % 360;
}

/** Libellé court d'un événement pour l'arbre et les tables. */
export function subject(e) {
  switch (e.type) {
    case 'SQL':
      return e.info || '';
    case 'UTIL': {
      const call = `${e.label || `${simple(e.entity)}.${e.prop}`}${e.info || ''}`;
      // Cible absente des arguments (méthode d'instance : customer.refetch()) : on l'indique
      return e.tentity && !call.includes(`#${e.teid}`) ? `${simple(e.tentity)}#${e.teid} · ${call}` : call;
    }
    case 'SESSION':
      if (e.prop === 'query') return `query ${e.info || ''}`;
      return `session.${e.prop}${e.info || ''}`;
    case 'COLLECTION':
      return `${simple(e.entity)}.${e.prop}` + (e.eid != null ? ` #${e.eid}` : '');
    case 'ACCESS':
      return `${simple(e.entity)}.${e.prop}` + (e.eid != null ? ` #${e.eid}` : '');
    case 'PROXY':
    case 'LAZY_ERROR':
    case 'LOAD':
      return `${simple(e.entity)}${e.prop ? '.' + e.prop : ''}` + (e.eid != null ? ` #${e.eid}` : '');
    default:
      return e.info || '';
  }
}

/** Tags propres à l'événement : ceux de la sonde (tags) et ceux des règles du serveur (rtags). */
export function tagsOf(e) {
  if (!e.rtags) return e.tags || [];
  if (!e.tags) return e.rtags;
  return [...new Set([...e.tags, ...e.rtags])];
}

/** Tous les tags, y compris ceux hérités des spans englobants (scope). */
export function allTags(e) {
  const own = tagsOf(e);
  return e.scope ? [...new Set([...own, ...e.scope])] : own;
}

/**
 * Filtre texte des événements : les mots préfixés par # sont des tags (propres ou hérités),
 * le reste est cherché dans l'entité, la propriété, l'id, l'appelant, l'EL, le détail, l'erreur et la sonde.
 */
export function eventMatcher(text) {
  const words = (text || '').trim().toLowerCase().split(/\s+/).filter(Boolean);
  const tags = words.filter((w) => w.startsWith('#') && w.length > 1).map((w) => w.slice(1));
  const rest = words.filter((w) => !w.startsWith('#')).join(' ');
  if (!tags.length && !rest) return null;
  return (e) => {
    if (tags.length) {
      const t = allTags(e).map((x) => x.toLowerCase());
      if (!tags.every((x) => t.includes(x))) return false;
    }
    return !rest || `${e.entity} ${e.prop} ${e.eid} ${e.caller} ${e.trigger} ${e.el} ${e.info} ${e.error} ${e.label} ${e.probe}`.toLowerCase().includes(rest);
  };
}

/** SQL normalisé pour regrouper les requêtes identiques aux paramètres près. */
export function normalizeSql(sql) {
  return (sql || '')
    .replace(/'(?:[^']|'')*'/g, '?')
    .replace(/\b\d+(\.\d+)?\b/g, '?')
    .replace(/\s+/g, ' ')
    .replace(/in\s*\((\s*\?\s*,?)+\)/gi, 'in (…)')
    .trim();
}

export function reqKey(e) {
  return e.req ? `${e.agent}|${e.req}` : `${e.agent}|bg|${e.thread || '?'}`;
}

/** Résumé d'une requête (construit par le serveur, sans les événements). */
export function newRequest(e) {
  return {
    key: reqKey(e),
    agent: e.agent,
    id: e.req,
    background: !e.req,
    method: e.req ? null : 'BG',
    uri: e.req ? null : `hors requête · ${e.thread || '?'}`,
    t: e.t,
    lastT: e.t,
    dur: null,
    status: null,
    source: null,
    error: null,
    done: !e.req,
    n: 0,
    counts: {},
    sqlTime: 0,
    initTime: 0,
    nplus: 0,
  };
}

/** Met à jour le résumé ; false pour REQ_START / REQ_END, qui ne sont pas des événements de la requête. */
export function applySummary(r, e) {
  if (e.type === 'REQ_START') {
    r.method = e.prop;
    r.uri = e.info;
    r.t = e.t;
    r.thread = e.thread;
    return false;
  }
  if (e.type === 'REQ_END') {
    r.method = e.prop;
    r.uri = e.info;
    r.t = e.t;
    r.dur = e.dur;
    r.status = e.state;
    r.source = e.el;
    r.error = e.error;
    r.done = true;
    return false;
  }
  r.n++;
  r.counts[e.type] = (r.counts[e.type] || 0) + 1;
  if (e.t > r.lastT) r.lastT = e.t;
  if (e.t < r.t) r.t = e.t;
  if (e.type === 'SQL' && e.dur) r.sqlTime += e.dur;
  if ((e.type === 'PROXY' || e.type === 'COLLECTION') && e.dur && !e.parent) r.initTime += e.dur;
  return true;
}

/**
 * Arbre d'appels. Les spans (PROXY, COLLECTION, UTIL, SQL) ont des enfants ; les LOAD / ACCESS consécutifs
 * identiques sont regroupés pour rester lisibles.
 */
export function buildTree(events, { showAccess = true, showLoad = true } = {}) {
  const byId = new Map();
  for (const e of events) byId.set(e.id, { ev: e, children: [] });
  const roots = [];
  for (const node of byId.values()) {
    const e = node.ev;
    if (!showAccess && e.type === 'ACCESS') continue;
    if (!showLoad && e.type === 'LOAD') continue;
    const parent = e.parent && byId.get(e.parent);
    (parent ? parent.children : roots).push(node);
  }
  const finish = (list) => {
    list.sort((a, b) => a.ev.t - b.ev.t || cmpId(a.ev.id, b.ev.id));
    const out = [];
    for (const n of list) {
      if (n.children.length) n.children = finish(n.children);
      const e = n.ev;
      const prev = out[out.length - 1];
      if ((e.type === 'LOAD' || e.type === 'ACCESS') && !n.children.length) {
        const key = `${e.type}|${e.entity}|${e.type === 'ACCESS' ? e.prop : ''}|${e.caller}|${e.el}`;
        if (prev && prev.group && prev.key === key) {
          prev.items.push(e);
          continue;
        }
        if (prev && !prev.group && !prev.children.length && prev.key === key) {
          out[out.length - 1] = { group: true, key, items: [prev.ev, e], ev: prev.ev };
          continue;
        }
        n.key = key;
      }
      out.push(n);
    }
    return out;
  };
  return finish(roots);
}

function cmpId(a, b) {
  const na = Number(String(a).split('-').pop());
  const nb = Number(String(b).split('-').pop());
  return na - nb;
}

function bump(map, key, init) {
  let v = map.get(key);
  if (!v) {
    v = init();
    map.set(key, v);
  }
  return v;
}

/** Synthèse par entité. */
export function byEntity(events) {
  const m = new Map();
  for (const e of events) {
    if (!e.entity || e.type === 'UTIL' || e.type === 'SQL') continue;
    const r = bump(m, e.entity, () => ({ entity: e.entity, LOAD: 0, lazyLoad: 0, wantedLoad: 0, PROXY: 0, COLLECTION: 0, ACCESS: 0, SESSION: 0, LAZY_ERROR: 0, lazyAccess: 0, time: 0, ids: new Set() }));
    r[e.type] = (r[e.type] || 0) + 1;
    if (e.type === 'LOAD') {
      const c = causeOf(e);
      if (c?.kind === 'lazy') r.lazyLoad++;
      else if (c?.wanted) r.wantedLoad++;
    }
    if (e.eid != null) r.ids.add(e.eid);
    if (e.type === 'ACCESS' && e.state) r.lazyAccess++;
    if ((e.type === 'PROXY' || e.type === 'COLLECTION') && e.dur) r.time += e.dur;
  }
  return [...m.values()].sort((a, b) => b.PROXY + b.COLLECTION + b.LAZY_ERROR - (a.PROXY + a.COLLECTION + a.LAZY_ERROR) || b.LOAD - a.LOAD);
}

/** Synthèse par propriété (accès, collections, proxies déclenchés par un getter). */
export function byProperty(events) {
  const m = new Map();
  for (const e of events) {
    if (!e.prop || !['ACCESS', 'COLLECTION', 'PROXY', 'LAZY_ERROR'].includes(e.type)) continue;
    const key = `${e.entity}.${e.prop}`;
    const r = bump(m, key, () => ({ key, entity: e.entity, prop: e.prop, ACCESS: 0, COLLECTION: 0, PROXY: 0, LAZY_ERROR: 0, lazy: 0, callers: new Set(), time: 0 }));
    r[e.type]++;
    if (e.type === 'ACCESS' && e.state) r.lazy++;
    if (e.caller) r.callers.add(e.caller);
    if (e.dur && e.type !== 'ACCESS') r.time += e.dur;
  }
  return [...m.values()].sort((a, b) => b.COLLECTION + b.PROXY - (a.COLLECTION + a.PROXY) || b.ACCESS - a.ACCESS);
}

/** Synthèse par appelant métier. */
export function byCaller(events) {
  const m = new Map();
  for (const e of events) {
    const key = e.caller || '(inconnu)';
    const r = bump(m, key, () => ({ caller: key, SQL: 0, LOAD: 0, PROXY: 0, COLLECTION: 0, ACCESS: 0, UTIL: 0, LAZY_ERROR: 0, time: 0, triggers: new Set(), els: new Set() }));
    r[e.type] = (r[e.type] || 0) + 1;
    if (e.trigger) r.triggers.add(e.trigger);
    if (e.el) r.els.add(e.el);
    if (e.dur && (e.type === 'SQL' || ((e.type === 'PROXY' || e.type === 'COLLECTION') && !e.parent))) r.time += e.dur;
  }
  return [...m.values()].sort((a, b) => b.SQL + b.PROXY + b.COLLECTION - (a.SQL + a.PROXY + a.COLLECTION));
}

export function bySql(events) {
  const m = new Map();
  for (const e of events) {
    if (e.type !== 'SQL') continue;
    const key = normalizeSql(e.info);
    const r = bump(m, key, () => ({ sql: key, count: 0, time: 0, max: 0, callers: new Set(), sample: e }));
    r.count++;
    r.time += e.dur || 0;
    r.max = Math.max(r.max, e.dur || 0);
    if (e.caller) r.callers.add(e.caller);
  }
  return [...m.values()].sort((a, b) => b.count - a.count || b.time - a.time);
}

/**
 * N+1 probables : le même appelant (ou la même expression EL) déclenche N fois l'initialisation
 * du même type de relation, ou N fois la même requête SQL.
 */
export function detectNPlusOne(events, threshold = 3) {
  const m = new Map();
  for (const e of events) {
    let what;
    if (e.type === 'PROXY') what = `${simple(e.entity)} (proxy)`;
    else if (e.type === 'COLLECTION') what = `${simple(e.entity)}.${e.prop}`;
    else if (e.type === 'SQL') what = normalizeSql(e.info);
    else continue;
    if (e.type === 'SQL' && e.parent) continue; // déjà compté via son PROXY / COLLECTION parent
    const where = e.el ? `${e.el}  ←  ${e.caller || ''}` : e.caller || '(inconnu)';
    const key = `${e.type}|${what}|${where}`;
    const r = bump(m, key, () => ({ key, type: e.type, what, el: e.el, caller: e.caller, trigger: e.trigger, count: 0, time: 0, ids: [], sample: e }));
    r.count++;
    r.time += e.dur || 0;
    if (e.eid != null && r.ids.length < 50) r.ids.push(e.eid);
  }
  return [...m.values()].filter((r) => r.count >= threshold).sort((a, b) => b.count - a.count);
}

/** Entité et id portés par un événement (cible pour UTIL). */
export function entityOf(e) {
  if (e.type === 'UTIL') return e.tentity ? { entity: e.tentity, eid: e.teid } : null;
  return e.entity && e.eid != null && e.type !== 'SQL' ? { entity: e.entity, eid: e.eid } : null;
}

function matchesRef(e, name, eid) {
  const r = entityOf(e);
  if (!r || simple(r.entity) !== name) return false;
  return r.eid === eid || (e.type === 'UTIL' && String(r.eid).split(',').includes(eid));
}

const real = (inst) => inst && !inst.startsWith('p');

/**
 * Historique d'une entité (toutes requêtes confondues) avec l'explication des changements d'état.
 * `items` : [{ e, req }]. Les instances (identité JVM) et sessions sont numérotées dans l'ordre d'apparition.
 * Une entité "initialisée" qui redevient lazy est en pratique une autre instance (findById, merge, rechargement
 * dans une autre session), une instance rafraîchie, ou une instance détachée (clear / evict / close).
 */
export function entityHistory(items, entity, eid) {
  const name = simple(entity);
  eid = String(eid);
  const insts = new Set();
  const sessions = new Set();
  for (const { e } of items) {
    if (!matchesRef(e, name, eid)) continue;
    if (e.inst) insts.add(e.inst);
    if (e.rinst) insts.add(e.rinst);
    if (e.sess) sessions.add(e.sess);
  }
  // + événements sur une instance connue (sans id exploitable), et clear / close des sessions concernées
  const rows = items.filter(
    ({ e }) =>
      matchesRef(e, name, eid) ||
      (e.inst && insts.has(e.inst) && e.type !== 'UTIL' && e.type !== 'SQL') ||
      (e.type === 'SESSION' && (e.prop === 'clear' || e.prop === 'close') && sessions.has(e.sess))
  );
  rows.sort((a, b) => a.e.t - b.e.t || cmpId(a.e.id, b.e.id));

  const instLabel = new Map();
  const sessLabel = new Map();
  const labelOf = (map, key, prefix) => {
    if (!key) return null;
    if (!map.has(key)) map.set(key, `${prefix}${map.size + 1}`);
    return map.get(key);
  };
  const L = (inst) => (inst ? labelOf(instLabel, inst, 'i') : '?');
  const instSess = new Map(); // instance -> session où elle a été vue
  const initialized = new Map(); // propriété -> { inst, row }
  const detached = []; // rows SESSION qui ont détaché l'entité
  const findings = [];
  let current = null; // dernière instance réelle vue

  const out = rows.map((row) => {
    const { e } = row;
    const notes = [];
    const i = labelOf(instLabel, e.inst, 'i');
    const s = labelOf(sessLabel, e.sess, 'S');
    if (real(e.inst) && e.sess) instSess.set(e.inst, e.sess);

    if (real(e.inst) && current && current !== e.inst && e.type !== 'SESSION') {
      const how = e.type === 'LOAD' ? `chargée${s ? ` dans ${s}` : ''}` : `vue en ${e.type}`;
      notes.push(`Nouvelle instance ${i} (${how}) : ${L(current)} n'est plus l'objet utilisé`);
    }
    if (e.rinst) {
      const r = L(e.rinst);
      const what = e.type === 'UTIL' ? `${simple(e.entity)}.${e.prop}` : `session.${e.prop}`;
      notes.push(`Renvoie une autre instance (${r}) que celle passée (${i || '?'}) : ce qui était initialisé sur ${i || "l'ancienne"} ne la suit pas`);
      findings.push({ row, text: `${what} remplace ${i || '?'} par ${r}` });
    }

    if (e.type === 'COLLECTION') {
      const prev = initialized.get(e.prop);
      if (prev && prev.inst !== e.inst)
        notes.push(`Déjà initialisée sur ${L(prev.inst)} (${fmtTime(prev.row.e.t)}) : rechargée car ${i || '?'} est une autre instance`);
      if (e.inst) initialized.set(e.prop, { inst: e.inst, row });
    }

    if (e.type === 'ACCESS' && e.state) {
      const prev = initialized.get(e.prop);
      if (prev && prev.inst === e.inst) {
        notes.push(`${e.prop} était initialisée sur cette même instance (${fmtTime(prev.row.e.t)}) : la valeur a été remplacée (setter, merge…)`);
        findings.push({ row, text: `${e.prop} redevenue lazy sur la même instance ${i}` });
      } else if (prev) {
        notes.push(`${e.prop} a été initialisée sur ${L(prev.inst)} (${fmtTime(prev.row.e.t)}), pas sur ${i || 'cette instance'}`);
        findings.push({ row, text: `${e.prop} initialisée sur ${L(prev.inst)}, lue non initialisée sur ${i}` });
      }
    }

    if (e.type === 'SESSION') {
      if (e.prop === 'evict' || e.prop === 'detach') {
        detached.push(row);
        notes.push(`Détache ${i || "l'entité"} de ${s || 'la session'} : tout chargement lazy ultérieur sur cette instance échouera`);
      } else if (e.prop === 'clear' || e.prop === 'close') {
        const victims = [...instSess].filter(([, ss]) => ss === e.sess).map(([inst]) => L(inst));
        detached.push(row);
        notes.push(`Détache toutes les entités de ${s}${victims.length ? ` (${victims.join(', ')})` : ''}`);
      } else if (['persist', 'save', 'saveOrUpdate'].includes(e.prop)) {
        notes.push(`Rend ${i || "l'entité"} managed dans ${s || 'la session'} (insérée au flush si nouvelle)`);
      } else if (e.prop === 'update' || e.prop === 'lock') {
        notes.push(`Réattache ${i || "l'entité"} à ${s || 'la session'}`);
      } else if (e.prop === 'remove' || e.prop === 'delete') {
        notes.push(`Marque ${i || "l'entité"} pour suppression (supprimée au flush)`);
      } else if (e.prop === 'getReference' || e.prop === 'load') {
        if (e.snap?.[0] === '@|P|') notes.push('Référence obtenue sans chargement : proxy non initialisé');
      } else if (e.prop === 'refresh') {
        notes.push(`Relit ${i || "l'entité"} depuis la base : ses collections redeviennent non initialisées`);
        findings.push({ row, text: `session.refresh sur ${i || "l'entité"}` });
        for (const [prop, v] of initialized) if (v.inst === e.inst) initialized.delete(prop);
      }
    }

    if (e.type === 'LAZY_ERROR' || (e.type === 'PROXY' && e.error)) {
      const cause = detached[detached.length - 1];
      notes.push(
        cause
          ? `Probablement détachée par session.${cause.e.prop} (${fmtTime(cause.e.t)}, ${shortFrame(cause.e.caller)})`
          : 'Instance détachée : session fermée, ou entité chargée dans une requête précédente'
      );
      findings.push({ row, text: `LazyInitializationException${e.prop ? ' sur ' + e.prop : ''}${cause ? ' après session.' + cause.e.prop : ''}` });
    }

    if (real(e.inst) && e.type !== 'SESSION') current = e.inst;
    return { ...row, i, s, r: e.rinst ? L(e.rinst) : null, notes };
  });

  // Requêtes / find : la cause des LOAD est affichée dans le chemin de vie, on signale les lazy implicites
  const lazyLoads = out.filter((r) => r.e.type === 'LOAD' && causeOf(r.e)?.kind === 'lazy');
  if (lazyLoads.length)
    findings.push({ row: lazyLoads[0], text: `${lazyLoads.length} chargement(s) lazy implicite(s) (${causeOf(lazyLoads[0].e).detail})` });

  return {
    rows: out,
    findings,
    instances: instLabel.size,
    sessions: sessLabel.size,
    sessLabel: (sess) => labelOf(sessLabel, sess, 'S'),
  };
}

// ------------------------------------------------------------------ cause des chargements

/** Genres de cause (champ `cause` des LOAD / PROXY / COLLECTION). `wanted` : chargement voulu par le code. */
export const CAUSES = {
  lazy: { label: 'Lazy implicite', help: 'Chargement lazy déclenché par un getter ou une itération, sans demande explicite', wanted: false, cls: 't-PROXY' },
  init: { label: 'Init. demandée', help: 'Initialisation lazy demandée explicitement (Hibernate.initialize…)', wanted: true, cls: 't-COLLECTION' },
  explicit: { label: 'Load voulu', help: 'Chargement demandé : find, load, refresh, merge…', wanted: true, cls: 't-UTIL' },
  query: { label: 'Requête', help: 'Hydraté par une requête HQL / JPQL / Criteria / native', wanted: true, cls: 't-SQL' },
  other: { label: 'Autre', help: 'Aucune opération instrumentée englobante (fetch eager, chemin non instrumenté…)', wanted: null, cls: '' },
};

export function causeOf(e) {
  if (!e?.cause) return null;
  const i = e.cause.indexOf('|');
  const kind = i < 0 ? e.cause : e.cause.slice(0, i);
  return { kind, detail: i < 0 ? '' : e.cause.slice(i + 1), ...(CAUSES[kind] || CAUSES.other) };
}

// ------------------------------------------------------------------ instantanés d'état

/**
 * Instantané envoyé par l'agent (`snap`, `rsnap`) : liste de "nom|genre|état|détail".
 * Retourne { att: { kind: 'S'|'D'|'P', sess }, props: Map(nom -> { kind: 'C'|'R'|'N', state: 'I'|'L'|'', detail, key }) }.
 */
export function parseSnap(list) {
  if (!list) return null;
  const out = { att: null, props: new Map() };
  for (const s of list) {
    const [name, kind, state = '', ...rest] = s.split('|');
    const detail = rest.join('|');
    if (name === '@') out.att = { kind, sess: state || null };
    else out.props.set(name, { kind, state, detail, key: `${kind}${state}${detail}` });
  }
  return out;
}

/** Libellé court d'une cellule d'état. */
export function cellText(c) {
  if (!c) return '';
  if (c.kind === 'N') return 'null';
  if (c.kind === 'C') return c.state === 'I' ? `chargée${c.detail !== '' ? ` (${c.detail})` : ''}` : 'lazy';
  return c.state === 'I' ? c.detail : `proxy ${c.detail}`;
}

/**
 * Chemin de vie d'une entité : une étape par opération, avec l'état de l'instance après l'opération.
 * Les colonnes sont les associations vues dans les instantanés ; `changed` marque ce qui a bougé depuis
 * l'étape précédente de la même instance.
 */
export function lifecycle(hist, { allAccess = false, allColumns = false } = {}) {
  const steps = [];
  for (const row of hist.rows) {
    const e = row.e;
    if (e.type === 'ACCESS' && !e.state && !allAccess && !row.notes.length) continue;
    steps.push({ ...row, snap: parseSnap(e.snap), mark: markOf(e) });
    if (e.rinst) steps.push({ ...row, i: row.r, r: null, returned: true, snap: parseSnap(e.rsnap), mark: null, notes: [] });
  }
  const cols = new Map(); // nom -> { keys: Set, marked, loaded }
  const prevByInst = new Map();
  for (const st of steps) {
    st.changed = new Set();
    if (st.mark) bumpCol(cols, st.mark.prop).marked = true;
    if (!st.snap) continue;
    const prev = prevByInst.get(st.i);
    for (const [name, c] of st.snap.props) {
      const col = bumpCol(cols, name);
      col.keys.add(c.key);
      if (c.state === 'I' && c.kind === 'C') col.loaded = true;
      if (prev && prev.props.get(name)?.key !== c.key) st.changed.add(name);
    }
    st.attChanged = !!prev && (prev.att?.kind !== st.snap.att?.kind || prev.att?.sess !== st.snap.att?.sess);
    prevByInst.set(st.i, st.snap);
  }
  const all = [...cols.keys()];
  const relevant = all.filter((n) => {
    const c = cols.get(n);
    return c.marked || c.keys.size > 1 || c.loaded;
  });
  return { steps, columns: allColumns || !relevant.length ? all : relevant, hidden: all.length - relevant.length };
}

function bumpCol(cols, name) {
  let c = cols.get(name);
  if (!c) cols.set(name, (c = { keys: new Set(), marked: false, loaded: false }));
  return c;
}

/** Propriété visée par un événement sans instantané (accès lazy, exception, initialisation). */
function markOf(e) {
  if (!e.prop) return null;
  if (e.type === 'ACCESS' && e.state) return { prop: e.prop, kind: 'lazyread', text: 'lue non initialisée' };
  if (e.type === 'LAZY_ERROR') return { prop: e.prop, kind: 'error', text: 'exception' };
  if (e.type === 'ACCESS') return { prop: e.prop, kind: 'read', text: 'lue' };
  if (e.type === 'COLLECTION') return { prop: e.prop, kind: 'init', text: 'initialisée' };
  return null;
}

/** Libellé de l'opération d'une étape du chemin de vie. */
export function operation(e) {
  switch (e.type) {
    case 'LOAD':
      return 'Chargement';
    case 'COLLECTION':
      return `Init. collection ${e.prop}`;
    case 'PROXY':
      return `Init. proxy${e.prop ? ` (via ${e.prop})` : ''}`;
    case 'UTIL':
      return `${simple(e.entity)}.${e.prop}`;
    case 'SESSION':
      return e.prop === 'query' ? 'Requête' : `session.${e.prop}`;
    case 'ACCESS':
      return `get ${e.prop}`;
    case 'LAZY_ERROR':
      return 'LazyInitializationException';
    default:
      return e.type;
  }
}

// ------------------------------------------------------------------ origine d'un état

/** Opérations qui changent l'attachement d'une instance à une session. */
const ATTACHING = new Set(['persist', 'save', 'saveOrUpdate', 'update', 'lock', 'merge', 'find', 'get', 'getReference', 'load', 'refresh']);
const DETACHING = new Set(['evict', 'detach', 'clear', 'close']);

/**
 * D'où vient l'état de l'instance à l'étape k du chemin de vie (lifecycle) : pour l'attachement (managée,
 * détachée, proxy) et pour chaque association, l'étape qui l'a établi, toutes requêtes et threads confondus.
 * `established` : l'étape a produit ce changement ; sinon l'état est seulement constaté (premier instantané reçu,
 * l'opération qui l'a produit n'a pas été capturée). `trail` : les changements de cette instance jusqu'à k.
 */
export function stateOrigins(steps, k) {
  const cur = steps[k];
  if (!cur?.i) return null;
  const out = { inst: null, att: null, props: new Map(), trail: [] };
  let prev = null;
  let sess = null; // session où l'instance est managée
  for (let j = 0; j <= k; j++) {
    const s = steps[j];
    // clear / close de sa session, éventuellement depuis un autre thread : l'instance est détachée
    if (s.i !== cur.i && sess && s.e.type === 'SESSION' && (s.e.prop === 'clear' || s.e.prop === 'close') && s.e.sess === sess && (!s.i || j === k)) {
      out.att = { step: s, text: 'détachée', established: true };
      out.trail.push({ step: s, what: [`détachée (session.${s.e.prop})`], current: j === k });
      sess = null;
      continue;
    }
    if (s.i !== cur.i) continue;
    if (!out.inst) out.inst = { step: s, established: s.e.type === 'LOAD' || s.returned || ATTACHING.has(s.e.prop) };
    const what = [];
    if (s.e.type === 'SESSION' && DETACHING.has(s.e.prop) && !s.snap) {
      out.att = { step: s, text: 'détachée', established: true };
      what.push('détachée');
      sess = null;
    }
    if (s.snap) {
      const a = s.snap.att;
      sess = a?.kind === 'S' ? a.sess || s.e.sess || sess : null;
      if (!prev || prev.att?.kind !== a?.kind || prev.att?.sess !== a?.sess) {
        const text = !a ? 'inconnu' : a.kind === 'S' ? 'managée' : a.kind === 'P' ? 'proxy non initialisé' : 'détachée';
        out.att = { step: s, text, established: !!prev || s.e.type === 'LOAD' || s.e.type === 'SESSION' || s.returned };
        what.push(text);
      }
      for (const [name, c] of s.snap.props) {
        const before = prev?.props.get(name);
        if (before?.key === c.key) continue;
        const established = !!before || (s.mark?.prop === name && s.mark.kind === 'init') || s.e.type === 'LOAD';
        out.props.set(name, { step: s, cell: c, established });
        if (before) what.push(`${name} : ${cellText(before)} → ${cellText(c)}`);
      }
      prev = s.snap;
    } else if (s.mark?.kind === 'init') {
      out.props.set(s.mark.prop, { step: s, cell: { kind: 'C', state: 'I', detail: '' }, established: true });
      what.push(`${s.mark.prop} initialisée`);
    }
    if (what.length || s.notes.length || j === k) out.trail.push({ step: s, what, current: j === k });
  }
  return out;
}
