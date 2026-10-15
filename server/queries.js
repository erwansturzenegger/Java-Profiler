// Lectures de la base, exécutées dans les workers (reader.js). Les gros résultats sont renvoyés en texte JSON
// assemblé à partir des colonnes data, sans reparser les événements.

import { simple, detectNPlusOne, SPAN_TYPES } from '../ui/src/lib/model.js';

const SPANS = [...SPAN_TYPES].map((t) => `'${t}'`).join(',');
const statements = new Map();

function st(db, sql) {
  let s = statements.get(sql);
  if (!s) statements.set(sql, (s = db.prepare(sql)));
  return s;
}

/** Lecture cohérente : toutes les requêtes de fn voient le même état de la base. */
function snapshot(db, fn) {
  db.exec('BEGIN');
  try {
    return fn();
  } finally {
    db.exec('COMMIT');
  }
}

const list = (rows) => `[${rows.map((r) => r.data).join(',')}]`;

/**
 * Corps d'un événement sans sa pile (souvent l'essentiel du volume : jusqu'à stackDepth frames) ; stackN = nombre de
 * frames, la pile est chargée à la demande (op event). Évite des réponses de centaines de Mo.
 */
const LIGHT = `CASE WHEN instr(b.data, '"stack":') = 0 THEN b.data
  ELSE json_set(json_remove(b.data, '$.stack'), '$.stackN', json_array_length(b.data, '$.stack')) END AS data`;

/** Taille maximale d'une réponse (caractères) : au-delà, le résultat est tronqué. */
const MAX_CHARS = 64 * 1024 * 1024;

/** Liste des requêtes, les plus récentes d'abord. */
export function requests(db, { q = '', hideEmpty = true, problems = false, limit = 300, offset = 0 }) {
  const where = [];
  const args = [];
  if (hideEmpty) where.push('n > 0');
  if (problems) where.push('(nplus > 0 OR lazy > 0)');
  if (q) {
    where.push("txt LIKE ? ESCAPE '\\'");
    args.push(`%${q.replace(/[\\%_]/g, '\\$&')}%`);
  }
  const w = where.length ? `WHERE ${where.join(' AND ')}` : '';
  return snapshot(db, () => {
    const rows = st(db, `SELECT data FROM requests ${w} ORDER BY t DESC LIMIT ? OFFSET ?`).all(...args, limit, offset);
    const total = st(db, `SELECT count(*) n FROM requests ${w}`).get(...args).n;
    // Dernière requête terminée (mode suivi), sinon la plus récente
    const latest =
      st(db, "SELECT key FROM requests WHERE n > 0 AND done = 1 AND instr(key, '|bg|') = 0 ORDER BY t DESC LIMIT 1").get() ||
      st(db, 'SELECT key FROM requests ORDER BY t DESC LIMIT 1').get();
    return `{"total":${total},"latest":${JSON.stringify(latest?.key ?? null)},"items":${list(rows)}}`;
  });
}

/** Une requête et ses événements (au plus limit, dans l'ordre de réception). */
export function request(db, { key, limit = 100_000 }) {
  return snapshot(db, () => {
    const req = st(db, 'SELECT data FROM requests WHERE key = ?').get(key);
    if (!req) return null;
    const rows = [];
    let size = 0;
    let truncated = false;
    for (const r of st(db, `SELECT ${LIGHT} FROM events e JOIN bodies b ON b.seq = e.seq WHERE e.rkey = ? AND e.type NOT IN ('REQ_START', 'REQ_END') ORDER BY e.seq`).iterate(key)) {
      if (rows.length >= limit || size + r.data.length > MAX_CHARS) {
        truncated = true;
        break;
      }
      size += r.data.length + 1;
      rows.push(r);
    }
    return `{"req":${req.data},"truncated":${truncated},"events":${list(rows)}}`;
  });
}

/** Un événement complet (avec sa pile), retrouvé par sa requête et son id. */
export function event(db, { key, id }) {
  const r = st(db, 'SELECT b.data FROM events e JOIN bodies b ON b.seq = e.seq WHERE e.rkey = ? AND e.id = ?').get(key, id);
  return r ? r.data : null;
}

const PAGE_MAX = 5000;

