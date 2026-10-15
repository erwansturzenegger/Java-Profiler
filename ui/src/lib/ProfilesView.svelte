<script>
  import { untrack } from 'svelte';
  import { store } from './store.svelte.js';
  import { router, to } from './router.svelte.js';
  import { TYPES, TYPE_LABEL, CAUSES } from './model.js';
  import Link from './Link.svelte';

  const SETTINGS = [
    ['callerPackages', 'Packages applicatifs', "Premier frame de ces packages = appelant « métier ». Vide partout : tout ce qui n'est pas une bibliothèque."],
    ['callerExclude', 'Exclusions d’appelant', 'Framework maison, utilitaires : visibles comme déclencheur (préfixe terminé par « . », classe ou glob).'],
    ['accessExclude', 'Propriétés ignorées (ACCESS)', 'Getters jamais enregistrés.'],
    ['libraryPackages', 'Bibliothèques supplémentaires', 'Ajoutées à la liste de la configuration.'],
  ];

  const blankProfile = () => ({ title: '', description: '', enabled: true, appliesTo: ['*'], settings: {}, probes: [], tagRules: [] });
  const clone = (p) => {
    if (!p) return blankProfile();
    const { name: _n, errors: _e, ...rest } = p;
    return structuredClone($state.snapshot(rest));
  };
  const join = (a, sep = '\n') => (Array.isArray(a) ? a.join(sep) : a || '');
  const lines = (v) => v.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
  /** Écrit une option, ou la retire si vide (JSON des profils sans clés inutiles). */
  function setOpt(obj, key, value) {
    if (value == null || value === '' || (Array.isArray(value) && !value.length)) delete obj[key];
    else obj[key] = value;
  }

  const selName = $derived(router.route.profile || store.profiles[0]?.name || null);
  const server = $derived(store.profiles.find((p) => p.name === selName) || null);

  let isNew = $state(false);
  let newName = $state('');
  let draft = $state(blankProfile());
  let lastBase = '';
  let loadedName = null;
  let editing = $state(null);
  let mode = $state('form');
  let jsonText = $state('');
  let message = $state(null);
  let fileInput;

  // Changement de profil (URL) ou mise à jour serveur : on recharge le brouillon s'il n'a pas été modifié
  $effect(() => {
    const base = JSON.stringify(clone(server));
    const name = selName;
    untrack(() => {
      if (isNew) return;
      if (name !== loadedName || JSON.stringify(draft) === lastBase) {
        draft = clone(server);
        if (name !== loadedName) {
          editing = null;
          message = null;
          mode = 'form';
        }
      }
      lastBase = base;
      loadedName = name;
    });
  });

  const dirty = $derived(isNew || JSON.stringify(draft) !== JSON.stringify(clone(server)));
  const agents = $derived(store.agents.filter((a) => a.online && a.profiles?.includes(selName)));

  function probeStats(probe) {
    let classes = 0;
    const sample = new Set();
    for (const a of agents) {
      const s = a.probes?.[`${selName}/${probe.id}`];
      if (!s) continue;
      classes += s.classes;
      for (const c of s.sample || []) sample.add(c);
    }
    return { classes, sample: [...sample] };
  }

  function matchSummary(p) {
    const parts = [];
    if (p.class?.length) parts.push(p.class.join(' | '));
    if (p.extends?.length) parts.push(`extends ${p.extends.join(' | ')}`);
    if (p.annotated?.length) parts.push(`@${p.annotated.map((a) => a.split('.').pop()).join(' | @')}`);
    const m = p.methods?.length ? p.methods.join('|') : '*';
    const args = p.params ? `(${p.params.length} args)` : p.args != null ? `(${p.args} args)` : p.minArgs ? `(≥${p.minArgs} args)` : '';
    return `${parts.join(' · ')} → ${m}${args}`;
  }

  async function save() {
    message = null;
    const name = isNew ? newName.trim() : selName;
    try {
      if (!name) throw new Error('nom du profil requis');
      if (isNew && store.profiles.some((p) => p.name === name)) throw new Error(`le profil « ${name} » existe déjà`);
      const body = $state.snapshot(draft);
      await store.saveProfile(name, body);
      lastBase = JSON.stringify(body);
      const wasNew = isNew;
      isNew = false;
      if (wasNew) router.go(to.profiles(name));
      message = { ok: true, text: 'Enregistré. Les agents concernés réinstallent leurs sondes (≤ 1 s).' };
    } catch (e) {
      message = { ok: false, text: e.message };
    }
  }

  function cancel() {
    isNew = false;
    draft = clone(server);
    editing = null;
    message = null;
  }

  async function remove() {
    if (!confirm(`Supprimer le profil « ${selName} » (fichier server/profiles/${selName}.json) ?`)) return;
    try {
      await store.deleteProfile(selName);
      router.go(to.profiles());
    } catch (e) {
      message = { ok: false, text: e.message };
    }
  }

  function startNew(base, name) {
    isNew = true;
    newName = name;
    draft = base;
    editing = null;
    mode = 'form';
    message = null;
  }

  async function toggleProfile(p) {
    if (p.name === selName && dirty) {
      draft.enabled = draft.enabled === false;
      return;
    }
    try {
      await store.saveProfile(p.name, { ...clone(p), enabled: p.enabled === false });
    } catch (e) {
      message = { ok: false, text: e.message };
    }
  }

  function addProbe() {
    const ids = new Set(draft.probes.map((p) => p.id));
    let i = draft.probes.length + 1;
    while (ids.has(`sonde-${i}`)) i++;
    draft.probes.push({ id: `sonde-${i}`, handler: 'method', class: [], methods: [], role: 'measure', tags: [] });
    editing = draft.probes.length - 1;
  }

  function removeProbe(i) {
    draft.probes.splice(i, 1);
    editing = null;
  }

  function addRule() {
    (draft.tagRules ||= []).push({ tag: '' });
  }

  // Règles d'identifiant des entités (settings.entityIds) : listes saisies séparées par des virgules
  const csv = (v) => (Array.isArray(v) ? v.join(', ') : v || '');
  const list = (text) => text.split(',').map((x) => x.trim()).filter(Boolean);

  function addIdRule() {
    ((draft.settings ||= {}).entityIds ||= []).push({ extends: [], id: '' });
  }

  function removeIdRule(i) {
    draft.settings.entityIds.splice(i, 1);
    if (!draft.settings.entityIds.length) delete draft.settings.entityIds;
  }

  function download() {
    const blob = new Blob([JSON.stringify($state.snapshot(draft), null, 2) + '\n'], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${isNew ? newName || 'profil' : selName}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  async function importFile(file) {
    try {
      const p = JSON.parse(await file.text());
      startNew(clone(p), file.name.replace(/\.json$/i, ''));
    } catch (e) {
      message = { ok: false, text: `JSON invalide : ${e.message}` };
    }
  }

  function openJson() {
    jsonText = JSON.stringify($state.snapshot(draft), null, 2);
    mode = 'json';
  }

  function applyJson() {
    try {
      draft = JSON.parse(jsonText);
      draft.probes ||= [];
      mode = 'form';
      message = null;
    } catch (e) {
      message = { ok: false, text: `JSON invalide : ${e.message}` };
    }
  }

  const triState = (v) => (v === true ? 'true' : v === false ? 'false' : '');
  const fromTri = (v) => (v === 'true' ? true : v === 'false' ? false : undefined);
  const num = (v) => (v === '' || v == null || isNaN(Number(v)) ? undefined : Number(v));
</script>

<div class="layout">
  <aside>
    <div class="side-head">
      <h1>Profils</h1>
      <button class="primary" onclick={() => startNew(blankProfile(), '')}>Nouveau</button>
    </div>
    <p class="muted small">
      Fichiers <span class="mono">server/profiles/*.json</span>. Un agent reçoit les sondes des profils actifs dont « s'applique à »
      correspond à son nom, ou ceux choisis à l'attachement.
    </p>
    <ul>
      {#each store.profiles as p (p.name)}
        <li class:sel={p.name === selName && !isNew}>
          <input type="checkbox" checked={p.name === selName && dirty && !isNew ? draft.enabled !== false : p.enabled !== false} onchange={() => toggleProfile(p)} title="Actif" />
          <Link to={to.profiles(p.name)} class="pname">
            <span class="mono">{p.name}</span>
            <span class="muted small">{p.title || ''}</span>
          </Link>
          <span class="count faint small" title="Sondes">{(p.probes || []).length}</span>
          {#if p.errors?.length}<span class="badge t-LAZY_ERROR" title={p.errors.join('\n')}>erreur</span>{/if}
        </li>
      {/each}
      {#if isNew}<li class="sel"><span class="mono">{newName || 'nouveau profil'}</span> <span class="muted small">(non enregistré)</span></li>{/if}
    </ul>
    <button class="link" onclick={() => fileInput.click()}>Importer un profil JSON…</button>
    <input bind:this={fileInput} type="file" accept=".json" hidden onchange={(e) => e.target.files[0] && importFile(e.target.files[0])} />
  </aside>

  <section class="editor">
    {#if !server && !isNew}
      <p class="muted">Aucun profil. Créez-en un avec « Nouveau ».</p>
    {:else}
      <div class="bar">
        {#if isNew}
          <input class="mono name" type="text" placeholder="nom-du-profil" bind:value={newName} aria-label="Nom du profil (nom du fichier)" />
        {:else}
          <h2 class="mono">{selName}</h2>
        {/if}
        <label class="row"><input type="checkbox" checked={draft.enabled !== false} onchange={(e) => (draft.enabled = e.target.checked)} /> actif</label>
        <div class="spacer"></div>
        {#if dirty}<span class="dirty small">Modifications non enregistrées</span>{/if}
        <button class="primary" onclick={save} disabled={!dirty}>Enregistrer</button>
        <button onclick={cancel} disabled={!dirty}>Annuler</button>
        {#if mode === 'form'}<button onclick={openJson}>JSON</button>{:else}<button onclick={() => (mode = 'form')}>Formulaire</button>{/if}
        <button onclick={download} title="Télécharger le profil">Exporter</button>
        {#if !isNew}
          <button onclick={() => startNew(clone(draft), `${selName}-copie`)}>Dupliquer</button>
          <button class="danger" onclick={remove}>Supprimer</button>
        {/if}
      </div>

      {#if message}<p class="msg" class:ok={message.ok} class:err={!message.ok}>{message.text}</p>{/if}
      {#if server?.errors?.length && !isNew}
        <p class="msg err">Profil ignoré tant qu'il est invalide : {server.errors.join(' ; ')}</p>
      {/if}

      {#if mode === 'json'}
        <textarea class="mono json" rows="30" bind:value={jsonText}></textarea>
        <div class="actions"><button class="primary" onclick={applyJson}>Appliquer au formulaire</button></div>
      {:else}
        <div class="grid2">
          <label>Titre <input type="text" bind:value={draft.title} /></label>
          <label>
            S'applique aux agents (noms, globs)
            <input type="text" class="mono" value={join(draft.appliesTo, ', ')} onchange={(e) => setOpt(draft, 'appliesTo', lines(e.target.value))} placeholder="* (tous)" />
          </label>
          <label class="wide">Description <textarea rows="2" bind:value={draft.description}></textarea></label>
        </div>

        <p class="small muted agents">
          {#if agents.length}
            Appliqué à : {#each agents as a, i}{i ? ', ' : ''}<b>{a.name}</b>{#if a.specPending} <span class="faint">(installation…)</span>{/if}{#if a.specError} <span class="err">({a.specError})</span>{/if}{/each}
          {:else}
            Aucun agent en ligne n'utilise ce profil.
          {/if}
        </p>

        <h3>Sondes <span class="muted small">— ce que l'agent instrumente</span></h3>
        <div class="scroll-x">
        <table class="grid probes">
          <thead>
            <tr><th></th><th>Id</th><th>Handler</th><th>Cible</th><th>Rôle</th><th>Tags</th><th class="num" title="Classes instrumentées par les agents en ligne">Classes</th><th></th></tr>
          </thead>
          <tbody>
            {#each draft.probes as p, i (i)}
              {@const st = probeStats(p)}
              <tr class:off={p.enabled === false} class:open={editing === i}>
                <td><input type="checkbox" checked={p.enabled !== false} onchange={(e) => setOpt(p, 'enabled', e.target.checked ? undefined : false)} title="Sonde active" /></td>
                <td class="mono">{p.id}</td>
                <td class="mono small">{p.handler || 'method'}</td>
                <td class="mono small target" title={matchSummary(p)}>{p.label ? `${p.label} · ` : ''}{matchSummary(p)}</td>
                <td class="small">{(p.handler || 'method') === 'method' ? (p.role === 'load' ? 'chargement' : 'mesure') : ''}</td>
                <td>{#each p.tags || [] as t}<span class="tag">#{t}</span>{/each}{#if p.propagate}<span class="faint small" title="Tags propagés aux événements enfants"> ↳</span>{/if}</td>
                <td class="num" title={st.sample.join('\n')}>
                  {#if agents.length}<span class:zero={st.classes === 0 && p.enabled !== false}>{st.classes}</span>{/if}
                </td>
                <td class="nowrap">
                  <button class="link" onclick={() => (editing = editing === i ? null : i)}>{editing === i ? 'Fermer' : 'Modifier'}</button>
                  <button class="link" onclick={() => removeProbe(i)}>Retirer</button>
                </td>
              </tr>
            {:else}
              <tr><td colspan="8" class="muted">Aucune sonde.</td></tr>
            {/each}
          </tbody>
        </table>
        </div>
        <div class="actions"><button onclick={addProbe}>+ Ajouter une sonde</button></div>
        {#if editing != null && draft.probes[editing]}
          {@const p = draft.probes[editing]}
          {@const st = probeStats(p)}
          <div class="card">
            <div class="card-head">
              <h4>Sonde <span class="mono">{p.id}</span></h4>
              <button class="link" onclick={() => (editing = null)}>Fermer</button>
            </div>
            <div class="pform">
                      <label>Id <input type="text" class="mono" bind:value={p.id} /></label>
                      <label>
                        Handler
                        <select value={p.handler || 'method'} onchange={(e) => (p.handler = e.target.value)}>
                          {#each Object.keys(store.handlers) as h}<option value={h}>{h}</option>{/each}
                        </select>
                      </label>
                      <p class="wide help small muted">{store.handlers[p.handler || 'method']?.help}</p>
                      <label class="wide">Description <input type="text" value={p.description || ''} onchange={(e) => setOpt(p, 'description', e.target.value.trim())} /></label>

                      <fieldset class="wide">
                        <legend>Classes (critères combinés en ET)</legend>
                        <label>Nom (FQCN ou glob *, un par ligne) <textarea rows="2" class="mono" value={join(p.class)} onchange={(e) => setOpt(p, 'class', lines(e.target.value))} placeholder="com.acme.*Service"></textarea></label>
                        <label>Hérite de / implémente (FQCN) <textarea rows="2" class="mono" value={join(p.extends)} onchange={(e) => setOpt(p, 'extends', lines(e.target.value))}></textarea></label>
                        <label>Annotée par (FQCN) <textarea rows="2" class="mono" value={join(p.annotated)} onchange={(e) => setOpt(p, 'annotated', lines(e.target.value))} placeholder="jakarta.ejb.Stateless"></textarea></label>
                        <label class="row"><input type="checkbox" checked={!!p.concrete} onchange={(e) => setOpt(p, 'concrete', e.target.checked || undefined)} /> classes concrètes seulement</label>
                      </fieldset>

                      <fieldset class="wide">
                        <legend>Méthodes</legend>
                        <label>Noms (globs, un par ligne ; vide = toutes ; &lt;init&gt; = constructeurs) <textarea rows="2" class="mono" value={join(p.methods)} onchange={(e) => setOpt(p, 'methods', lines(e.target.value))} placeholder="find*"></textarea></label>
                        <div class="inline">
                          <label>Nb d'arguments <input type="number" min="0" value={p.args ?? ''} onchange={(e) => setOpt(p, 'args', num(e.target.value))} /></label>
                          <label>Au moins <input type="number" min="0" value={p.minArgs ?? ''} onchange={(e) => setOpt(p, 'minArgs', num(e.target.value))} /></label>
                          <label>Publique <select value={triState(p.public)} onchange={(e) => setOpt(p, 'public', fromTri(e.target.value))}><option value="">indifférent</option><option value="true">oui</option><option value="false">non</option></select></label>
                          <label>Statique <select value={triState(p.static)} onchange={(e) => setOpt(p, 'static', fromTri(e.target.value))}><option value="">indifférent</option><option value="true">oui</option><option value="false">non</option></select></label>
                          <label class="row"><input type="checkbox" checked={!!p.nonVoid} onchange={(e) => setOpt(p, 'nonVoid', e.target.checked || undefined)} /> retour non void</label>
                        </div>
                        <label>Types des paramètres (un par argument, alternatives « | », « * » = quelconque) <textarea rows="2" class="mono" value={p.params ? p.params.join('\n') : ''} onchange={(e) => setOpt(p, 'params', e.target.value.split('\n').map((s) => s.trim()).filter(Boolean))}></textarea></label>
                      </fieldset>

                      <fieldset class="wide">
                        <legend>Mesure</legend>
                        <div class="inline">
                          <label>Tags (séparés par des virgules) <input type="text" class="mono" value={join(p.tags, ', ')} onchange={(e) => setOpt(p, 'tags', lines(e.target.value))} /></label>
                          <label class="row" title="Les événements produits pendant l'exécution (LOAD, SQL...) héritent des tags"><input type="checkbox" checked={!!p.propagate} onchange={(e) => setOpt(p, 'propagate', e.target.checked || undefined)} /> propager aux enfants</label>
                        </div>
                        {#if (p.handler || 'method') === 'method'}
                          <div class="inline">
                            <label>Libellé <input type="text" value={p.label || ''} onchange={(e) => setOpt(p, 'label', e.target.value.trim())} placeholder="Classe.méthode" /></label>
                            <label>
                              Rôle
                              <select value={p.role || 'measure'} onchange={(e) => (p.role = e.target.value)}>
                                <option value="measure">mesure (transparente pour la cause des chargements)</option>
                                <option value="load">chargement voulu (findById, initialize…)</option>
                              </select>
                            </label>
                          </div>
                        {/if}
                      </fieldset>

                      {#if agents.length && st.sample.length}
                        <p class="wide small muted">Classes instrumentées ({st.classes}) : <span class="mono">{st.sample.join(', ')}</span></p>
                      {:else if agents.length && dirty}
                        <p class="wide small muted">Enregistrez pour voir les classes instrumentées.</p>
                      {/if}
                    </div>
          </div>
        {/if}


        <h3>Règles de tags <span class="muted small">— appliquées par le serveur aux événements reçus (et à l'historique)</span></h3>
        <div class="scroll-x">
        <table class="grid rules">
          <thead>
            <tr><th>Tag</th><th>Types</th><th>Entité</th><th>Propriété</th><th>Appelant</th><th>Sonde</th><th>Texte</th><th>Cause</th><th class="num">Durée ≥ ms</th><th title="Seulement les événements en erreur">Err.</th><th></th></tr>
          </thead>
          <tbody>
            {#each draft.tagRules || [] as r, i (i)}
              <tr>
                <td><input type="text" class="mono" bind:value={r.tag} placeholder="tag" /></td>
                <td>
                  <select multiple size="2" value={r.types || []} onchange={(e) => setOpt(r, 'types', [...e.target.selectedOptions].map((o) => o.value))}>
                    {#each TYPES as t}<option value={t}>{TYPE_LABEL[t]}</option>{/each}
                  </select>
                </td>
                {#each ['entity', 'prop', 'caller', 'probe', 'text'] as k}
                  <td><input type="text" class="mono" value={r[k] || ''} onchange={(e) => setOpt(r, k, e.target.value.trim())} placeholder={k === 'text' ? 'contient…' : 'glob'} /></td>
                {/each}
                <td>
                  <select value={r.cause || ''} onchange={(e) => setOpt(r, 'cause', e.target.value)}>
                    <option value="">—</option>
                    {#each Object.entries(CAUSES) as [k, c]}<option value={k}>{c.label}</option>{/each}
                  </select>
                </td>
                <td><input type="number" min="0" class="num-in" value={r.minDur ?? ''} onchange={(e) => setOpt(r, 'minDur', num(e.target.value))} /></td>
                <td><input type="checkbox" checked={!!r.error} onchange={(e) => setOpt(r, 'error', e.target.checked || undefined)} /></td>
                <td><button class="link" onclick={() => draft.tagRules.splice(i, 1)}>Retirer</button></td>
              </tr>
            {:else}
              <tr><td colspan="11" class="muted">Aucune règle. Exemple : tag « sql-lent » sur les SQL de plus de 100 ms.</td></tr>
            {/each}
          </tbody>
        </table>
        </div>
        <div class="actions"><button onclick={addRule}>+ Ajouter une règle</button></div>

        <h3>Identifiant des entités <span class="muted small">— où l'agent lit l'id d'une instance (cible d'une opération, getter, référence)</span></h3>
        <p class="small muted">
          Sans règle : champ annoté <span class="mono">@Id</span> / <span class="mono">@EmbeddedId</span>, sinon <span class="mono">getId()</span>.
          Id : un nom de champ (lu directement, recommandé) ou <span class="mono">méthode()</span>. Première règle qui correspond.
          Une classe désignée ici est traitée comme une entité même sans <span class="mono">@Entity</span> (mapping XML).
          Hibernate fournit lui-même l'id des LOAD, proxies et collections : la règle doit désigner le même identifiant persistant.
        </p>
        <div class="scroll-x">
        <table class="grid rules">
          <thead>
            <tr><th>Classes (FQCN, nom simple ou glob)</th><th>ou héritant de (classe / interface)</th><th>Id</th><th></th></tr>
          </thead>
          <tbody>
            {#each draft.settings?.entityIds || [] as r, i (i)}
              <tr>
                <td><input type="text" class="mono" value={csv(r.class)} onchange={(e) => setOpt(r, 'class', list(e.target.value))} placeholder="com.acme.model.*" /></td>
                <td><input type="text" class="mono" value={csv(r.extends)} onchange={(e) => setOpt(r, 'extends', list(e.target.value))} placeholder="com.acme.model.AbstractEntity" /></td>
                <td><input type="text" class="mono" value={r.id || ''} onchange={(e) => setOpt(r, 'id', e.target.value.trim())} placeholder="id, pk ou getKey()" /></td>
                <td><button class="link" onclick={() => removeIdRule(i)}>Retirer</button></td>
              </tr>
            {:else}
              <tr><td colspan="4" class="muted">Aucune règle : @Id, sinon getId().</td></tr>
            {/each}
          </tbody>
        </table>
        </div>
        <div class="actions"><button onclick={addIdRule}>+ Ajouter une règle d'identifiant</button></div>

        <h3>Réglages <span class="muted small">— ajoutés à ceux de la configuration pour les agents de ce profil</span></h3>
        <div class="grid2">
          {#each SETTINGS as [k, label, help]}
            <label>
              {label}
              <textarea rows="3" class="mono" value={join(draft.settings?.[k])} onchange={(e) => setOpt((draft.settings ||= {}), k, lines(e.target.value))}></textarea>
              <span class="small faint">{help}</span>
            </label>
          {/each}
        </div>
      {/if}
    {/if}
  </section>
</div>

<style>
  .layout {
    display: grid;
    grid-template-columns: 280px 1fr;
    height: 100%;
    min-height: 0;
    background: var(--panel);
  }

  aside {
    border-right: 1px solid var(--border);
    padding: 12px 14px;
    overflow: auto;
  }

  .side-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
  }

  h1 {
    font-size: 20px;
    margin: 6px 0;
  }

  h2 {
    font-size: 16px;
    margin: 0;
  }

  h3 {
    font-size: 14px;
    margin: 22px 0 6px;
  }

  aside ul {
    list-style: none;
    padding: 0;
    margin: 10px 0;
  }

  aside li {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 6px;
    border-radius: 6px;
  }

  aside li.sel {
    background: var(--sel);
  }

  aside :global(a.pname) {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
  }

  aside :global(a.pname span) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .editor {
    padding: 12px 20px 40px;
    overflow: auto;
    min-width: 0;
  }

  .bar {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    position: sticky;
    top: -12px;
    background: var(--panel);
    padding: 8px 0;
    border-bottom: 1px solid var(--border);
    z-index: 2;
  }

  .bar .name {
    width: 240px;
  }

  .spacer {
    flex: 1;
  }

  .dirty {
    color: var(--accent);
  }

  .danger {
    color: var(--t-lazy);
  }

  .msg {
    padding: 6px 10px;
    border-radius: 6px;
    font-size: 13px;
  }

  .msg.ok {
    background: color-mix(in srgb, var(--t-util) 12%, transparent);
  }

  .msg.err,
  .err {
    color: var(--t-lazy);
  }

  .msg.err {
    background: color-mix(in srgb, var(--t-lazy) 10%, transparent);
  }

  .grid2 {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px 16px;
    margin-top: 10px;
  }

  .grid2 label,
  .pform label {
    display: flex;
    flex-direction: column;
    gap: 3px;
    font-size: 12px;
    color: var(--muted);
  }

  .wide {
    grid-column: 1 / -1;
  }

  label.row {
    flex-direction: row !important;
    align-items: center;
    gap: 6px;
  }

  .agents {
    margin: 10px 0 0;
  }

  .probes .target {
    max-width: 520px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  tr.off td {
    opacity: 0.5;
  }

  tr.open td {
    background: var(--sel);
  }

  .zero {
    color: var(--t-lazy);
    font-weight: 600;
  }

  .nowrap {
    white-space: nowrap;
  }

  .scroll-x {
    overflow-x: auto;
  }

  .card {
    margin-top: 10px;
    border: 1px solid var(--sel-border);
    border-radius: 8px;
    background: var(--panel-2);
    padding: 8px 12px;
  }

  .card-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
  }

  h4 {
    margin: 4px 0;
    font-size: 13px;
  }

  .pform textarea,
  .pform input[type='text'],
  .pform select {
    width: 100%;
  }

  .pform {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px 16px;
    padding: 8px 4px;
  }

  .help {
    margin: 0;
  }

  fieldset {
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 6px 10px 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  legend {
    font-size: 12px;
    font-weight: 600;
    color: var(--text);
  }

  .inline {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: flex-end;
  }

  .inline input[type='number'] {
    width: 80px;
  }

  .rules input[type='text'] {
    min-width: 80px;
  }

  .rules .num-in {
    width: 70px;
  }

  .rules select[multiple] {
    min-width: 110px;
  }

  .actions {
    margin-top: 8px;
  }

  .json {
    width: 100%;
    margin-top: 10px;
  }

  .link {
    min-height: 0;
    box-shadow: none;
    font-weight: inherit;
    border: none;
    background: none;
    color: var(--muted);
    text-decoration: underline;
    font-size: 12px;
    padding: 0 4px;
  }

  .small {
    font-size: 12px;
  }

  @media (max-width: 900px) {
    .layout {
      grid-template-columns: 1fr;
    }

    aside {
      border-right: none;
      border-bottom: 1px solid var(--border);
    }

    .grid2,
    .pform {
      grid-template-columns: 1fr;
    }
  }
</style>
