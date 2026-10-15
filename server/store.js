// Stockage des événements : SQLite intégré à Node (node:sqlite), un fichier en mode WAL.
// Le thread principal écrit (lots reçus, résumés de requêtes, références d'entités, tags, agrégats, purge) ;
// les lectures lourdes passent par des workers (reader.js) qui ouvrent le même fichier, sans bloquer la réception.
//
//   events       un événement par ligne, colonnes utiles aux recherches (table compacte : parcours rapides)
//   bodies       JSON complet de chaque événement, lu seulement pour l'afficher
//   refs         (nom simple d'entité, id) -> événement : historique d'une entité, ids d'un type
//   tags         (tag, événement, propre / hérité + colonnes de l'événement) : mesures par tag sans jointure
//   requests     résumé de chaque requête (compteurs, durée, N+1) pour la liste
//   agg_*        compteurs tenus à jour à l'écriture (et décomptés à la purge) : pages Entités et Mesures
//                instantanées quel que soit le volume

import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from './sqlite.js';
import { newRequest, applySummary, reqKey, entityOf, simple, tagsOf, allTags, causeOf } from '../ui/src/lib/model.js';
import { applyRules } from './profiles.js';

// À incrémenter quand le schéma change : une base d'une autre version est recréée (l'historique est perdu)
const VERSION = 3;
const TABLES = ['events', 'bodies', 'refs', 'tags', 'requests', 'agg_entity', 'agg_tag', 'agg_tag_req'];
// Types sans entité propre, exclus des agrégats par entité
const NOT_ENTITY = new Set(['UTIL', 'SQL', 'REQ_START', 'REQ_END']);

const SCHEMA = `
CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY,
  rkey TEXT NOT NULL,
  agent TEXT, id TEXT, type TEXT, t INTEGER,
  entity TEXT, ename TEXT, prop TEXT, eid TEXT, caller TEXT, probe TEXT, label TEXT,
  inst TEXT, rinst TEXT, sess TEXT, dur REAL, err INTEGER, cause TEXT, state TEXT
);
CREATE INDEX IF NOT EXISTS ev_rkey ON events(rkey);
CREATE INDEX IF NOT EXISTS ev_inst ON events(inst) WHERE inst IS NOT NULL;
CREATE INDEX IF NOT EXISTS ev_sess ON events(sess) WHERE type = 'SESSION';
CREATE INDEX IF NOT EXISTS ev_ename ON events(ename, type);
CREATE INDEX IF NOT EXISTS ev_util ON events(type, probe, entity, prop);
CREATE TABLE IF NOT EXISTS bodies (seq INTEGER PRIMARY KEY, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS refs (name TEXT NOT NULL, eid TEXT NOT NULL, seq INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS refs_ref ON refs(name, eid);
CREATE INDEX IF NOT EXISTS refs_seq ON refs(seq);
CREATE TABLE IF NOT EXISTS tags (
  tag TEXT NOT NULL, seq INTEGER NOT NULL, own INTEGER NOT NULL, type TEXT, dur REAL, err INTEGER, rkey TEXT,
  PRIMARY KEY (tag, seq)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS tags_seq ON tags(seq);
CREATE INDEX IF NOT EXISTS tags_span ON tags(type, tag, dur) WHERE own = 1 AND dur IS NOT NULL;
CREATE TABLE IF NOT EXISTS requests (
  key TEXT PRIMARY KEY,
  t INTEGER, n INTEGER, lazy INTEGER, nplus INTEGER, nplusN INTEGER, done INTEGER, lastSeq INTEGER,
  txt TEXT, data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS req_t ON requests(t);
CREATE INDEX IF NOT EXISTS req_seq ON requests(lastSeq);
CREATE TABLE IF NOT EXISTS agg_entity (
  entity TEXT NOT NULL, type TEXT NOT NULL, cause TEXT NOT NULL, lazy INTEGER NOT NULL, n INTEGER NOT NULL, dur REAL NOT NULL,
  PRIMARY KEY (entity, type, cause, lazy)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS agg_tag (
  tag TEXT NOT NULL, type TEXT NOT NULL, own INTEGER NOT NULL, err INTEGER NOT NULL, n INTEGER NOT NULL,
  PRIMARY KEY (tag, type, own, err)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS agg_tag_req (tag TEXT NOT NULL, rkey TEXT NOT NULL, PRIMARY KEY (tag, rkey)) WITHOUT ROWID;
`;

