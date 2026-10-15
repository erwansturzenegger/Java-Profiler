// Navigation portée par l'URL : précédent / suivant du navigateur, liens partageables, ouverture en nouvel onglet.
//
//   ?req=<clé>&tab=tree&ev=<id>&q=<filtre>   requête (onglet, événement sélectionné, filtre des événements)
//   ?view=entities                           types d'entités
//   ?entity=<fqcn>                           un type d'entité : ses ids, ses propriétés
//   ?entity=<fqcn>&id=12                     une entité : cycle de vie
//   ?view=measures&tag=<tag>                 mesures par tag et par sonde
//   ?view=profiles&profile=<nom>             profils de profilage (sondes, réglages, règles de tags)
//   ?view=timeline[&entity=<fqcn>[&id=12]]   chronologie, toutes requêtes et threads confondus (at / before / after :
//                                            position ; types, agent, q : filtres ; ev : événement sélectionné)

const KEYS = ['view', 'req', 'tab', 'ev', 'q', 'entity', 'id', 'tag', 'profile', 'types', 'agent', 'at', 'before', 'after', 'changes'];

function parse(search) {
  const p = new URLSearchParams(search);
  const r = {};
  for (const k of KEYS) if (p.has(k)) r[k] = p.get(k);
  return r;
}

export function href(route) {
  const p = new URLSearchParams();
  for (const k of KEYS) if (route[k] != null && route[k] !== '') p.set(k, route[k]);
  const s = p.toString();
  return s ? `?${s}` : location.pathname;
}

class Router {
  route = $state.raw(parse(location.search));

  constructor() {
    addEventListener('popstate', () => (this.route = parse(location.search)));
  }

  /** 'requests' | 'entities' | 'type' | 'entity' | 'measures' | 'profiles' | 'timeline' */
  get view() {
    const r = this.route;
    if (r.view === 'timeline') return 'timeline';
    if (r.entity) return r.id != null ? 'entity' : 'type';
    if (r.view === 'entities' || r.view === 'measures' || r.view === 'profiles') return r.view;
    return 'requests';
  }

  href(route) {
    return href(route);
  }

  /** Nouvelle page dans l'historique (replace : met à jour l'URL sans créer d'entrée). */
  go(route, replace = false) {
    const url = href(route);
    if (url !== (location.search || location.pathname)) history[replace ? 'replaceState' : 'pushState'](null, '', url);
    this.route = route;
    if (!replace) document.querySelector('main')?.scrollTo?.(0, 0);
  }

  /** Modifie une partie de la route courante. */
  patch(changes, replace = false) {
    this.go({ ...this.route, ...changes }, replace);
  }
}

export const router = new Router();

/** Routes usuelles. */
export const to = {
  request: (req, extra = {}) => ({ req, ...extra }),
  event: (req, ev) => ({ req, ev }),
  entities: () => ({ view: 'entities' }),
  type: (entity) => ({ entity }),
  entity: (entity, id) => ({ entity, id: String(id) }),
  measures: (tag) => ({ view: 'measures', tag }),
  profiles: (profile) => ({ view: 'profiles', profile }),
  timeline: (params = {}) => ({ view: 'timeline', ...params }),
};
