// Worker de lecture : ouvre la base en lecture seule et exécute les requêtes de queries.js à la demande
// du thread principal, qui continue pendant ce temps à recevoir les événements des agents.

import { parentPort, workerData, isMainThread, Worker } from 'node:worker_threads';
import { openDb } from './store.js';
import * as queries from './queries.js';

if (!isMainThread && workerData?.reader) {
  const db = openDb(workerData.file, { readonly: true });
  parentPort.on('message', ({ id, op, params }) => {
    try {
      parentPort.postMessage({ id, result: queries[op](db, params || {}) });
    } catch (e) {
      parentPort.postMessage({ id, error: String(e.message || e) });
    }
  });
}

/** Pool de workers de lecture : chaque appel part vers le worker le moins occupé. */
export class Readers {
  constructor(file, size = 2) {
    this.file = file;
    this.seq = 0;
    this.workers = Array.from({ length: size }, () => this.#spawn());
  }

  #spawn() {
    const w = { worker: new Worker(new URL(import.meta.url), { workerData: { reader: true, file: this.file } }), pending: new Map() };
    w.worker.on('message', ({ id, result, error }) => {
      const p = w.pending.get(id);
      w.pending.delete(id);
      if (error) p?.reject(new Error(error));
      else p?.resolve(result);
    });
    // Worker tombé : les appels en cours échouent, un nouveau worker le remplace
    w.worker.on('error', (e) => console.error('reader:', e));
    w.worker.on('exit', () => {
      for (const p of w.pending.values()) p.reject(new Error('worker de lecture arrêté'));
      const i = this.workers.indexOf(w);
      if (i >= 0 && !this.closed) this.workers[i] = this.#spawn();
    });
    return w;
  }

  call(op, params) {
    const w = this.workers.reduce((a, b) => (b.pending.size < a.pending.size ? b : a));
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      w.pending.set(id, { resolve, reject });
      w.worker.postMessage({ id, op, params });
    });
  }
}