/** Ouvre la base (création du schéma côté écrivain, lecture seule côté workers). */
export function openDb(file, { readonly = false } = {}) {
  if (!readonly) fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA busy_timeout = 5000; PRAGMA temp_store = MEMORY; PRAGMA cache_size = -65536; PRAGMA mmap_size = 268435456;');
  if (readonly) {
    db.exec('PRAGMA query_only = 1');
    return db;
  }
  db.exec('PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;');
  if (db.prepare('PRAGMA user_version').get().user_version !== VERSION) {
    for (const t of TABLES) db.exec(`DROP TABLE IF EXISTS ${t}`);
    db.exec(`PRAGMA user_version = ${VERSION}`);
  }
  db.exec(SCHEMA);
  // Chronologie : ordre d'émission dans la JVM (compteur des ids de l'agent), départage les événements d'une même ms.
  // Colonne ajoutée sans recréer la base ; les événements déjà stockés gardent o = 0 (départagés par seq).
  if (!db.prepare('PRAGMA table_info(events)').all().some((c) => c.name === 'o')) db.exec('ALTER TABLE events ADD COLUMN o INTEGER NOT NULL DEFAULT 0');
  db.exec('CREATE INDEX IF NOT EXISTS ev_time ON events(t, o, seq)');
  return db;
}

/** Fin de requête : placée à l'heure de fin, après tout ce qui s'est passé pendant cette ms. */
const END = 2 ** 52;

/** Position dans la chronologie : heure (ms) et ordre d'émission dans la JVM. */
function timeOf(e) {
  if (e.type === 'REQ_END') return [typeof e.t === 'number' && typeof e.dur === 'number' ? Math.round(e.t + e.dur) : e.t ?? null, END];
  const m = /(\d+)$/.exec(e.id == null ? '' : String(e.id));
  return [e.t ?? null, m ? Number(m[1]) : 0];
}

/** Compteurs d'un lot (ou d'une purge), reportés en une fois dans une table agg_*. */
class Deltas {
  m = new Map();

  add(key, n, dur = 0) {
    const d = this.m.get(key);
    if (d) {
      d.n += n;
      d.dur += dur;
    } else this.m.set(key, { n, dur });
  }
}

// Résumés de requêtes gardés en mémoire (requêtes actives) pour éviter de les relire à chaque lot
const CACHE = 5000;

export class EventStore {
  #db;
  #cache = new Map();
  #retag = 0;
  #aggEntity = new Deltas();
  #aggTag = new Deltas();
  #tagReqs = new Set();

