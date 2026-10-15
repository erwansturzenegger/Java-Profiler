// Serveur de collecte de l'Entity Profiler. Aucune dépendance : node server.js
//
//   POST /api/agent/poll     agent -> serveur : heartbeat + entités connues + état des sondes,
//                            réponse = config dynamique (+ sondes à installer si elles ont changé)
//   POST /api/agent/events   agent -> serveur : lot d'événements
//   POST /api/agent/bye      agent -> serveur : l'agent s'est détaché
//   GET  /api/jvms           UI : JVM locales (java -jar agent.jar list), avec l'agent éventuellement attaché
//   POST /api/jvms/attach    UI : attache l'agent à chaud à une JVM locale { pid, name? }
//   POST /api/agents/detach  UI : demande le détachement d'un agent { id } (appliqué au prochain poll, < 1 s)
//   GET  /api/state          UI : config, agents, entités connues
//   GET  /api/requests       UI : liste des requêtes (?q=&hideEmpty=1&problems=0&limit=300&offset=0)
//   GET  /api/request        UI : une requête et ses événements (?key=)
//   GET  /api/entities       UI : synthèse par type d'entité
//   GET  /api/entities/type  UI : ids et propriétés d'un type (?entity=&filter=)
//   GET  /api/entity         UI : événements de l'historique d'une entité (?entity=&id=)
//   GET  /api/measures       UI : mesures par tag et par méthode sondée (?tag=)
//   GET  /api/stream         UI : flux SSE (changed, reload, config, agents, profiles)
//   PUT  /api/config         UI : modifie la config (poussée aux agents au prochain poll, < 1 s)
//   POST /api/clear          UI : vide l'historique
//   GET  /api/export         téléchargement JSON de tout l'historique (flux)
//   GET  /api/profiles       UI : profils (server/profiles/*.json) et handlers disponibles
//   PUT  /api/profiles/:nom  UI : crée / remplace un profil (appliqué aux agents au prochain poll)
//   DELETE /api/profiles/:nom
//   POST /api/import         ajoute un export JSON à l'historique
//
// Les événements sont stockés dans SQLite (server/data/events.db) : l'historique survit aux redémarrages et
// l'UI ne charge que ce qu'elle affiche. Les lectures lourdes tournent dans des workers (reader.js).
//   GET  /*                  UI compilée (../ui/dist)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Profiles, HANDLERS } from './profiles.js';
import { EventStore } from './store.js';
import { Readers } from './reader.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 7070);
const HOST = process.env.HOST || '0.0.0.0';
// Au-delà, les événements les plus anciens sont supprimés
const MAX_EVENTS = Number(process.env.MAX_EVENTS || 2_000_000);
const DB_FILE = process.env.DB_FILE || path.join(here, 'data', 'events.db');
const UI_DIR = process.env.UI_DIR || path.join(here, '..', 'ui', 'dist');
const CONFIG_FILE = process.env.CONFIG_FILE || path.join(here, 'config.json');
const PROFILES_DIR = process.env.PROFILES_DIR || path.join(here, 'profiles');
// Attachement à chaud depuis l'UI : un JDK est nécessaire (module jdk.attach)
const AGENT_JAR = process.env.AGENT_JAR || path.join(here, '..', 'agent', 'target', 'entity-profiler-agent.jar');
const JAVA = process.env.JAVA || (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java');

const DEFAULT_CONFIG = {
  recording: true,
  // '*' = toutes ; FQCN, nom simple (Patient) ou glob (com.acme.model.patient.*)
  entities: ['*'],
  // Mode focus { entity, id, access } : remplace entities / types, une seule entité suivie (voir RuntimeConfig.focus)
  focus: null,
  types: { LOAD: true, PROXY: true, COLLECTION: true, ACCESS: false, UTIL: true, SQL: true, SESSION: true, LAZY_ERROR: true },
  // Packages applicatifs (appelant "métier"). Vide : tout ce qui n'est pas une bibliothèque. Les profils en ajoutent.
  callerPackages: [],
  // Frames ignorés pour déterminer l'appelant "métier" (le framework est alors visible comme déclencheur)
  callerExclude: [],
  accessExclude: [],
  // Bibliothèques : jamais "appelant" quand aucun package applicatif n'est défini
  libraryPackages: ['java.', 'javax.', 'jakarta.', 'jdk.', 'sun.', 'com.sun.', 'org.hibernate.', 'org.springframework.', 'org.apache.',
    'org.jboss.', 'org.wildfly.', 'io.undertow.', 'org.eclipse.', 'org.glassfish.', 'net.bytebuddy.', 'org.primefaces.', 'org.omnifaces.',
    'com.zaxxer.', 'io.quarkus.', 'io.netty.', 'io.smallrye.', 'com.fasterxml.', 'org.slf4j.', 'ch.qos.', 'kotlin.', 'scala.', 'groovy.',
    'org.codehaus.', 'org.aspectj.', 'com.google.', 'io.micrometer.', 'reactor.', 'org.postgresql.', 'oracle.', 'com.microsoft.',
    'com.mysql.', 'org.mariadb.', 'org.h2.', 'org.hsqldb.', 'org.junit.'],
  ignoreHibernateAccess: true,
  // Instantané de l'état des associations après chaque opération (cycle de vie des entités)
  snapshots: true,
  accessStacks: false,
  loadStacks: false,
  stackDepth: 80,
};

let config = loadConfig();
const agents = new Map();
const store = new EventStore(DB_FILE, { maxEvents: MAX_EVENTS });
const readers = new Readers(DB_FILE, Number(process.env.READERS || 2));
const profiles = new Profiles(PROFILES_DIR, onProfilesChanged);
let rulesSignature = rulesSig();

function rulesSig() {
  return JSON.stringify(profiles.list().map((p) => [p.name, p.enabled, p.appliesTo, p.tagRules]));
}

/** Profils modifiés (UI ou fichier) : diffusion à l'UI ; si les règles de tags ont changé, recalcul sur l'historique. */
function onProfilesChanged() {
  broadcast('profiles', profilesState());
  const sig = rulesSig();
  if (sig === rulesSignature) return;
  rulesSignature = sig;
  store
    .retag((agentId) => profiles.rulesFor(agents.get(agentId) || { id: agentId }))
    .then((changed) => {
      if (changed === false) return;
      console.log(`tag rules: ${changed} events retagged`);
      broadcast('reload', stats());
    })
    .catch((e) => console.error('retag:', e));
}

function stats() {
  return { events: store.count, requests: store.requestCount };
}

// Requêtes modifiées depuis la dernière notification : l'UI ne recharge que ce qu'elle affiche
const dirty = new Set();
let dirtyTimer = null;
function markDirty(keys) {
  for (const k of keys) dirty.add(k);
  dirtyTimer ??= setTimeout(() => {
    dirtyTimer = null;
    const keys = dirty.size > 500 ? null : [...dirty];
    dirty.clear();
    broadcast('changed', { ...stats(), keys });
  }, 400);
}

// N+1 probables de chaque requête, recalculés en arrière-plan quand elle reçoit des événements
// (à chaque fois pour les petites, quand elles ont grossi de 25 % pour les très grosses)
let nplusBusy = false;
setInterval(async () => {
  if (nplusBusy) return;
  let budget = 300_000;
  const keys = [];
  for (const r of store.staleNplus(200)) {
    const due = r.done || r.n < 2000 || r.n > r.nplusN * 1.25;
    if (!due || r.n > budget) continue;
    budget -= r.n;
    keys.push(r.key);
  }
  if (!keys.length) return;
  nplusBusy = true;
  try {
    store.setNplus(await readers.call('nplus', { keys }));
    markDirty(keys);
  } catch (e) {
    console.error('nplus:', e.message);
  } finally {
    nplusBusy = false;
  }
}, 1500);

function profilesState() {
  return { profiles: profiles.list(), handlers: HANDLERS };
}
const clients = new Set();
// Agents à qui le prochain poll répond { detach: true }
const pendingDetach = new Set();

function loadConfig() {
  try {
    const saved = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    // Les types ajoutés depuis la sauvegarde prennent leur valeur par défaut
    return { ...DEFAULT_CONFIG, ...saved, types: { ...DEFAULT_CONFIG.types, ...saved.types } };
  } catch {
    return structuredClone(DEFAULT_CONFIG);
  }
}

function saveConfig() {
  try {
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
  } catch (e) {
    console.warn('cannot save config:', e.message);
  }
}

function broadcast(type, data) {
  const msg = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) res.write(msg);
}