/**
 * Chronologie : tous les événements triés par heure puis par ordre d'émission dans la JVM, par pages.
 * Curseur "t:o:seq" ; before = page précédant le curseur, after = page suivante, at = à partir d'une heure (ms),
 * rien = les plus récents. Filtres : types (liste), agent, q (texte sur entité / propriété / id / appelant / sonde, ou #tag).
 */
export function timeline(db, { before = null, after = null, at = null, limit = 500, types = [], agent = '', q = '', entity = '' }) {
  limit = Math.max(1, Math.min(PAGE_MAX, Number(limit) || 500));
  const where = [];
  const args = [];
  if (entity) {
    // Un type : ses événements propres et ceux qui le référencent (UTIL ciblant une instance...)
    where.push('(e.ename = ? OR e.seq IN (SELECT seq FROM refs WHERE name = ?))');
    args.push(simple(entity), simple(entity));
  }
  if (types.length) {
    where.push('e.type IN (SELECT value FROM json_each(?))');
    args.push(JSON.stringify(types));
  }
  if (agent) {
    where.push('e.agent = ?');
    args.push(agent);
  }
  for (const word of q.trim().split(/\s+/).filter(Boolean)) {
    if (word.startsWith('#') && word.length > 1) {
      where.push('EXISTS (SELECT 1 FROM tags g WHERE g.tag = ? AND g.seq = e.seq)');
      args.push(word.slice(1));
    } else {
      const like = `%${word.replace(/[\\%_]/g, '\\$&')}%`;
      where.push(`(${['entity', 'prop', 'eid', 'caller', 'probe', 'label', 'id']
        .map((c) => `e.${c} LIKE ? ESCAPE '\\'`).join(' OR ')})`);
      args.push(like, like, like, like, like, like, like);
    }
  }
  const cur = (c) => String(c).split(':').map(Number);
  let pos = null;
  let desc = true;
  if (after) {
    pos = cur(after);
    desc = false;
  } else if (at != null && at !== '') {
    pos = [Number(at), -1, -1];
    desc = false;
  } else if (before) pos = cur(before);
  const filter = where.join(' AND ');
  const and = (extra) => [extra, filter].filter(Boolean).join(' AND ') || '1';
  const sql = (cond, dir) =>
    `SELECT e.rkey, e.t AS tt, e.o, e.seq, ${LIGHT} FROM events e JOIN bodies b ON b.seq = e.seq
     WHERE ${and(cond)} ORDER BY e.t ${dir}, e.o ${dir}, e.seq ${dir} LIMIT ?`;
  return snapshot(db, () => {
    const cond = pos ? (desc ? '(e.t, e.o, e.seq) < (?, ?, ?)' : '(e.t, e.o, e.seq) > (?, ?, ?)') : '';
    let rows = st(db, sql(cond, desc ? 'DESC' : 'ASC')).all(...(pos || []), ...args, limit + 1);
    const more = rows.length > limit;
    rows = rows.slice(0, limit);
    if (desc) rows.reverse();
    // Taille : on garde le début de la page (ordre chronologique)
    let size = 0;
    let n = 0;
    while (n < rows.length && size + rows[n].data.length < MAX_CHARS) size += rows[n++].data.length + 1;
    const cut = n < rows.length;
    rows = rows.slice(0, n);
    const first = rows[0];
    const last = rows[rows.length - 1];
    const exists = (c, k) => !!st(db, `SELECT 1 FROM events e WHERE ${and(c)} LIMIT 1`).get(k.tt, k.o, k.seq, ...args);
    const hasOlder = first ? (desc ? more : exists('(e.t, e.o, e.seq) < (?, ?, ?)', first)) : !desc && !!pos;
    const hasNewer = last ? (desc ? cut || (pos ? exists('(e.t, e.o, e.seq) > (?, ?, ?)', last) : false) : more || cut) : desc && !!pos;
    const keys = [...new Set(rows.map((r) => r.rkey))];
    const reqs = st(db, 'SELECT key, data FROM requests WHERE key IN (SELECT value FROM json_each(?))').all(JSON.stringify(keys));
    const c = (r) => `${r.tt}:${r.o}:${r.seq}`;
    return `{"hasOlder":${hasOlder},"hasNewer":${hasNewer},"first":${JSON.stringify(first ? c(first) : null)},"last":${JSON.stringify(last ? c(last) : null)},` +
      `"reqs":{${reqs.map((r) => `${JSON.stringify(r.key)}:${r.data}`).join(',')}},"events":${list(rows)},"rkeys":${JSON.stringify(rows.map((r) => r.rkey))}}`;
  });
}

