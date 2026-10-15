# Entity Profiler

Profileur Java, en direct : chargement, initialisation lazy et accès aux propriétés des entités (Hibernate/JPA), requêtes HTTP, SQL, et toute méthode que vous voulez mesurer.

L'agent ne connaît **aucune classe applicative**. Ce qu'il instrumente est décrit dans des **profils** gérés par le serveur (`server/profiles/*.json`), modifiables depuis l'UI et appliqués à chaud. Le même agent sert donc à n'importe quelle application : on active les profils de ses technologies (Hibernate, web) et on écrit un profil pour son code (méthodes à mesurer, tags).

```
 JVM (WildFly / Tomcat…)                        Node                     Navigateur
┌────────────────────────────┐   HTTP POST    ┌──────────────┐   SSE   ┌──────────────┐
│ entity-profiler-agent.jar  │ ─────────────▶ │  server.js   │ ──────▶ │  UI Svelte   │
│ (ByteBuddy, -javaagent ou  │ ◀───────────── │  (port 7070) │ ◀────── │  (pages à la │
│  attachement à chaud)      │  config / 1 s  └──────┬───────┘ GET/PUT │   demande)   │
└────────────────────────────┘                       │ SQLite          └──────────────┘
                                              server/data/events.db
```

## Démarrage rapide

```bash
# 1. Agent
cd agent && mvn package                  # -> agent/target/entity-profiler-agent.jar

# 2. Serveur + UI
cd ui && npm install && npm run build    # UI compilée dans ui/dist, servie par le serveur
cd ../server && node server.js           # http://localhost:7070  (Node ≥ 22.13, aucune dépendance npm)

# 3a. Brancher l'agent au démarrage du serveur d'app (standalone.conf / JAVA_OPTS)
-javaagent:/chemin/entity-profiler-agent.jar=server=http://localhost:7070;name=mon-app-dev

# 3b. … ou à chaud, sans redémarrer (JDK requis)
java -jar entity-profiler-agent.jar <pid> "server=http://localhost:7070;name=mon-app-dev"
java -jar entity-profiler-agent.jar list   # JVM locales (pid + nom)
```

### Attacher / détacher depuis l'UI

Le bouton **JVM…** de l'en-tête liste les JVM locales du serveur (même utilisateur) avec leur PID. **Attacher** charge l'agent à chaud, avec les profils cochés (sinon ceux qui s'appliquent à son nom) ; **Détacher** retire l'instrumentation : les classes retrouvent leur bytecode d'origine et l'agent s'arrête (< 1 s). Une JVM détachée peut être rattachée plus tard.

- Le serveur lance `java -jar agent/target/entity-profiler-agent.jar` : il lui faut un **JDK** (`JAVA_HOME`, ou `JAVA=/chemin/java`). Autre emplacement du jar : `AGENT_JAR=/chemin/entity-profiler-agent.jar`.
- L'attachement à chaud n'est possible que sur la machine du serveur. Le détachement fonctionne pour tout agent connecté, y compris démarré par `-javaagent`.
- Le jar reste dans le classpath système de la JVM après détachement (la JVM ne permet pas de décharger un agent) : pour changer de version de l'agent, redémarrez la JVM.
- Une JVM lancée avec `-XX:+DisableAttachMechanism` refuse l'attachement.

Pour développer l'UI : `npm run dev` dans `ui/` (port 5173, l'API est proxifiée vers 7070).

Pour voir l'UI sans agent : bouton Importer, puis `docs/sample-events.json` (application de démonstration : `demo.Customer`, `demo.Order`, plusieurs threads).

### Stockage et volumes

Les événements sont stockés par le serveur dans une base **SQLite** (`node:sqlite`, intégré à Node ≥ 22.13 : rien à installer), fichier `server/data/events.db` en mode WAL. L'historique survit aux redémarrages du serveur. Le navigateur ne charge que ce qu'il affiche : une page de la liste des requêtes, la requête ouverte, ou le résultat d'une agrégation calculée par le serveur. Le flux SSE ne transporte que des notifications (« ces requêtes ont changé »), et les vues ouvertes se rechargent en différé.