function agentList() {
  const now = Date.now();
  return [...agents.values()].map((a) => ({
    id: a.id,
    name: a.name,
    startedAt: a.startedAt,
    lastSeen: a.lastSeen,
    host: a.host,
    pid: a.pid,
    online: now - a.lastSeen < 5000,
    detaching: pendingDetach.has(a.id),
    // Profils appliqués et état des sondes (classes instrumentées par sonde)
    profiles: profiles.forAgent(a).map((p) => p.name),
    requestedProfiles: a.profiles || null,
    specVersion: a.specVersion || null,
    specPending: a.specVersion !== profiles.specFor(a).version,
    specError: a.specError || null,
    probes: a.probes || {},
    queued: a.queued,
    dropped: a.dropped,
    received: a.received,
    entityCount: a.entities.length,
  }));
}

function knownEntities() {
  const all = new Set();
  for (const a of agents.values()) for (const e of a.entities) all.add(e);
  return [...all].sort();
}

// Diffusion périodique de l'état des agents (en ligne / hors ligne, compteurs)
let lastAgentsJson = '';
function broadcastAgents() {
  const list = agentList();
  const json = JSON.stringify(list);
  if (json !== lastAgentsJson) {
    lastAgentsJson = json;
    broadcast('agents', { agents: list, entities: knownEntities() });
  }
}
setInterval(broadcastAgents, 1000);