/** Nombre de N+1 probables de chaque requête ; n = nombre d'événements du résumé au moment du calcul. */
export function nplus(db, { keys }) {
  return snapshot(db, () =>
    keys.map((key) => {
      const req = st(db, 'SELECT n FROM requests WHERE key = ?').get(key);
      const rows = st(db, `SELECT ${LIGHT} FROM events e JOIN bodies b ON b.seq = e.seq WHERE e.rkey = ? AND e.type IN ('PROXY', 'COLLECTION', 'SQL')`).all(key);
      const events = rows.map((r) => JSON.parse(r.data));
      return { key, n: req ? Number(req.n) : 0, nplus: events.length ? detectNPlusOne(events).length : 0 };
    })
  );
}

/** Synthèse par type d'entité, toutes requêtes confondues (compteurs tenus à jour à l'écriture). */
export function entities(db) {
  return snapshot(db, () => {
    const m = new Map();
    for (const r of st(db, 'SELECT entity, type, cause, lazy, n, dur FROM agg_entity').all()) {
      let x = m.get(r.entity);
      if (!x) m.set(r.entity, (x = { entity: r.entity, LOAD: 0, lazyLoad: 0, wantedLoad: 0, PROXY: 0, COLLECTION: 0, ACCESS: 0, SESSION: 0, LAZY_ERROR: 0, lazyAccess: 0, time: 0, ids: 0 }));
      x[r.type] = (x[r.type] || 0) + r.n;
      if (r.type === 'LOAD') {
        if (r.cause === 'lazy') x.lazyLoad += r.n;
        else if (r.cause === 'init' || r.cause === 'explicit' || r.cause === 'query') x.wantedLoad += r.n;
      }
      if (r.type === 'ACCESS' && r.lazy) x.lazyAccess += r.n;
      if (r.type === 'PROXY' || r.type === 'COLLECTION') x.time += r.dur;
    }
    // Ids distincts par nom simple (index refs_ref parcouru sans lire la table)
    const ids = new Map(st(db, 'SELECT name, count(DISTINCT eid) n FROM refs GROUP BY name').all().map((r) => [r.name, r.n]));
    for (const x of m.values()) x.ids = ids.get(simple(x.entity)) || 0;
    return [...m.values()].sort((a, b) => b.PROXY + b.COLLECTION + b.LAZY_ERROR - (a.PROXY + a.COLLECTION + a.LAZY_ERROR) || b.LOAD - a.LOAD);
  });
}