- Réception : plus de 30 000 événements/s. Les lectures lourdes tournent dans des workers et ne bloquent jamais la réception.
- Les pages Entités et Mesures lisent des compteurs tenus à jour à l'écriture : quelques dizaines de ms quel que soit le volume. Liste et détail d'une requête : quelques ms. Vue d'un type : < 1 s pour 100 000 références.
- Au-delà de `MAX_EVENTS` (2 000 000 par défaut), les événements les plus anciens sont supprimés et les compteurs décomptés.
- Variables : `DB_FILE` (emplacement de la base), `MAX_EVENTS`, `READERS` (workers de lecture, 2 par défaut).
- Une requête de plus de 100 000 événements n'en charge que les 100 000 premiers (signalé dans l'en-tête). L'arbre et les listes s'affichent par tranches.
- Les listes d'événements arrivent sans les piles d'appels, qui font l'essentiel du volume. La pile d'un événement est chargée quand on l'ouvre.
- Une réponse est limitée à 64 Mo. Au-delà, elle est tronquée et c'est signalé.
- **Vider** supprime l'historique de la base. **Exporter** produit un flux JSON de toute la base. **Importer** ajoute un export à l'historique.

## Profils, sondes et tags

Un profil est un fichier `server/profiles/<nom>.json`, éditable dans la page **Profils** de l'UI (formulaire ou JSON) ou directement sur disque (rechargé automatiquement). Toute modification est envoyée aux agents concernés au poll suivant (< 1 s) : l'agent retire l'ancienne instrumentation, restaure le bytecode d'origine, puis installe les nouvelles sondes, y compris sur les classes déjà chargées.

Profils fournis :

| Profil        | Contenu                                                                                                        |
| ------------- | -------------------------------------------------------------------------------------------------------------- |
| `hibernate`   | LOAD, PROXY, COLLECTION, SQL, SESSION, requêtes, LazyInitializationException, getters des entités. Règles de tags `sql-lent` (≥ 100 ms) et `lazy-implicite` |
| `web`         | Requêtes HTTP (javax / jakarta), expression EL en cours                                                        |
| `exemple-app` | Désactivé. Modèle pour une autre application : services mesurés, repositories, batch avec tags propagés       |

```json
{
  "title": "Mon application",
  "enabled": true,
  "appliesTo": ["mon-app*"],
  "settings": { "callerPackages": ["com.acme."], "callerExclude": ["com.acme.framework."] },
  "probes": [
    { "id": "services", "class": ["com.acme.*Service"], "public": true, "tags": ["service"] },
    { "id": "repos", "class": ["com.acme.*Repository"], "methods": ["find*", "load*"], "role": "load", "tags": ["repository"] },
    { "id": "batch", "class": ["com.acme.batch.*Job"], "methods": ["run"], "tags": ["batch"], "propagate": true }
  ],
  "tagRules": [ { "tag": "lent", "types": ["UTIL"], "probe": "mon-app/services", "minDur": 500 } ]
}
```