/** Lance le jar de l'agent en ligne de commande (list / attach). */
function runAgentJar(args, timeout) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(AGENT_JAR))
      return reject(new Error(`jar de l'agent introuvable : ${AGENT_JAR} (cd agent && mvn package, ou variable AGENT_JAR)`));
    execFile(JAVA, ['-jar', AGENT_JAR, ...args], { timeout, windowsHide: true }, (err, stdout, stderr) => {
      if (!err) return resolve(stdout);
      const detail = (stderr || '').trim().split(/\r?\n/).filter(Boolean).pop();
      if (err.code === 'ENOENT') return reject(new Error(`java introuvable (${JAVA}) : définir JAVA_HOME ou JAVA vers un JDK`));
      if (err.killed) return reject(new Error(`délai dépassé (${timeout / 1000} s)`));
      reject(new Error(detail || err.message));
    });
  });
}

const localHost = os.hostname().toLowerCase().split('.')[0];

/** Agent en ligne branché sur cette JVM locale (même pid, même machine). */
function agentForPid(pid) {
  for (const a of agents.values()) {
    const [host, apid] = a.id.split(':');
    if (String(a.pid || apid) === pid && (a.host || host).toLowerCase().split('.')[0] === localHost && Date.now() - a.lastSeen < 5000)
      return a;
  }
  return null;
}

// Keep-alive SSE
setInterval(() => {
  for (const res of clients) res.write(': ping\n\n');
}, 15000);

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function send(res, status, body, headers = {}) {
  const data = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
  res.end(data);
}

function addEvents(agentId, batch) {
  if (!batch.length) return;
  markDirty(store.add(agentId, batch, profiles.rulesFor(agents.get(agentId) || { id: agentId })));
}

/** Réponse d'une lecture faite par un worker (texte JSON déjà assemblé, ou objet). */
async function query(res, op, params) {
  let result;
  try {
    result = await readers.call(op, params);
  } catch (e) {
    console.error(`query ${op}:`, e.message);
    return send(res, 500, { error: /string length/i.test(e.message) ? 'résultat trop volumineux' : e.message });
  }
  if (result == null) return send(res, 404, { error: 'introuvable' });
  return send(res, 200, result);
}

// Filet de sécurité : une promesse rejetée non interceptée ne doit pas arrêter le serveur de collecte
process.on('unhandledRejection', (e) => console.error('unhandled rejection:', e));

const flag = (v, def) => (v == null ? def : v === '1' || v === 'true');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
};