/** Un type d'entité : ses ids (compteurs, instances, sessions) et ses propriétés. */
export function entityType(db, { entity, filter = '', limit = 500 }) {
  const name = simple(entity);
  return snapshot(db, () => {
    const rows = st(
      db,
      `SELECT r.eid, e.type, e.cause, e.state, e.inst, e.rinst, e.sess, e.rkey, e.t
       FROM refs r JOIN events e ON e.seq = r.seq WHERE r.name = ?`
    ).all(name);
    const m = new Map();
    for (const e of rows) {
      let r = m.get(e.eid);
      if (!r) {
        r = { eid: e.eid, LOAD: 0, lazyLoad: 0, wantedLoad: 0, PROXY: 0, COLLECTION: 0, ACCESS: 0, lazyAccess: 0, UTIL: 0, SESSION: 0, LAZY_ERROR: 0, insts: new Set(), sessions: new Set(), reqs: new Set(), last: 0 };
        m.set(e.eid, r);
      }
      r[e.type] = (r[e.type] || 0) + 1;
      if (e.type === 'LOAD') {
        if (e.cause === 'lazy') r.lazyLoad++;
        else if (e.cause === 'init' || e.cause === 'explicit' || e.cause === 'query') r.wantedLoad++;
      }
      if (e.type === 'ACCESS' && e.state) r.lazyAccess++;
      if (e.inst && !e.inst.startsWith('p')) r.insts.add(e.inst);
      if (e.rinst) r.insts.add(e.rinst);
      if (e.sess) r.sessions.add(e.sess);
      r.reqs.add(e.rkey);
      if (e.t > r.last) r.last = e.t;
    }
    const all = [...m.values()].map((r) => ({ ...r, insts: r.insts.size, sessions: r.sessions.size, reqs: r.reqs.size }));
    all.sort((a, b) => b.LAZY_ERROR - a.LAZY_ERROR || b.insts - a.insts || b.last - a.last);
    const totals = { ids: all.length, LOAD: 0, lazyLoad: 0, wantedLoad: 0, LAZY_ERROR: 0, multi: 0 };
    for (const x of all) {
      totals.LOAD += x.LOAD;
      totals.lazyLoad += x.lazyLoad;
      totals.wantedLoad += x.wantedLoad;
      totals.LAZY_ERROR += x.LAZY_ERROR;
      if (x.insts > 1) totals.multi++;
    }
    const f = filter.trim();
    const matching = f ? all.filter((x) => x.eid.includes(f)) : all;
    const props = st(
      db,
      `SELECT e.entity, e.prop,
         sum(e.type = 'ACCESS') AS ACCESS, sum(e.type = 'ACCESS' AND e.state IS NOT NULL) AS lazy,
         sum(e.type = 'PROXY') AS PROXY, sum(e.type = 'COLLECTION') AS COLLECTION, sum(e.type = 'LAZY_ERROR') AS LAZY_ERROR,
         count(DISTINCT e.caller) AS callers, total(CASE WHEN e.type <> 'ACCESS' THEN e.dur END) AS time
       FROM events e WHERE e.ename = ? AND e.type IN ('ACCESS', 'COLLECTION', 'PROXY', 'LAZY_ERROR') AND e.prop IS NOT NULL
       GROUP BY e.entity, e.prop`
    ).all(name);
    props.sort((a, b) => b.COLLECTION + b.PROXY - (a.COLLECTION + a.PROXY) || b.ACCESS - a.ACCESS);
    return { totals, matching: matching.length, ids: matching.slice(0, limit), props };
  });
}

/**
 * Événements utiles à l'historique d'une entité : ceux qui la référencent, ceux de ses instances Java,
 * et les clear / close de ses sessions. Le filtrage fin est fait par entityHistory côté UI.
 */
export function entity(db, { entity, id, limit = 20_000 }) {
  const name = simple(entity);
  return snapshot(db, () => {
    const direct = st(db, 'SELECT e.seq, e.inst, e.rinst, e.sess FROM refs r JOIN events e ON e.seq = r.seq WHERE r.name = ? AND r.eid = ?').all(name, String(id));
    const seqs = new Set();
    const insts = new Set();
    const sessions = new Set();
    for (const r of direct) {
      seqs.add(r.seq);
      if (r.inst) insts.add(r.inst);
      if (r.rinst) insts.add(r.rinst);
      if (r.sess) sessions.add(r.sess);
    }
    for (const inst of insts)
      for (const r of st(db, "SELECT seq FROM events WHERE inst = ? AND type NOT IN ('UTIL', 'SQL')").all(inst)) seqs.add(r.seq);
    for (const sess of sessions)
      for (const r of st(db, "SELECT seq FROM events WHERE sess = ? AND type = 'SESSION' AND prop IN ('clear', 'close')").all(sess)) seqs.add(r.seq);
    // Les plus récents si l'entité est très sollicitée (nombre, puis taille)
    const keep = [...seqs].sort((a, b) => a - b).slice(-limit);
    let rows = st(db, `SELECT e.rkey, ${LIGHT} FROM events e JOIN bodies b ON b.seq = e.seq WHERE e.seq IN (SELECT value FROM json_each(?)) ORDER BY e.seq`).all(JSON.stringify(keep));
    let size = 0;
    let from = rows.length;
    while (from > 0 && size + rows[from - 1].data.length < MAX_CHARS) size += rows[--from].data.length + 1;
    if (from > 0) rows = rows.slice(from);
    const keys = [...new Set(rows.map((r) => r.rkey))];
    const reqs = st(db, 'SELECT key, data FROM requests WHERE key IN (SELECT value FROM json_each(?))').all(JSON.stringify(keys));
    return `{"truncated":${seqs.size > rows.length},"reqs":{${reqs.map((r) => `${JSON.stringify(r.key)}:${r.data}`).join(',')}},"events":${list(rows)},"rkeys":${JSON.stringify(rows.map((r) => r.rkey))}}`;
  });
}