- **Quels agents** : un agent reçoit les sondes des profils actifs dont `appliesTo` (globs) correspond à son nom, ou exactement ceux qu'il demande (`profiles=hibernate,web` dans ses arguments, ou cases cochées à l'attachement).
- **Sonde** : `handler` (par défaut `method`) et critères de classes combinés en ET : `class` (FQCN ou glob), `extends` (classe ou interface, sous-types inclus), `annotated`, `concrete`. Critères de méthodes : `methods` (globs, `<init>` pour les constructeurs), `args`, `minArgs`, `params` (un type par argument, `a|b`, `*`), `public`, `static`, `nonVoid`. La page Profils affiche, par sonde, le nombre de classes réellement instrumentées : 0 signale une sonde qui ne correspond à rien.
- **Handlers** : `method` (mesure générique : durée, arguments, retour, entité ciblée et son état après) ; et les handlers techniques `post-load`, `proxy-init`, `collection-init`, `session`, `query`, `lazy-collection`, `lazy-exception`, `sql`, `http-request`, `el`, `getter`. Ces derniers interprètent les objets reçus (événement Hibernate, requête servlet…) : on peut changer leurs classes cibles (autre version d'Hibernate…) sans toucher à l'agent.
- **Rôle** d'une sonde `method` : `measure` (défaut) est transparente pour la cause des chargements ; `load` rend « voulus » les chargements qu'elle déclenche (findById, initialize…).
- **Tags** : `tags` sur une sonde marque ses événements ; avec `propagate`, tout ce qui s'exécute pendant l'appel (LOAD, SQL, autres méthodes…) hérite du tag (affiché en pointillé). Les **règles de tags** (`tagRules`) sont appliquées par le serveur à la réception et recalculées sur l'historique à chaque modification : critères `types`, `entity`, `prop`, `caller`, `probe` (globs), `text` (contenu du détail), `cause` (`lazy`, `init`, `explicit`, `query`), `minDur` (ms), `error`.
- **Réglages** (`settings`) : `callerPackages`, `callerExclude`, `accessExclude`, `libraryPackages`, ajoutés à ceux de la configuration pour les agents du profil.
- **Identifiant des entités** (`settings.entityIds`) : où l'agent lit l'id d'une instance. Exemple :

  ```json
  "entityIds": [
    { "extends": ["com.acme.model.AbstractEntity"], "id": "id" },
    { "class": ["com.acme.legacy.*"], "id": "getPk()" }
  ]
  ```

  - **Critères** : `class` (FQCN, nom simple ou glob) ou `extends` (classe ou interface parente). C'est la première règle qui correspond qui s'applique, tous profils confondus.
  - **`id`** : un nom de champ, lu directement sans déclencher de code ni de chargement (recommandé), ou `méthode()`.
  - **Sans règle** : le champ annoté `@Id` / `@EmbeddedId`, sinon `getId()`.
  - **Entités sans `@Entity`** : une classe désignée par une règle est traitée comme une entité même sans annotation (mapping XML).
  - **Quand la règle sert** : seulement quand l'agent n'a que l'instance (cible d'une opération de session ou d'une méthode sondée, getter, références des instantanés). Pour les LOAD, les proxies et les clés de collection, Hibernate fournit l'id lui-même. La règle doit donc désigner l'identifiant persistant (le même que le mapping), sinon une même entité apparaîtra sous deux ids.

La page **Mesures** (`?view=measures`) agrège par tag (événements, requêtes, durées total / moyenne / p95 / max des spans tagués, erreurs) et par méthode sondée ; un tag sélectionné liste ses spans les plus lents. Dans une requête, le filtre des événements accepte `#tag`.

## Ce qui est capturé (profils `hibernate` et `web`)

| Type       | Hook                                                              | Contenu                                                                                                                                                                                                                                                        |
| ---------- | ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SQL        | `ResultSetReturnImpl.extract/execute/executeUpdate`               | Requête, durée, appelant                                                                                                                                                                                                                                       |
| LOAD       | `DefaultPostLoadEventListener.onPostLoad`                         | Chaque entité hydratée (requête, find, fetch join, proxy, collection)                                                                                                                                                                                          |
| PROXY      | `AbstractLazyInitializer.initialize()`                            | Accès à une entité non initialisée : chargement lazy, durée, propriété accédée (déduite du getter du proxy), erreur éventuelle                                                                                                                                 |
| COLLECTION | `DefaultInitializeCollectionEventListener.onInitializeCollection` | Collection lazy chargée : rôle, clé, taille, type d'élément, durée                                                                                                                                                                                             |
| LAZY_ERROR | Constructeurs de `LazyInitializationException`                    | Accès lazy impossible (entité détachée, `UninitializedSet`…), entité, id et rôle extraits du message. Entité, id et instance repris de la collection en échec (`throwLazyInitializationException`) ou du proxy en cours |
| ACCESS     | Getters des classes `@Entity` / `@MappedSuperclass`               | Propriété lue, et signale quand la valeur retournée est un proxy ou une collection non initialisés (`LAZY_PROXY`, `LAZY_COLLECTION`, `UNINITIALIZED`)                                                                                                          |
| SESSION    | `SessionImpl.find/get/refresh/merge/evict/detach/clear/close`, `Query.list()` | Chargements demandés, requêtes HQL/JPQL/Criteria/natives, détachement et instances remplacées |
| UTIL       | Sondes `method` des profils                                       | Méthode mesurée : durée, arguments décrits sans déclencher de lazy loading, retour, cible (`this` ou premier argument entité) et son état après. Porte `probe`, `label` et les tags de la sonde |

Chaque événement porte aussi les informations suivantes :

- caller : premier frame métier : dans les packages applicatifs (configuration + profils), hors exclusions. Sans package applicatif, premier frame qui n'est pas une bibliothèque (`libraryPackages`).
- trigger : la méthode appelée juste avant, par exemple `Hibernate.initialize`, `PersistentSet.iterator` ou `Seller(proxy).getName`.
- `tags` (sonde), `rtags` (règles du serveur), `scope` (tags hérités d'un span englobant).
- l'expression EL en cours (`#{bean.customer.orders}`).
- la phase JSF.
- la pile.

Les événements d'entité portent aussi :

- l'entité et son id. Pour UTIL, c'est la cible (`tentity`/`teid`) : `this` pour `customer.refetch()`, sinon le premier argument entité, proxy ou collection (liste des ids pour une collection) ;
- `inst` : l'identité JVM de l'instance (propriétaire pour COLLECTION, implémentation derrière le proxy pour PROXY) ;
- `rinst` : l'instance renvoyée par un UTIL (`findById`…) ou un `merge`, quand ce n'est pas celle passée ;
- `sess` : l'identité de la session Hibernate (LOAD, PROXY, COLLECTION, SESSION) ;
- `cause` (LOAD, PROXY, COLLECTION) : **pourquoi ce chargement**, déduit des opérations englobantes :
  - `lazy` : chargement lazy implicite (getter, itération d'une collection), sans demande explicite au-dessus ;
  - `init` : initialisation lazy demandée par une sonde de rôle `load` (`Hibernate.initialize`…) ou une opération de session ;
  - `explicit` : chargement voulu (sonde de rôle `load` : `findById`, `findAll`… ; `session.find/get/refresh/merge`) ;
  - `query` : hydraté par une requête (texte de la requête en détail) ;
- `snap` / `rsnap` : **état de l'instance après l'opération**, lu sur les champs (aucun chargement déclenché) : attachée ou détachée de sa session, et pour chaque association : chargée (taille), lazy, proxy non initialisé, null. Désactivable dans la configuration (« Instantanés d'état »).

### Vues et navigation

L'état de l'interface est dans l'URL : les boutons précédent / suivant du navigateur fonctionnent, et Ctrl+clic (ou ↗) ouvre une vue dans un nouvel onglet.

| URL | Vue |
|---|---|
| `?req=<clé>&tab=tree&ev=<id>` | Une requête, son onglet et l'événement sélectionné |
| `?view=entities` | Types d'entités : ids, LOAD lazy / voulus, initialisations, exceptions |
| `?entity=com.acme.Customer` | Un type : ses ids (instances multiples, lazy, exceptions) et ses propriétés |
| `?entity=com.acme.Customer&id=12` | **Cycle de vie** d'une entité |
| `?view=measures&tag=batch` | Mesures par tag et par méthode sondée |
| `?view=profiles&profile=hibernate` | Profils : sondes, règles de tags, réglages |
| `?view=timeline` | **Chronologie** de tous les événements (filtres `types`, `q`, `agent` ; position `at`, `before`, `after`) |
| `?view=timeline&entity=com.acme.Customer&id=12&ev=<id>` | Chronologie d'une entité et origine de son état à l'événement `ev` |

Le cycle de vie liste chaque opération sur l'entité, toutes requêtes confondues : chargement (avec sa cause), initialisation, util, find / refresh / merge / evict / clear, accès lazy, exception. Pour chaque étape, il montre l'instance Java (i1, i2…), son attachement à la session (S1, S2…) et l'état de chaque association **après** l'opération, en mettant en évidence ce qui a changé. Il explique les ruptures : nouvelle instance (findById, merge, rechargement dans une autre session), refresh, détachement avant une `LazyInitializationException`.

La **chronologie** affiche les événements triés par heure, toutes requêtes et tous threads confondus. Chaque ligne donne l'heure à la ms (date en séparateur), le temps écoulé depuis la ligne précédente, le thread (⇄ quand il change), la requête et l'emplacement dans le code. Au sein d'une même milliseconde, l'ordre est celui d'émission dans la JVM (compteur des ids de l'agent) ; un span est placé à son début et la fin d'une requête à son heure de fin. Sans position, la dernière page est suivie en direct ; « Aller à » saute à une date et heure précises.

Centrée sur une entité (type + id), elle ajoute l'instance Java, l'état après chaque étape et les explications du cycle de vie. Le panneau **« Pourquoi cet état ici ? »** indique, pour l'étape sélectionnée, quelle étape a établi chaque élément d'état, avec son thread, sa requête et son emplacement dans le code :

- l'instance (chargée par…) ;
- l'attachement (managée, détachée, proxy) ;
- chaque association.

Un `clear()` ou un `close()` de sa session est attribué à l'instance même s'il vient d'un autre thread. Le panneau liste aussi tous les changements de l'instance jusqu'à cette étape.

Chaque événement est rattaché à sa requête HTTP, avec le composant JSF source pour l'ajax PrimeFaces, et à son span parent. Exemple d'arborescence : UTIL `Hibernate.initialize` → COLLECTION `Customer.orders` → SQL + LOAD ×n.

Un accès à une entité ou une collection non initialisée apparaît donc toujours :

- en PROXY ou COLLECTION si le chargement lazy réussit ;
- en LAZY_ERROR (et PROXY en erreur) s'il échoue.

## Configuration dynamique (UI, appliquée en moins d'1 s, sans redémarrage)

- Entités : FQCN, nom simple (`Customer`) ou glob (`com.acme.model.order.*`), sous-classes incluses. `*` sélectionne tout. L'UI propose la liste des entités connues des agents.
- Types d'événements activés. ACCESS est désactivé par défaut car très volumineux : activez-le une fois la liste d'entités restreinte.
- Packages appelants, exclusions, bibliothèques, propriétés ignorées, piles pour ACCESS/LOAD. Valables pour tous les agents ; les profils y ajoutent leurs propres valeurs.

La config est persistée dans `server/config.json`, les profils dans `server/profiles/` (autre dossier : variable `PROFILES_DIR`). Seuls les profils génériques (`hibernate`, `web`, `exemple-app`) sont versionnés : les profils propres à une application restent locaux (`.gitignore`).

### Mode focus : une seule entité

Pour suivre une entité précise sans être submergé : bouton **Focus sur cette entité** (page d'une entité), **Focus sur ce type** (page d'un type), ou section *Mode focus* de la Configuration (type + id facultatif). Tant qu'il est actif, il remplace les entités et types configurés. L'en-tête l'affiche, avec un lien vers l'entité et un bouton **Arrêter**.

- **Enregistré** : uniquement ce qui concerne l'entité suivie (et cet id s'il est donné) :
  - chargement (LOAD, avec sa cause) ;
  - initialisation de ses proxies et collections ;
  - opérations de session qui la ciblent : find, getReference, refresh, merge, persist, update, lock, remove, evict, detach ;
  - les `clear` / `close` d'une session qui la contient encore (attribués à l'entité, état « détachée » après) ;
  - méthodes sondées qui la ciblent ou la renvoient ;
  - LazyInitializationException ;
  - ses getters (case « accès » cochée par défaut).
- **Non enregistré** : SQL, requêtes HQL, autres entités. Les requêtes HTTP qui ne la touchent pas n'apparaissent pas.
- Chaque opération porte l'instantané d'état de l'instance (attachée / détachée, associations chargées ou lazy). La page de l'entité montre ainsi tout son chemin de vie.
- Les spans non enregistrés restent suivis par l'agent : la cause d'un chargement (lazy implicite, init. demandée, requête, find…) reste exacte. L'analyse de pile n'est faite que pour les événements envoyés.

## Configuration statique (arguments de l'agent)

Séparés par `;`, ou dans un fichier : `config=/chemin/profiler.properties`. Seuls la connexion et le choix des profils sont ici : les sondes viennent du serveur. Au démarrage (`-javaagent`), l'agent contacte le serveur avant de rendre la main pour que les sondes soient en place avant le code applicatif ; si le serveur ne répond pas, elles sont installées au premier contact (une méthode déjà en cours d'exécution, comme `main`, n'est alors pas mesurée).

| Clé                                                                   | Défaut                                      | Rôle                                                                                     |
| --------------------------------------------------------------------- | ------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `server`                                                              | `http://localhost:7070`                     | Serveur de collecte                                                                      |
| `name`                                                                | `host:pid`                                  | Nom affiché dans l'UI                                                                    |
| `profiles`                                                            | profils dont `appliesTo` correspond         | Profils à appliquer, séparés par des virgules                                            |
| `debug`                                                               | `false`                                     | Journalise chaque classe transformée                                                     |

## Où modifier le code

- `Hooks.java` contient le code injecté dans les classes cibles. Il ne référence que des types `java.*` : les handlers sont publiés en `BiFunction` dans `System.getProperties()`. C'est ce qui permet de fonctionner avec les modules WildFly sans `jboss.modules.system.pkgs`, et en attachement à chaud.
- `Instrumenter.java` installe les sondes reçues du serveur (et les remplace à chaud), `Probe.java` traduit une sonde JSON en critères ByteBuddy. Pour mesurer une méthode de plus, aucun code : un profil suffit. Un nouveau handler demande une advice dans `Hooks`, une entrée dans `Probe.Handler` et son traitement dans `Recorder`.
- `server/profiles.js` : chargement et validation des profils, spécification envoyée à chaque agent, règles de tags.
- `Recorder.java` transforme un appel de hook en événement : filtrage, analyse de pile, spans parent/enfant.
- `Reflect.java` regroupe les accès réflexifs aux objets Hibernate (compatibles 5 et 6, javax et jakarta).
- `ui/src/lib/model.js` contient les agrégats d'une requête et la détection N+1 (même relation initialisée au moins 3 fois au même endroit). Il est partagé avec le serveur (résumés de requêtes, N+1).
- `server/store.js` : écriture dans SQLite (schéma, résumés de requêtes, compteurs, purge, recalcul des tags). `server/queries.js` : lectures pour l'UI, exécutées dans les workers de `server/reader.js`.

## Limites et points à vérifier

- Validé ici contre un banc de test qui reproduit les classes Hibernate 6, Servlet, EL et Mojarra ciblées, avec les mêmes noms et signatures. Il n'a pas été testé contre un vrai Hibernate, faute d'accès à Maven Central dans mon environnement. Au premier lancement, lancez avec `debug=true` et vérifiez que `AbstractLazyInitializer`, `DefaultInitializeCollectionEventListener`, `DefaultPostLoadEventListener` et `ResultSetReturnImpl` apparaissent bien comme transformées.
- Les accès faits par réflexion sur les champs (introspection, mappers génériques…) ne passent pas par les getters : pas d'ACCESS pour ceux-là. Les chargements lazy qu'ils provoquent restent visibles en PROXY ou COLLECTION.
- Si `UninitializedSet` lève une autre exception que `LazyInitializationException`, ajoutez-la à la sonde `hibernate/lazy-exception` (`extends`).
- Une sonde trop large (`class: ["*"]`) instrumente des milliers de classes : vérifiez le compteur de classes dans la page Profils.
- Les handlers stockés dans `System.getProperties()` ne sont pas des `String`. Un code qui ferait `System.getProperties().store(...)` lèverait une `ClassCastException` (rare).
- Coût : une analyse de pile par événement retenu. C'est négligeable hors ACCESS, mais lourd avec ACCESS sur toutes les entités. À réserver à un poste de dev.
- `dropped` > 0 dans l'en-tête signifie que la file de l'agent a débordé (500 000 événements) ou que le serveur était injoignable. Si le serveur ne répond plus pendant 5 s, l'agent suspend l'enregistrement.