function serveStatic(req, res, pathname) {
  let file = path.normalize(path.join(UI_DIR, decodeURIComponent(pathname)));
  if (!file.startsWith(path.normalize(UI_DIR))) return send(res, 403, { error: 'forbidden' });
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(UI_DIR, 'index.html');
  if (!fs.existsSync(file)) {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('UI non compilée : cd ui && npm install && npm run build (ou npm run dev pour le mode développement)');
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  if (req.method === 'OPTIONS') return res.writeHead(204).end();

  try {
    if (p === '/api/agent/poll' && req.method === 'POST') {
      const b = await readBody(req);
      const isNew = !agents.has(b.agent);
      const a = agents.get(b.agent) || { id: b.agent, received: 0, entities: [] };
      Object.assign(a, {
        name: b.name || b.agent,
        host: b.host,
        pid: b.pid,
        startedAt: b.startedAt,
        lastSeen: Date.now(),
        queued: b.queued || 0,
        dropped: b.dropped || 0,
        profiles: Array.isArray(b.profiles) && b.profiles.length ? b.profiles : null,
        specVersion: b.specVersion || null,
        specError: b.specError || null,
        probes: b.probes || {},
      });
      if (Array.isArray(b.entities)) a.entities = b.entities;
      agents.set(b.agent, a);
      if (isNew) console.log(`agent connected: ${a.name} (${a.id})`);
      if (pendingDetach.has(a.id)) return send(res, 200, { ...config, recording: false, detach: true });
      const spec = profiles.specFor(a);
      const body = { ...config, ...profiles.settingsFor(a, config), specVersion: spec.version };
      if (b.specVersion !== spec.version) {
        body.instrumentation = spec;
        console.log(`agent ${a.name}: probes ${spec.version} (${spec.probes.length}) from ${profiles.forAgent(a).map((p) => p.name).join(', ') || 'aucun profil'}`);
      }
      return send(res, 200, body);
    }

    if (p === '/api/agent/bye' && req.method === 'POST') {
      const b = await readBody(req);
      const a = agents.get(b.agent);
      console.log(`agent detached: ${a?.name || b.agent}`);
      agents.delete(b.agent);
      pendingDetach.delete(b.agent);
      broadcastAgents();
      return send(res, 200, { ok: true });
    }

    if (p === '/api/jvms' && req.method === 'GET') {
      const list = JSON.parse(await runAgentJar(['list'], 20_000));
      const jvms = list.map((j) => {
        const a = agentForPid(j.pid);
        return { ...j, agent: a ? a.id : null };
      });
      return send(res, 200, { host: localHost, jvms });
    }

    if (p === '/api/jvms/attach' && req.method === 'POST') {
      const b = await readBody(req);
      const pid = String(b.pid || '');
      if (!/^\d+$/.test(pid)) return send(res, 400, { error: 'pid invalide' });
      if (agentForPid(pid)) return send(res, 409, { error: 'un agent est déjà attaché à cette JVM' });
      const name = String(b.name || '').replace(/[;=]/g, ' ').trim();
      const chosen = (Array.isArray(b.profiles) ? b.profiles : []).filter((n) => /^[\w.-]+$/.test(n));
      const args = `server=http://127.0.0.1:${PORT}${name ? `;name=${name}` : ''}${chosen.length ? `;profiles=${chosen.join(',')}` : ''}`;
      console.log(`attaching agent to pid ${pid}...`);
      const out = await runAgentJar([pid, args], 120_000);
      return send(res, 200, { ok: true, message: out.trim() });
    }

    if (p === '/api/agents/detach' && req.method === 'POST') {
      const b = await readBody(req);
      const a = agents.get(b.id);
      if (!a) return send(res, 404, { error: 'agent inconnu' });
      if (Date.now() - a.lastSeen < 5000) {
        pendingDetach.add(a.id);
      } else {
        // Hors ligne : il ne pollera plus, on l'oublie simplement
        agents.delete(a.id);
      }
      broadcastAgents();
      return send(res, 200, { ok: true });
    }

    if (p === '/api/agent/events' && req.method === 'POST') {
      const b = await readBody(req);
      const batch = Array.isArray(b.events) ? b.events : [];
      const a = agents.get(b.agent);
      if (a) {
        a.received += batch.length;
        a.lastSeen = Date.now();
      }
      addEvents(b.agent, batch);
      return send(res, 200, { ok: true });
    }

    if (p === '/api/state' && req.method === 'GET') {
      return send(res, 200, { config, agents: agentList(), entities: knownEntities(), stats: stats() });
    }

    const q = url.searchParams;
    if (p === '/api/requests' && req.method === 'GET') {
      return query(res, 'requests', {
        q: q.get('q') || '',
        hideEmpty: flag(q.get('hideEmpty'), true),
        problems: flag(q.get('problems'), false),
        limit: Math.min(Number(q.get('limit') || 300), 5000),
        offset: Number(q.get('offset') || 0),
      });
    }
    if (p === '/api/request' && req.method === 'GET') {
      return query(res, 'request', { key: q.get('key') || '', limit: Number(q.get('limit') || 100_000) });
    }
    if (p === '/api/event' && req.method === 'GET') return query(res, 'event', { key: q.get('key') || '', id: q.get('id') || '' });
    if (p === '/api/entities' && req.method === 'GET') return query(res, 'entities', {});
    if (p === '/api/timeline' && req.method === 'GET')
      return query(res, 'timeline', {
        before: q.get('before'),
        after: q.get('after'),
        at: q.get('at'),
        limit: Number(q.get('limit') || 500),
        types: (q.get('types') || '').split(',').filter(Boolean),
        agent: q.get('agent') || '',
        q: q.get('q') || '',
        entity: q.get('entity') || '',
      });
    if (p === '/api/entities/type' && req.method === 'GET') {
      return query(res, 'entityType', { entity: q.get('entity') || '', filter: q.get('filter') || '', limit: Number(q.get('limit') || 500) });
    }
    if (p === '/api/entity' && req.method === 'GET') {
      return query(res, 'entity', { entity: q.get('entity') || '', id: q.get('id') || '', limit: Number(q.get('limit') || 20_000) });
    }
    if (p === '/api/measures' && req.method === 'GET') return query(res, 'measures', { tag: q.get('tag') || null });

    if (p === '/api/stream' && req.method === 'GET') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      res.write(`event: hello\ndata: ${JSON.stringify({ config, agents: agentList(), entities: knownEntities(), stats: stats(), ...profilesState() })}\n\n`);
      clients.add(res);
      req.on('close', () => clients.delete(res));
      return;
    }

    if (p === '/api/config' && req.method === 'PUT') {
      const b = await readBody(req);
      config = { ...config, ...b, types: { ...config.types, ...(b.types || {}) } };
      saveConfig();
      broadcast('config', config);
      return send(res, 200, config);
    }

    if (p === '/api/config/reset' && req.method === 'POST') {
      config = structuredClone(DEFAULT_CONFIG);
      saveConfig();
      broadcast('config', config);
      return send(res, 200, config);
    }

    if (p === '/api/clear' && req.method === 'POST') {
      store.clear();
      dirty.clear();
      broadcast('reload', stats());
      return send(res, 200, { ok: true });
    }

    if (p === '/api/export' && req.method === 'GET') {
      const name = `entity-profiler-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Disposition': `attachment; filename="${name}"` });
      let first = true;
      res.write('[');
      for await (const chunk of store.scan()) {
        const ok = res.write((first ? '' : ',') + chunk.join(','));
        first = false;
        if (!ok) await new Promise((r) => res.once('drain', r));
      }
      return res.end(']');
    }

    if (p === '/api/import' && req.method === 'POST') {
      const b = await readBody(req);
      const batch = Array.isArray(b) ? b : b.events || [];
      // Regroupés par agent d'origine, par tranches : le serveur reste disponible pendant l'import
      const byAgent = new Map();
      for (const e of batch) {
        if (!e || typeof e !== 'object') continue;
        const a = e.agent || 'import';
        if (!byAgent.has(a)) byAgent.set(a, []);
        byAgent.get(a).push(e);
      }
      for (const [a, list] of byAgent)
        for (let i = 0; i < list.length; i += 5000) {
          store.add(a, list.slice(i, i + 5000), profiles.rulesFor(agents.get(a) || { id: a }));
          await new Promise((r) => setImmediate(r));
        }
      broadcast('reload', stats());
      return send(res, 200, { ok: true, count: batch.length });
    }

    if (p === '/api/profiles' && req.method === 'GET') {
      return send(res, 200, profilesState());
    }

    const prof = p.match(/^\/api\/profiles\/([^/]+)$/);
    if (prof && req.method === 'PUT') {
      const name = decodeURIComponent(prof[1]);
      profiles.save(name, await readBody(req));
      onProfilesChanged();
      return send(res, 200, profilesState());
    }
    if (prof && req.method === 'DELETE') {
      profiles.remove(decodeURIComponent(prof[1]));
      onProfilesChanged();
      return send(res, 200, profilesState());
    }

    if (p.startsWith('/api/')) return send(res, 404, { error: 'not found' });
    return serveStatic(req, res, p);
  } catch (e) {
    console.error(e);
    return send(res, 400, { error: String(e.message || e) });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Entity Profiler server on http://localhost:${PORT}  (UI: ${UI_DIR})`);
  console.log(`events: ${DB_FILE} (${store.count.toLocaleString('fr-CH')}, max ${MAX_EVENTS.toLocaleString('fr-CH')})`);
});