function stats(r) {
  const d = r.durs.sort((a, b) => a - b);
  r.total = d.reduce((s, x) => s + x, 0);
  r.spans = d.length;
  r.avg = d.length ? r.total / d.length : 0;
  r.p95 = d.length ? d[Math.min(d.length - 1, Math.floor(0.95 * d.length))] : 0;
  r.max = d.length ? d[d.length - 1] : 0;
  delete r.durs;
  return r;
}

/**
 * Mesures : par tag (événements portant le tag, propre ou hérité ; durées des spans qui le portent eux-mêmes),
 * par méthode sondée (UTIL, éventuellement restreintes à un tag), et spans les plus lents d'un tag.
 */
export function measures(db, { tag = null }) {
  return snapshot(db, () => {
    const tags = new Map();
    for (const r of st(db, 'SELECT tag, type, own, err, n FROM agg_tag').all()) {
      let t = tags.get(r.tag);
      if (!t) tags.set(r.tag, (t = { tag: r.tag, events: 0, inherited: 0, errors: 0, reqs: 0, types: {}, durs: [] }));
      t.events += r.n;
      if (!r.own) t.inherited += r.n;
      if (r.err) t.errors += r.n;
      t.types[r.type] = (t.types[r.type] || 0) + r.n;
    }
    for (const r of st(db, 'SELECT tag, count(*) n FROM agg_tag_req GROUP BY tag').all()) if (tags.has(r.tag)) tags.get(r.tag).reqs = r.n;
    for (const r of st(db, `SELECT tag, dur FROM tags WHERE own = 1 AND dur IS NOT NULL AND type IN (${SPANS})`).all()) tags.get(r.tag)?.durs.push(r.dur);
    const byTag = [...tags.values()].map(stats).sort((a, b) => b.total - a.total || b.events - a.events);

    // Restriction à un tag : test d'existence par clé (tag, seq), à partir des méthodes sondées (peu nombreuses)
    const scope = tag ? 'AND EXISTS (SELECT 1 FROM tags g WHERE g.tag = ? AND g.seq = e.seq)' : '';
    const args = tag ? [tag] : [];
    const probes = new Map();
    const keyOf = (r) => `${r.probe || ''}|${r.entity}.${r.prop}`;
    for (const r of st(db, `SELECT e.probe, e.entity, e.prop, e.label, e.dur, e.err, e.rkey, e.id FROM events e WHERE e.type = 'UTIL' ${scope}`).all(...args)) {
      const key = keyOf(r);
      let p = probes.get(key);
      if (!p) probes.set(key, (p = { key, probe: r.probe, label: r.label, entity: r.entity, prop: r.prop, tags: [], durs: [], reqs: new Set(), errors: 0, events: 0, slowest: null }));
      p.events++;
      p.reqs.add(r.rkey);
      p.errors += r.err;
      if (r.dur != null) {
        p.durs.push(r.dur);
        if (!p.slowest || r.dur > p.slowest.dur) p.slowest = { req: r.rkey, id: r.id, dur: r.dur };
      }
    }
    for (const r of st(db, `SELECT DISTINCT e.probe, e.entity, e.prop, t.tag FROM events e JOIN tags t ON t.seq = e.seq AND t.own = 1 WHERE e.type = 'UTIL' ${scope}`).all(...args))
      probes.get(keyOf(r))?.tags.push(r.tag);
    const byProbe = [...probes.values()].map((p) => stats({ ...p, reqs: p.reqs.size })).sort((a, b) => b.total - a.total);

    let slowest = [];
    if (tag) {
      const rows = st(db, `SELECT g.rkey, b.data FROM tags g JOIN bodies b ON b.seq = g.seq WHERE g.tag = ? AND g.dur IS NOT NULL AND g.type IN (${SPANS}) ORDER BY g.dur DESC LIMIT 40`).all(tag);
      const reqs = new Map(
        st(db, 'SELECT key, data FROM requests WHERE key IN (SELECT value FROM json_each(?))')
          .all(JSON.stringify([...new Set(rows.map((r) => r.rkey))]))
          .map((r) => [r.key, JSON.parse(r.data)])
      );
      slowest = rows.map((r) => ({ e: JSON.parse(r.data), req: reqs.get(r.rkey) || { key: r.rkey } }));
    }
    return { tags: byTag, probes: byProbe, slowest };
  });
}