  constructor(file, { maxEvents }) {
    this.file = file;
    this.maxEvents = maxEvents;
    this.#db = openDb(file);
    const db = this.#db;
    this.insEvent = db.prepare(`INSERT INTO events (rkey, agent, id, type, t, o, entity, ename, prop, eid, caller, probe, label, inst, rinst, sess, dur, err, cause, state)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
    this.insBody = db.prepare('INSERT INTO bodies (seq, data) VALUES (?, ?)');
    this.insRef = db.prepare('INSERT INTO refs (name, eid, seq) VALUES (?, ?, ?)');
    this.insTag = db.prepare('INSERT OR IGNORE INTO tags (tag, seq, own, type, dur, err, rkey) VALUES (?, ?, ?, ?, ?, ?, ?)');
    this.putReq = db.prepare('INSERT OR REPLACE INTO requests (key, t, n, lazy, nplus, nplusN, done, lastSeq, txt, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
    this.getReq = db.prepare('SELECT data FROM requests WHERE key = ?');
    this.upAggEntity = db.prepare(`INSERT INTO agg_entity (entity, type, cause, lazy, n, dur) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT DO UPDATE SET n = n + excluded.n, dur = dur + excluded.dur`);
    this.upAggTag = db.prepare('INSERT INTO agg_tag (tag, type, own, err, n) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO UPDATE SET n = n + excluded.n');
    this.insTagReq = db.prepare('INSERT OR IGNORE INTO agg_tag_req (tag, rkey) VALUES (?, ?)');
    this.count = Number(db.prepare('SELECT count(*) n FROM events').get().n);
    this.requestCount = Number(db.prepare('SELECT count(*) n FROM requests').get().n);
  }

  /** Exécute fn dans une transaction. */
  #tx(fn) {
    this.#db.exec('BEGIN');
    try {
      const r = fn();
      this.#db.exec('COMMIT');
      return r;
    } catch (err) {
      this.#db.exec('ROLLBACK');
      this.#aggEntity.m.clear();
      this.#aggTag.m.clear();
      this.#tagReqs.clear();
      throw err;
    }
  }

  /** Enregistre un lot d'un agent ; retourne les clés des requêtes modifiées. */
  add(agentId, batch, rules) {
    const touched = new Map();
    let n = 0;
    this.#tx(() => {
      for (const e of batch) {
        if (!e || typeof e !== 'object') continue;
        e.agent = agentId;
        if (rules?.length) applyRules(rules, e);
        const key = reqKey(e);
        const r = touched.get(key) || this.#summary(key, e);
        touched.set(key, r);
        applySummary(r, e);
        r.lastSeq = this.#insert(key, e);
        n++;
      }
      for (const r of touched.values()) this.#put(r);
      this.#flushAggregates();
    });
    this.count += n;
    if (this.count > this.maxEvents * 1.02) this.prune();
    return [...touched.keys()];
  }

  #insert(key, e) {
    const cause = causeOf(e)?.kind ?? null;
    const dur = typeof e.dur === 'number' ? e.dur : null;
    const [t, o] = timeOf(e);
    const info = this.insEvent.run(
      key, e.agent, e.id == null ? null : String(e.id), e.type, t, o,
      e.entity ?? null, e.entity ? simple(e.entity) : null, e.prop ?? null, e.eid == null ? null : String(e.eid),
      e.caller ?? null, e.probe ?? null, e.label ?? null, e.inst ?? null, e.rinst ?? null, e.sess ?? null,
      dur, e.error ? 1 : 0, cause, e.state || null
    );
    const seq = Number(info.lastInsertRowid);
    this.insBody.run(seq, JSON.stringify(e));
    const ref = entityOf(e);
    if (ref && ref.eid != null) {
      const name = simple(ref.entity);
      for (const id of String(ref.eid).split(',')) if (id && id !== '…') this.insRef.run(name, id, seq);
    }
    if (e.entity && !NOT_ENTITY.has(e.type)) this.#aggEntity.add(`${e.entity}\u0001${e.type}\u0001${cause || ''}\u0001${e.state ? 1 : 0}`, 1, dur || 0);
    this.#insertTags(e, seq, key);
    return seq;
  }

  #insertTags(e, seq, key) {
    if (!e.tags && !e.rtags && !e.scope) return;
    const own = tagsOf(e);
    const dur = typeof e.dur === 'number' ? e.dur : null;
    const err = e.error ? 1 : 0;
    for (const tag of allTags(e)) {
      const o = own.includes(tag) ? 1 : 0;
      this.insTag.run(tag, seq, o, e.type, dur, err, key);
      this.#aggTag.add(`${tag}\u0001${e.type}\u0001${o}\u0001${err}`, 1);
      this.#tagReqs.add(`${tag}\u0001${key}`);
    }
  }

  #flushAggregates() {
    for (const [k, d] of this.#aggEntity.m) {
      const [entity, type, cause, lazy] = k.split('\u0001');
      this.upAggEntity.run(entity, type, cause, Number(lazy), d.n, d.dur);
    }
    for (const [k, d] of this.#aggTag.m) {
      const [tag, type, own, err] = k.split('\u0001');
      this.upAggTag.run(tag, type, Number(own), Number(err), d.n);
    }
    for (const k of this.#tagReqs) this.insTagReq.run(...k.split('\u0001'));
    this.#aggEntity.m.clear();
    this.#aggTag.m.clear();
    this.#tagReqs.clear();
  }

  /** Résumé d'une requête : cache, sinon base, sinon nouveau. */
  #summary(key, e) {
    let r = this.#cache.get(key);
    if (r) {
      this.#cache.delete(key);
    } else {
      const row = this.getReq.get(key);
      r = row ? JSON.parse(row.data) : newRequest(e);
      if (!row) this.requestCount++;
    }
    this.#cache.set(key, r);
    if (this.#cache.size > CACHE) this.#cache.delete(this.#cache.keys().next().value);
    return r;
  }

  #put(r) {
    const txt = `${r.uri || ''} ${r.source || ''} ${r.method || ''}`;
    this.putReq.run(r.key, r.t, r.n, r.counts.LAZY_ERROR || 0, r.nplus || 0, r.nplusN || 0, r.done ? 1 : 0, r.lastSeq || 0, txt, JSON.stringify(r));
  }

  /** Requêtes dont le nombre de N+1 est à recalculer (nouveaux événements depuis le dernier calcul). */
  staleNplus(limit) {
    return this.#db.prepare('SELECT key, n, nplusN, done FROM requests WHERE n <> nplusN ORDER BY lastSeq DESC LIMIT ?').all(limit);
  }

  setNplus(results) {
    this.#tx(() => {
      for (const { key, nplus, n } of results) {
        let row = this.#cache.get(key);
        if (!row) {
          const saved = this.getReq.get(key);
          if (!saved) continue;
          row = JSON.parse(saved.data);
        }
        row.nplus = nplus;
        row.nplusN = n;
        this.#put(row);
      }
    });
  }

  /** Supprime les événements les plus anciens au-delà de maxEvents, en décomptant les agrégats. */
  prune() {
    const excess = this.count - this.maxEvents;
    if (excess <= 0) return;
    const db = this.#db;
    const row = db.prepare('SELECT seq FROM events ORDER BY seq LIMIT 1 OFFSET ?').get(excess);
    if (!row) return;
    const cut = row.seq;
    this.#tx(() => {
      for (const r of db.prepare(`SELECT entity, type, coalesce(cause, '') cause, state IS NOT NULL lazy, count(*) n, total(dur) dur
          FROM events WHERE seq < ? AND entity IS NOT NULL AND type NOT IN ('UTIL', 'SQL', 'REQ_START', 'REQ_END') GROUP BY 1, 2, 3, 4`).all(cut))
        this.upAggEntity.run(r.entity, r.type, r.cause, r.lazy, -r.n, -r.dur);
      for (const r of db.prepare('SELECT tag, type, own, err, count(*) n FROM tags WHERE seq < ? GROUP BY 1, 2, 3, 4').all(cut))
        this.upAggTag.run(r.tag, r.type, r.own, r.err, -r.n);
      db.exec('DELETE FROM agg_entity WHERE n <= 0; DELETE FROM agg_tag WHERE n <= 0;');
      const n = db.prepare('DELETE FROM events WHERE seq < ?').run(cut).changes;
      db.prepare('DELETE FROM bodies WHERE seq < ?').run(cut);
      db.prepare('DELETE FROM refs WHERE seq < ?').run(cut);
      db.prepare('DELETE FROM tags WHERE seq < ?').run(cut);
      const gone = db.prepare('SELECT key FROM requests WHERE lastSeq < ?').all(cut).map((r) => r.key);
      db.prepare('DELETE FROM agg_tag_req WHERE rkey IN (SELECT value FROM json_each(?))').run(JSON.stringify(gone));
      db.prepare('DELETE FROM requests WHERE lastSeq < ?').run(cut);
      this.requestCount -= gone.length;
      this.count -= Number(n);
    });
    for (const [k, r] of this.#cache) if (r.lastSeq < cut) this.#cache.delete(k);
  }

  clear() {
    this.#retag++;
    this.#tx(() => {
      for (const t of TABLES) this.#db.exec(`DELETE FROM ${t}`);
    });
    this.#db.exec('PRAGMA wal_checkpoint(TRUNCATE)');
    this.#cache.clear();
    this.count = 0;
    this.requestCount = 0;
  }

  /**
   * Recalcule les tags des règles (rtags) sur tout l'historique, par tranches, sans bloquer le serveur.
   * Une nouvelle demande interrompt la précédente. Retourne le nombre d'événements modifiés, false si interrompu.
   */
  async retag(rulesFor) {
    const token = ++this.#retag;
    const db = this.#db;
    const sel = db.prepare('SELECT b.seq, b.data, e.rkey FROM bodies b JOIN events e ON e.seq = b.seq WHERE b.seq > ? ORDER BY b.seq LIMIT 5000');
    const upd = db.prepare('UPDATE bodies SET data = ? WHERE seq = ?');
    const oldTags = db.prepare('SELECT tag, type, own, err FROM tags WHERE seq = ?');
    const delTags = db.prepare('DELETE FROM tags WHERE seq = ?');
    const rules = new Map();
    let last = 0;
    let changed = 0;
    for (;;) {
      if (token !== this.#retag) return false;
      const rows = sel.all(last);
      if (!rows.length) break;
      this.#tx(() => {
        for (const row of rows) {
          const e = JSON.parse(row.data);
          if (!rules.has(e.agent)) rules.set(e.agent, rulesFor(e.agent));
          const before = JSON.stringify(e.rtags || null);
          applyRules(rules.get(e.agent), e);
          if (JSON.stringify(e.rtags || null) === before) continue;
          upd.run(JSON.stringify(e), row.seq);
          for (const t of oldTags.all(row.seq)) this.#aggTag.add(`${t.tag}\u0001${t.type}\u0001${t.own}\u0001${t.err}`, -1);
          delTags.run(row.seq);
          this.#insertTags(e, row.seq, row.rkey);
          changed++;
        }
        this.#flushAggregates();
      });
      last = rows[rows.length - 1].seq;
      await new Promise((r) => setImmediate(r));
    }
    // Requêtes par tag : recalculées en une fois (un tag retiré ne laisse pas de requête orpheline)
    this.#tx(() => db.exec('DELETE FROM agg_tag WHERE n <= 0; DELETE FROM agg_tag_req; INSERT INTO agg_tag_req SELECT DISTINCT tag, rkey FROM tags;'));
    return changed;
  }

  /** Parcourt tous les événements (JSON) par tranches, pour l'export. */
  async *scan() {
    const sel = this.#db.prepare('SELECT seq, data FROM bodies WHERE seq > ? ORDER BY seq LIMIT 10000');
    let last = 0;
    for (;;) {
      const rows = sel.all(last);
      if (!rows.length) return;
      yield rows.map((r) => r.data);
      last = rows[rows.length - 1].seq;
    }
  }
}
