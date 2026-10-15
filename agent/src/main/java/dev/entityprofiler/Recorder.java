package dev.entityprofiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ThreadLocalRandom;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reçoit les appels des hooks, construit les événements et les confie au {@link Transport}.
 * <p>
 * Contexte par thread : requête HTTP en cours, pile des spans ouverts (pour l'arbre d'appels),
 * pile des expressions EL en cours d'évaluation.
 */
final class Recorder {

    private final Transport transport;
    private final String idPrefix = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36), 36) + "-";
    private final AtomicLong seq = new AtomicLong();
    volatile RuntimeConfig rc = RuntimeConfig.offline();
    final Set<String> knownEntities = new ConcurrentSkipListSet<>();

    /** Sondes installées, par identifiant ; tags et propagation par handler (union des sondes du même handler). */
    private volatile Map<String, Probe> probes = new HashMap<>();
    private volatile Map<Probe.Handler, List<String>> handlerTags = new EnumMap<>(Probe.Handler.class);
    private volatile Set<Probe.Handler> handlerPropagate = EnumSet.noneOf(Probe.Handler.class);

    private final ThreadLocal<Ctx> ctx = ThreadLocal.withInitial(Ctx::new);

    private static final class Ctx {
        String req;
        int reqDepth;
        final ArrayDeque<Span> spans = new ArrayDeque<>();
        final ArrayDeque<Object> el = new ArrayDeque<>();
        /** Garde anti-réentrance : nos propres appels réflexifs ne doivent pas produire d'événements. */
        boolean busy;
        /** Collection dont l'initialisation lazy échoue (la LazyInitializationException est construite juste après). */
        Object failingCollection;
        /** Tags propagés par la requête HTTP en cours (sonde http-request avec propagate). */
        List<String> reqScope;
        /** Mode focus : REQ_START retenu jusqu'au premier événement sur l'entité suivie. */
        Event pendingStart;
        /** REQ_START de la requête en cours envoyé (sinon REQ_END ne l'est pas non plus). */
        boolean startSent;
    }

    /**
     * Mode focus : instances de l'entité suivie et dernière session connue (références faibles, comparées par identité :
     * jamais d'appel à equals / hashCode de l'application). Sert à signaler les session.clear / close qui les détachent.
     */
    private static final class Tracked {
        private static final int MAX = 256;
        private final WeakReference<?>[][] slots = new WeakReference<?>[MAX][];
        private int next;

        synchronized void put(Object entity, Object session) {
            if (entity == null)
                return;
            int free = -1;
            for (int i = 0; i < MAX; i++) {
                WeakReference<?>[] s = slots[i];
                Object o = s == null ? null : s[0].get();
                if (o == entity) {
                    if (session != null)
                        s[1] = new WeakReference<>(session);
                    return;
                }
                if (o == null && free < 0)
                    free = i;
            }
            int i = free >= 0 ? free : next++ % MAX;
            slots[i] = new WeakReference<?>[] { new WeakReference<>(entity), new WeakReference<>(session) };
        }

        /** Une instance suivie (de l'entité suivie actuellement) encore attachée à cette session, ou null. */
        synchronized Object in(Object session, RuntimeConfig rc) {
            for (WeakReference<?>[] s : slots) {
                if (s == null || s[1].get() != session)
                    continue;
                Object o = s[0].get();
                if (o == null)
                    continue;
                String[] ref = Reflect.entityRef(o);
                if (ref != null && rc.focusRef(ref[0], ref[1]) && Boolean.TRUE.equals(Reflect.call(session, "contains", o)))
                    return o;
            }
            return null;
        }
    }

    private final Tracked tracked = new Tracked();

    private static final class Span {
        final Event ev;
        final long start = System.nanoTime();
        final Object subject;
        boolean emit = true;
        /** UTIL / SESSION : objet ciblé, dont on prend l'instantané à la sortie. */
        Object target;
        Object session;
        /** Tags hérités par les événements produits pendant ce span. */
        List<String> scope;
        /** UTIL : sonde de rôle "load" (rend "voulus" les chargements qu'elle déclenche). */
        boolean loads;

        Span(Event ev, Object subject) {
            this.ev = ev;
            this.subject = subject;
        }
    }

    private static final class ElToken {
        final Object expression;

        ElToken(Object expression) {
            this.expression = expression;
        }
    }

    private static final class ReqToken {
        final Object request;
        final Object response;
        final Event start;
        final long nanos = System.nanoTime();
        final boolean nested;

        ReqToken(Object request, Object response, Event start, boolean nested) {
            this.request = request;
            this.response = response;
            this.start = start;
            this.nested = nested;
        }
    }

    Recorder(Transport transport) {
        this.transport = transport;
    }

    void install() {
        publish(Hooks.PROXY, this::onProxy);
        publish(Hooks.COLLECTION, this::onCollection);
        publish(Hooks.POST_LOAD, this::onPostLoad);
        publish(Hooks.ACCESS, this::onAccess);
        publish(Hooks.UTIL, this::onUtil);
        publish(Hooks.SQL, this::onSql);
        publish(Hooks.LAZY_EX, this::onLazyException);
        publish(Hooks.REQUEST, this::onRequest);
        publish(Hooks.EL, this::onEl);
        publish(Hooks.SESSION, this::onSession);
        publish(Hooks.QUERY, this::onQuery);
        publish(Hooks.LAZY_COLL, this::onLazyCollection);
        publish(Hooks.EXIT, this::onExit);
    }

    /** Retire les handlers : le code injecté encore présent devient inerte (il teste l'absence de handler). */
    void uninstall() {
        rc = RuntimeConfig.offline();
        for (String key : new String[] { Hooks.PROXY, Hooks.COLLECTION, Hooks.POST_LOAD, Hooks.ACCESS, Hooks.UTIL,
                Hooks.SQL, Hooks.LAZY_EX, Hooks.REQUEST, Hooks.EL, Hooks.SESSION, Hooks.QUERY, Hooks.LAZY_COLL, Hooks.EXIT })
            System.getProperties().remove(key);
    }

    void setProbes(List<Probe> list) {
        Map<String, Probe> byId = new HashMap<>();
        Map<Probe.Handler, Set<String>> tags = new EnumMap<>(Probe.Handler.class);
        Set<Probe.Handler> propagate = EnumSet.noneOf(Probe.Handler.class);
        for (Probe p : list) {
            byId.put(p.id, p);
            tags.computeIfAbsent(p.handler, h -> new LinkedHashSet<>()).addAll(p.tags);
            if (p.propagate && !p.tags.isEmpty())
                propagate.add(p.handler);
        }
        Map<Probe.Handler, List<String>> ht = new EnumMap<>(Probe.Handler.class);
        for (Map.Entry<Probe.Handler, Set<String>> e : tags.entrySet())
            if (!e.getValue().isEmpty())
                ht.put(e.getKey(), new ArrayList<>(e.getValue()));
        probes = byId;
        handlerTags = ht;
        handlerPropagate = propagate;
    }

    /** Nouvel événement portant les tags des sondes du handler. */
    private Event event(String type, Probe.Handler handler) {
        Event ev = new Event(type);
        ev.tags = handlerTags.get(handler);
        ev.propagate = ev.tags != null && handlerPropagate.contains(handler);
        return ev;
    }

    private static void publish(String key, BiFunction<Object, Object, Object> f) {
        System.getProperties().put(key, f);
    }

    // ------------------------------------------------------------------ hooks

    private Object onProxy(Object lazyInitializer, Object unused) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("PROXY"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        c.busy = true;
        try {
            // initialize() est appelé à chaque invocation sur le proxy : seul le premier appel nous intéresse
            if (!Boolean.TRUE.equals(Reflect.call(lazyInitializer, "isUninitialized")))
                return null;
            Event ev = event("PROXY", Probe.Handler.PROXY_INIT);
            ev.entity = str(Reflect.call(lazyInitializer, "getEntityName"));
            ev.eid = Reflect.shortId(Reflect.lazyInitializerId(lazyInitializer));
            ev.sess = Reflect.identity(Reflect.call(lazyInitializer, "getSession"));
            ev.cause = initCause(c, Reflect.simple(ev.entity) + " (proxy)");
            // Span ouvert dans tous les cas (cause des LOAD enfants), pile analysée seulement s'il sera émis
            boolean emit = rc.focus ? rc.focusRef(ev.entity, ev.eid) : rc.matchesEntity(ev.entity);
            if (emit) {
                Site s = analyze(rc, c, 0, true);
                s.apply(ev);
                ev.prop = propertyFromProxyTrigger(s.triggerFrame);
            }
            Span span = open(c, ev, lazyInitializer);
            span.emit = emit;
            return span;
        } finally {
            c.busy = false;
        }
    }

    private Object onCollection(Object initializeEvent, Object unused) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("COLLECTION"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        c.busy = true;
        try {
            Object coll = Reflect.call(initializeEvent, "getCollection");
            String role = str(Reflect.call(coll, "getRole"));
            Event ev = event("COLLECTION", Probe.Handler.COLLECTION_INIT);
            if (role != null) {
                int dot = role.lastIndexOf('.');
                ev.entity = dot > 0 ? role.substring(0, dot) : role;
                ev.prop = dot > 0 ? role.substring(dot + 1) : null;
            }
            ev.eid = Reflect.shortId(Reflect.call(coll, "getKey"));
            ev.inst = Reflect.identity(Reflect.call(coll, "getOwner"));
            ev.sess = Reflect.identity(Reflect.call(coll, "getSession"));
            ev.cause = initCause(c, Reflect.simple(ev.entity) + "." + ev.prop);
            if (!rc.focus || rc.focusRef(ev.entity, ev.eid))
                analyze(rc, c, 0, true).apply(ev);
            return open(c, ev, coll);
        } finally {
            c.busy = false;
        }
    }

    private Object onPostLoad(Object postLoadEvent, Object unused) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("LOAD"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        c.busy = true;
        try {
            Object entity = Reflect.call(postLoadEvent, "getEntity");
            if (entity == null || !rc.matchesEntity(entity.getClass()))
                return null;
            Event ev = event("LOAD", Probe.Handler.POST_LOAD);
            ev.entity = Reflect.entityClassName(entity.getClass());
            ev.eid = Reflect.shortId(Reflect.call(postLoadEvent, "getId"));
            if (rc.focus && !rc.focusId(ev.eid))
                return null;
            ev.inst = Reflect.identity(entity);
            Object session = Reflect.call(postLoadEvent, "getSession");
            ev.sess = Reflect.identity(session);
            if (rc.snapshots)
                ev.snap = Reflect.snapshot(entity, session);
            ev.cause = cause(c);
            analyze(rc, c, 0, rc.loadStacks).apply(ev);
            emitPoint(c, ev);
            if (rc.focus)
                tracked.put(entity, session);
        } finally {
            c.busy = false;
        }
        return null;
    }

    private Object onAccess(Object self, Object data) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("ACCESS"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        Class<?> cls = self.getClass();
        if (!rc.matchesEntity(cls))
            return null;
        Object[] a = (Object[]) data;
        String prop = propertyName((String) a[0]);
        if (rc.accessExclude.contains(prop))
            return null;
        c.busy = true;
        try {
            String eid = Reflect.shortId(EntityIds.read(self));
            if (rc.focus && !rc.focusId(eid))
                return null;
            Site s = analyze(rc, c, 1, rc.accessStacks);
            if (rc.ignoreHibernateAccess && s.firstFrame != null && s.firstFrame.startsWith("org.hibernate."))
                return null;
            Event ev = event("ACCESS", Probe.Handler.GETTER);
            ev.entity = Reflect.entityClassName(cls);
            ev.prop = prop;
            ev.eid = eid;
            ev.inst = Reflect.identity(self);
            ev.state = valueState(a[1]);
            s.apply(ev);
            emitPoint(c, ev);
        } finally {
            c.busy = false;
        }
        return null;
    }

    private Object onUtil(Object signature, Object args) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("UTIL"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        c.busy = true;
        try {
            Object[] sig = (Object[]) signature;
            Probe probe = probes.get((String) sig[4]);
            Event ev = new Event("UTIL");
            ev.entity = (String) sig[0];
            ev.prop = (String) sig[1];
            if (probe != null) {
                ev.probe = probe.id;
                ev.label = probe.label;
                ev.tags = probe.tags.isEmpty() ? null : probe.tags;
                ev.propagate = probe.propagate && ev.tags != null;
            }
            Object[] arr = (Object[]) args;
            // Entité ciblée : this (patient.reload()...), sinon le premier argument entité / proxy / collection
            Object targetObj = sig[3];
            String[] target = Reflect.entityRef(targetObj);
            for (int i = 0; target == null && arr != null && i < arr.length; i++)
                target = Reflect.entityRef(targetObj = arr[i]);
            if (target != null) {
                ev.tentity = target[0];
                ev.teid = target[1];
                ev.inst = target[2];
            }
            if (arr != null && arr.length > 0) {
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(Reflect.describe(arr[i]));
                }
                ev.info = sb.append(')').toString();
            } else {
                ev.info = "()";
            }
            // Mode focus : cible connue et autre que l'entité suivie => jamais émis, pas d'analyse de pile
            if (!rc.focus || target == null || rc.focusRef(ev.tentity, ev.teid))
                analyze(rc, c, 1, true).apply(ev);
            boolean isVoid = sig[2] != null && ((String) sig[2]).endsWith(")V");
            Span span = open(c, ev, isVoid);
            span.loads = probe != null && probe.wantsLoadCause();
            if (target != null)
                span.target = Reflect.isPersistentCollection(targetObj) ? Reflect.call(targetObj, "getOwner") : targetObj;
            return span;
        } finally {
            c.busy = false;
        }
    }

    private static String family(String method) {
        switch (method) {
        case "get":
            return "find";
        case "detach":
            return "evict";
        case "delete":
            return "remove";
        case "load":
            return "getReference";
        case "save":
        case "saveOrUpdate":
            return "persist";
        default:
            return method;
        }
    }

    /** Query.list() : span "query" dont les LOAD enfants héritent la cause. */
    private Object onQuery(Object query, Object unused) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("SESSION"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        Span top = c.spans.peek();
        if (top != null && "SESSION".equals(top.ev.type) && "query".equals(top.ev.prop))
            return null; // list() d'une sous-classe qui appelle super.list()
        c.busy = true;
        try {
            Event ev = event("SESSION", Probe.Handler.QUERY);
            ev.prop = "query";
            Object hql = Reflect.call(query, "getQueryString");
            ev.info = Reflect.truncate(hql == null ? query.getClass().getSimpleName() : String.valueOf(hql), 2000);
            ev.sess = Reflect.identity(Reflect.call(query, "getSession"));
            if (!rc.focus) // mode focus : span utile pour la cause des LOAD, jamais émis
                analyze(rc, c, 1, true).apply(ev);
            return open(c, ev, null);
        } finally {
            c.busy = false;
        }
    }

    /**
     * Cause d'un chargement, d'après les spans englobants (du plus proche au plus lointain) :
     * le premier UTIL de rôle "load" ou SESSION rencontré rend le chargement "voulu" ; un PROXY / COLLECTION rencontré
     * avant lui signifie que c'est un chargement lazy (demandé explicitement s'il y a un tel span au-dessus).
     * Les UTIL de rôle "measure" (simple mesure) sont transparents.
     */
    private static String cause(Ctx c) {
        String lazy = null;
        for (Span s : c.spans) {
            Event a = s.ev;
            switch (a.type) {
            case "PROXY":
            case "COLLECTION":
                if (lazy == null)
                    lazy = Reflect.simple(a.entity) + (a.prop != null ? "." + a.prop : " (proxy)");
                break;
            case "UTIL":
                if (!s.loads)
                    break; // simple mesure : transparente pour la cause
                // fall through
            case "SESSION":
                String op = "UTIL".equals(a.type) ? Reflect.simple(a.entity) + "." + a.prop
                        : "query".equals(a.prop) ? "query " + Reflect.truncate(a.info, 300) : "session." + a.prop;
                if (lazy != null)
                    return "init|" + op + " › " + lazy;
                return ("query".equals(a.prop) && "SESSION".equals(a.type) ? "query|" + Reflect.truncate(a.info, 300) : "explicit|" + op);
            default:
            }
        }
        return lazy != null ? "lazy|" + lazy : "other|";
    }

    /**
     * Cause d'une initialisation lazy (PROXY / COLLECTION) : implicite si rien d'explicite ne l'englobe,
     * "init" si elle est demandée par une sonde de rôle "load" ou une opération de session.
     */
    private static String initCause(Ctx c, String self) {
        String cause = cause(c);
        if (cause.startsWith("other|"))
            return "lazy|" + self;
        if (cause.startsWith("explicit|"))
            return "init|" + cause.substring("explicit|".length()) + " › " + self;
        return cause;
    }

    private Object onLazyCollection(Object collection, Object unused) {
        if (rc.on("LAZY_ERROR"))
            ctx.get().failingCollection = collection;
        return null;
    }

    /** SessionImpl.clear/evict/detach/close/refresh/merge/find/get. */
    private Object onSession(Object signature, Object args) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("SESSION"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        Object[] sig = (Object[]) signature;
        String method = (String) sig[0];
        String sess = Reflect.identity(sig[1]);
        // Surcharges qui se délèguent entre elles (merge(String, Object) -> merge(Object)...) : un seul span
        Span top = c.spans.peek();
        if (top != null && "SESSION".equals(top.ev.type) && family(method).equals(family(top.ev.prop)) && sess.equals(top.ev.sess))
            return null;
        c.busy = true;
        try {
            Event ev = event("SESSION", Probe.Handler.SESSION);
            Object target = null;
            ev.prop = method;
            ev.sess = sess;
            Object[] arr = (Object[]) args;
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; arr != null && i < arr.length; i++) {
                Object a = arr[i];
                if (i > 0)
                    sb.append(", ");
                sb.append(Reflect.describe(a));
                if (target == null && !(a instanceof String)) {
                    String[] ref = Reflect.entityRef(a);
                    if (ref != null) {
                        ev.entity = ref[0];
                        ev.eid = ref[1];
                        ev.inst = ref[2];
                        target = a;
                    }
                }
            }
            ev.info = sb.append(')').toString();
            if (rc.focus && target == null && ("clear".equals(method) || "close".equals(method))) {
                // L'événement concerne l'entité suivie si une de ses instances est encore dans cette session
                Object hit = tracked.in(sig[1], rc);
                String[] ref = Reflect.entityRef(hit);
                if (ref != null) {
                    ev.entity = ref[0];
                    ev.eid = ref[1];
                    ev.inst = ref[2];
                    target = hit; // instantané après l'opération : détachée
                }
            }
            // Mode focus : pile analysée seulement si l'événement peut être émis (find / get : entité connue au retour)
            if (!rc.focus || rc.focusRef(ev.entity, ev.eid) || ev.entity == null && ("find".equals(family(method)) || "getReference".equals(family(method)) || "merge".equals(method)))
                analyze(rc, c, 1, true).apply(ev);
            Span span = open(c, ev, null);
            span.target = target;
            span.session = sig[1];
            return span;
        } finally {
            c.busy = false;
        }
    }

    private Object onSql(Object method, Object args) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("SQL"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        c.busy = true;
        try {
            Event ev = event("SQL", Probe.Handler.SQL);
            ev.prop = (String) method;
            String sql = null;
            Object statement = null;
            for (Object o : (Object[]) args) {
                if (o instanceof String && sql == null)
                    sql = (String) o;
                else if (statement == null && o != null)
                    statement = o;
            }
            if (sql == null && statement != null)
                sql = String.valueOf(statement);
            ev.info = Reflect.truncate(sql, 4000);
            analyze(rc, c, 0, true).apply(ev);
            return open(c, ev, null);
        } finally {
            c.busy = false;
        }
    }

    private static final Pattern LAZY_PROXY = Pattern.compile("\\[([\\w.$]+)#([^\\]]*)\\]");
    private static final Pattern LAZY_ROLE = Pattern.compile("role:?\\s*'?([\\w.$]+)\\.([\\w$]+)'?");
    private static final Pattern LAZY_KEY = Pattern.compile("key\\s*'([^']*)'");

    private Object onLazyException(Object exception, Object unused) {
        RuntimeConfig rc = this.rc;
        if (!rc.on("LAZY_ERROR"))
            return null;
        Ctx c = ctx.get();
        if (c.busy)
            return null;
        c.busy = true;
        try {
            Event ev = event("LAZY_ERROR", Probe.Handler.LAZY_EXCEPTION);
            String msg = ((Throwable) exception).getMessage();
            ev.error = exception.getClass().getName() + ": " + msg;
            if (msg != null) {
                Matcher m = LAZY_ROLE.matcher(msg);
                if (m.find()) {
                    ev.entity = m.group(1);
                    ev.prop = m.group(2);
                    Matcher k = LAZY_KEY.matcher(msg);
                    if (k.find())
                        ev.eid = k.group(1);
                } else if ((m = LAZY_PROXY.matcher(msg)).find()) {
                    ev.entity = m.group(1);
                    ev.eid = m.group(2);
                }
            }
            Object coll = c.failingCollection;
            c.failingCollection = null;
            if (coll != null) {
                String role = str(Reflect.call(coll, "getRole"));
                if (role != null && role.lastIndexOf('.') > 0) {
                    ev.entity = role.substring(0, role.lastIndexOf('.'));
                    ev.prop = role.substring(role.lastIndexOf('.') + 1);
                }
                ev.eid = Reflect.shortId(Reflect.call(coll, "getKey"));
                ev.inst = Reflect.identity(Reflect.call(coll, "getOwner"));
            } else {
                Span top = c.spans.peek();
                if (top != null && "PROXY".equals(top.ev.type)) {
                    ev.entity = top.ev.entity;
                    ev.eid = top.ev.eid;
                }
            }
            if (ev.entity != null && !rc.matchesEntity(ev.entity))
                return null;
            Site s = analyze(rc, c, 0, true);
            s.apply(ev);
            if (ev.prop == null)
                ev.prop = propertyFromProxyTrigger(s.triggerFrame);
            emitPoint(c, ev);
        } finally {
            c.busy = false;
        }
        return null;
    }

    private Object onRequest(Object request, Object response) {
        Ctx c = ctx.get();
        c.reqDepth++;
        if (c.reqDepth > 1)
            return new ReqToken(null, null, null, true); // forward / include
        c.req = idPrefix + "r" + seq.incrementAndGet();
        c.spans.clear();
        c.el.clear();
        Event ev = event("REQ_START", Probe.Handler.HTTP_REQUEST);
        c.reqScope = ev.propagate ? ev.tags : null;
        ev.id = c.req;
        ev.req = c.req;
        ev.t = System.currentTimeMillis();
        ev.thread = Thread.currentThread().getName();
        c.busy = true;
        try {
            String method = str(Reflect.call(request, "getMethod"));
            String uri = str(Reflect.call(request, "getRequestURI"));
            String query = str(Reflect.call(request, "getQueryString"));
            ev.prop = method;
            ev.info = query == null ? uri : uri + "?" + Reflect.truncate(query, 300);
        } finally {
            c.busy = false;
        }
        c.pendingStart = null;
        c.startSent = false;
        if (rc.recording) {
            if (rc.focus) {
                c.pendingStart = ev;
            } else {
                transport.offer(ev);
                c.startSent = true;
            }
        }
        return new ReqToken(request, response, ev, false);
    }

    private Object onEl(Object expression, Object unused) {
        Ctx c = ctx.get();
        c.el.push(expression);
        return new ElToken(expression);
    }

    private Object onExit(Object token, Object data) {
        Object[] a = (Object[]) data;
        Object result = a[0];
        Throwable thrown = (Throwable) a[1];
        Ctx c = ctx.get();
        if (token instanceof ElToken) {
            if (!c.el.isEmpty() && c.el.peek() == ((ElToken) token).expression)
                c.el.pop();
            else
                c.el.remove(((ElToken) token).expression);
            return null;
        }
        if (token instanceof ReqToken) {
            endRequest(c, (ReqToken) token, thrown);
            return null;
        }
        if (token instanceof Span) {
            boolean wasBusy = c.busy;
            c.busy = true;
            try {
                closeSpan(c, (Span) token, result, thrown);
            } finally {
                c.busy = wasBusy;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ spans

    private Span open(Ctx c, Event ev, Object subject) {
        ev.id = idPrefix + seq.incrementAndGet();
        Span top = c.spans.peek();
        ev.parent = top == null ? null : top.ev.id;
        ev.req = c.req;
        ev.t = System.currentTimeMillis();
        ev.thread = Thread.currentThread().getName();
        ev.scope = top == null ? c.reqScope : top.scope;
        Span s = new Span(ev, subject);
        s.scope = ev.propagate ? union(ev.scope, ev.tags) : ev.scope;
        c.spans.push(s);
        return s;
    }

    private void closeSpan(Ctx c, Span s, Object result, Throwable thrown) {
        if (c.spans.peek() == s)
            c.spans.pop();
        else
            c.spans.remove(s);
        Event ev = s.ev;
        ev.dur = (System.nanoTime() - s.start) / 1_000_000.0;
        if (thrown != null)
            ev.error = thrown.getClass().getName() + ": " + Reflect.truncate(thrown.getMessage(), 500);
        RuntimeConfig rc = this.rc;
        switch (ev.type) {
        case "COLLECTION":
            if (rc.snapshots && thrown == null)
                ev.snap = Reflect.snapshot(Reflect.call(s.subject, "getOwner"), Reflect.call(s.subject, "getSession"));
            String elementClass = null;
            if (thrown == null && Boolean.TRUE.equals(Reflect.call(s.subject, "wasInitialized"))) {
                Object coll = s.subject;
                Collection<?> values = coll instanceof Collection ? (Collection<?>) coll
                        : coll instanceof Map ? ((Map<?, ?>) coll).values() : null;
                if (values != null) {
                    Iterator<?> it = values.iterator();
                    if (it.hasNext()) {
                        Object first = it.next();
                        if (first != null)
                            elementClass = Reflect.entityClassName(first.getClass());
                    }
                    ev.info = "size=" + values.size() + (elementClass == null ? "" : ", element=" + elementClass);
                }
            }
            s.emit = rc.matchesEntity(ev.entity) || (elementClass != null && rc.matchesEntity(elementClass));
            break;
        case "UTIL":
            if (thrown == null && !Boolean.TRUE.equals(s.subject)) {
                ev.info = ev.info + " → " + Reflect.describe(result);
                returned(ev, result);
            }
            afterTarget(rc, ev, s, result);
            break;
        case "PROXY":
            // Instance réelle derrière le proxy : la même que celle du LOAD enfant
            if (thrown == null && !Boolean.TRUE.equals(Reflect.call(s.subject, "isUninitialized"))) {
                Object impl = Reflect.call(s.subject, "getImplementation");
                ev.inst = Reflect.identity(impl);
                if (rc.snapshots)
                    ev.snap = Reflect.snapshot(impl, Reflect.call(s.subject, "getSession"));
            }
            break;
        case "SESSION":
            if (thrown == null && "merge".equals(ev.prop)) {
                ev.info = ev.info + " → " + Reflect.describe(result);
                returned(ev, result);
            }
            if (thrown == null && ev.entity == null && ("find".equals(family(ev.prop)) || "getReference".equals(family(ev.prop)))) {
                // find(Class, id), getReference(Class, id) : l'entité n'est connue qu'au retour
                ev.info = ev.info + " → " + Reflect.describe(result);
                String[] ref = Reflect.entityRef(result);
                if (ref != null) {
                    ev.entity = ref[0];
                    ev.eid = ref[1];
                    ev.inst = ref[2];
                    s.target = result;
                }
            }
            if ("query".equals(ev.prop) && thrown == null && result instanceof java.util.Collection)
                ev.info = ev.info + "  → " + ((java.util.Collection<?>) result).size() + " résultat(s)";
            s.emit = ev.entity == null || rc.matchesEntity(ev.entity);
            afterTarget(rc, ev, s, result);
            break;
        default:
        }
        if (s.emit && rc.recording && (!rc.focus || keepFocus(rc, ev))) {
            offer(c, ev);
            if (rc.focus)
                track(ev, s, result);
        }
    }

    /** Mode focus : l'événement porte sur l'entité suivie. */
    private static boolean keepFocus(RuntimeConfig rc, Event ev) {
        switch (ev.type) {
        case "SQL":
            return false;
        case "UTIL":
            return rc.focusRef(ev.tentity, ev.teid);
        default:
            return rc.focusRef(ev.entity, ev.eid);
        }
    }

    /** Mode focus : mémorise l'instance concernée et sa session (pour les clear / close qui la détacheront). */
    private void track(Event ev, Span s, Object result) {
        switch (ev.type) {
        case "PROXY":
            if (!Boolean.TRUE.equals(Reflect.call(s.subject, "isUninitialized")))
                tracked.put(Reflect.call(s.subject, "getImplementation"), Reflect.call(s.subject, "getSession"));
            break;
        case "COLLECTION":
            tracked.put(Reflect.call(s.subject, "getOwner"), Reflect.call(s.subject, "getSession"));
            break;
        case "SESSION":
            if (!"clear".equals(ev.prop) && !"close".equals(ev.prop) && !"evict".equals(ev.prop) && !"detach".equals(ev.prop)
                    && !"remove".equals(family(ev.prop))) {
                tracked.put(unproxy(s.target), s.session);
                tracked.put(unproxy(result), s.session);
            }
            break;
        default:
        }
    }

    /** Instance réelle d'une entité (proxy initialisé déballé) ; null pour un proxy non initialisé ou autre chose qu'une entité. */
    private static Object unproxy(Object o) {
        if (o == null)
            return null;
        if (Reflect.isProxy(o))
            return Reflect.isUninitializedProxy(o) ? null : Reflect.call(Reflect.call(o, "getHibernateLazyInitializer"), "getImplementation");
        return Reflect.looksLikeEntity(o.getClass()) ? o : null;
    }

    /** Envoi ; en mode focus, le REQ_START retenu part juste avant le premier événement de la requête. */
    private void offer(Ctx c, Event ev) {
        if (c.pendingStart != null) {
            transport.offer(c.pendingStart);
            c.pendingStart = null;
            c.startSent = true;
        }
        transport.offer(ev);
    }

    /** Instantané de la cible (et de l'instance retournée si c'en est une autre) après l'exécution. */
    private static void afterTarget(RuntimeConfig rc, Event ev, Span s, Object result) {
        if (s.target != null) {
            // Proxy non initialisé à l'entrée, initialisé par la méthode (Utils.initialize...) : instance réelle
            String[] now = Reflect.entityRef(s.target);
            if (now != null && now[2] != null && ev.inst != null && ev.inst.startsWith("p") && !now[2].startsWith("p"))
                ev.inst = now[2];
            if (rc.snapshots)
                ev.snap = Reflect.snapshot(s.target, s.session);
        }
        if (rc.snapshots && ev.rinst != null)
            ev.rsnap = Reflect.snapshot(result, s.session);
    }

    /** Instance retournée (reload, merge...) : signalée si ce n'est pas l'instance ciblée. */
    private static void returned(Event ev, Object result) {
        String[] ref = Reflect.entityRef(result);
        if (ref == null)
            return;
        if (ev.tentity == null && "UTIL".equals(ev.type)) {
            ev.tentity = ref[0];
            ev.teid = ref[1];
        }
        if (ref[2] != null && !ref[2].equals(ev.inst))
            ev.rinst = ref[2];
    }

    private static List<String> union(List<String> a, List<String> b) {
        if (a == null || a.isEmpty())
            return b;
        Set<String> r = new LinkedHashSet<>(a);
        r.addAll(b);
        return new ArrayList<>(r);
    }

    private void emitPoint(Ctx c, Event ev) {
        ev.id = idPrefix + seq.incrementAndGet();
        Span top = c.spans.peek();
        ev.parent = top == null ? null : top.ev.id;
        ev.scope = top == null ? c.reqScope : top.scope;
        ev.req = c.req;
        ev.t = System.currentTimeMillis();
        ev.thread = Thread.currentThread().getName();
        RuntimeConfig rc = this.rc;
        if (rc.focus && !keepFocus(rc, ev))
            return;
        offer(c, ev);
    }

    private void endRequest(Ctx c, ReqToken t, Throwable thrown) {
        c.reqDepth = Math.max(0, c.reqDepth - 1);
        if (t.nested)
            return;
        Event ev = new Event("REQ_END");
        ev.tags = t.start.tags;
        ev.id = c.req;
        ev.req = c.req;
        ev.t = t.start.t;
        ev.dur = (System.nanoTime() - t.nanos) / 1_000_000.0;
        ev.thread = t.start.thread;
        ev.prop = t.start.prop;
        ev.info = t.start.info;
        c.busy = true;
        try {
            Object status = Reflect.call(t.response, "getStatus");
            ev.state = status == null ? null : String.valueOf(status);
            // Composant JSF source de la requête (bouton, ajax...) : les paramètres sont déjà parsés à ce stade
            String source = str(Reflect.call(t.request, "getParameter", "jakarta.faces.source"));
            if (source == null)
                source = str(Reflect.call(t.request, "getParameter", "javax.faces.source"));
            String partial = str(Reflect.call(t.request, "getParameter", "jakarta.faces.partial.ajax"));
            if (partial == null)
                partial = str(Reflect.call(t.request, "getParameter", "javax.faces.partial.ajax"));
            String event = str(Reflect.call(t.request, "getParameter", "jakarta.faces.behavior.event"));
            if (event == null)
                event = str(Reflect.call(t.request, "getParameter", "javax.faces.behavior.event"));
            if (source != null)
                ev.el = source + ("true".equals(partial) ? " (ajax" + (event != null ? " " + event : "") + ")" : "");
        } finally {
            c.busy = false;
        }
        if (thrown != null)
            ev.error = thrown.getClass().getName() + ": " + Reflect.truncate(thrown.getMessage(), 500);
        // Mode focus : seulement les requêtes qui ont touché l'entité suivie
        if (rc.recording && (c.startSent || !rc.focus))
            transport.offer(ev);
        c.pendingStart = null;
        c.startSent = false;
        c.req = null;
        c.reqScope = null;
        c.spans.clear();
        c.el.clear();
    }

    // ------------------------------------------------------------------ analyse de pile

    private static final class Site {
        String caller;
        String inner;
        String trigger;
        String triggerFrame;
        String firstFrame;
        String phase;
        String el;
        List<String> stack;

        void apply(Event ev) {
            ev.caller = caller;
            ev.inner = inner;
            ev.trigger = trigger;
            ev.phase = phase;
            ev.el = el;
            ev.stack = stack;
        }
    }

    /**
     * @param skip nombre de frames à ignorer après ceux de l'agent (1 = la méthode instrumentée elle-même,
     *             utile pour les getters et les utils dont on veut l'appelant)
     */
    private Site analyze(RuntimeConfig rc, Ctx c, int skip, boolean withStack) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        Site s = new Site();
        int i = 0;
        while (i < st.length && (st[i].getClassName().startsWith("dev.entityprofiler.") || "<init>".equals(st[i].getMethodName())
                && isThrowable(st[i].getClassName())))
            i++;
        i = Math.min(st.length, i + skip);
        if (i < st.length)
            s.firstFrame = st[i].getClassName();
        int stop = -1;
        for (int j = i; j < st.length; j++) {
            String cn = st[j].getClassName();
            if (s.phase == null)
                s.phase = phaseOf(cn);
            if (s.inner == null && rc.isApp(cn))
                s.inner = frame(st[j]);
            if (stop < 0) {
                if (rc.isCaller(cn)) {
                    stop = j;
                    s.caller = frame(st[j]);
                } else if (isView(cn)) {
                    stop = j;
                    s.caller = (cn.contains(".el.") || cn.contains("expressly") ? "[EL] " : "[JSF] ") + shortFrame(st[j]);
                }
            }
            if (stop >= 0 && s.phase != null && s.inner != null)
                break;
        }
        if (stop > i) {
            s.triggerFrame = st[stop - 1].getClassName() + "." + st[stop - 1].getMethodName();
            s.trigger = shortFrame(st[stop - 1]);
        }
        Object expr = c.el.peek();
        if (expr != null) {
            Object text = Reflect.call(expr, "getExpressionString");
            s.el = text == null ? null : Reflect.truncate(String.valueOf(text), 200);
        }
        if (withStack) {
            List<String> frames = new ArrayList<>();
            for (int j = i; j < st.length && frames.size() < rc.stackDepth; j++) {
                String cn = st[j].getClassName();
                if (cn.startsWith("jdk.internal.reflect.") || cn.startsWith("sun.reflect.") || cn.startsWith("java.lang.reflect."))
                    continue;
                frames.add(frame(st[j]));
            }
            s.stack = frames;
        }
        return s;
    }

    private static boolean isThrowable(String className) {
        return className.endsWith("Exception") || className.endsWith("Error") || className.equals("java.lang.Throwable");
    }

    private static boolean isView(String cn) {
        return cn.startsWith("jakarta.el.") || cn.startsWith("javax.el.") || cn.startsWith("com.sun.el.") || cn.startsWith("org.apache.el.")
                || cn.startsWith("org.glassfish.expressly.") || cn.startsWith("org.jboss.el.") || cn.startsWith("com.sun.faces.")
                || cn.startsWith("org.apache.myfaces.") || cn.startsWith("org.primefaces.") || cn.startsWith("org.omnifaces.")
                || cn.startsWith("jakarta.faces.") || cn.startsWith("javax.faces.");
    }

    private static String phaseOf(String cn) {
        if (!(cn.startsWith("com.sun.faces.lifecycle.") || cn.startsWith("org.apache.myfaces.")))
            return null;
        if (cn.contains("RenderResponse"))
            return "RENDER_RESPONSE";
        if (cn.contains("InvokeApplication"))
            return "INVOKE_APPLICATION";
        if (cn.contains("UpdateModelValues"))
            return "UPDATE_MODEL_VALUES";
        if (cn.contains("ProcessValidations"))
            return "PROCESS_VALIDATIONS";
        if (cn.contains("ApplyRequestValues"))
            return "APPLY_REQUEST_VALUES";
        if (cn.contains("RestoreView"))
            return "RESTORE_VIEW";
        return null;
    }

    private static String frame(StackTraceElement e) {
        return e.getClassName() + "." + e.getMethodName() + (e.getLineNumber() > 0 ? ":" + e.getLineNumber() : "");
    }

    private static String shortFrame(StackTraceElement e) {
        String cn = e.getClassName();
        int proxy = cn.indexOf("$HibernateProxy");
        String simple = proxy > 0 ? Reflect.simple(cn.substring(0, proxy)) + "(proxy)" : cn.substring(cn.lastIndexOf('.') + 1);
        return simple + "." + e.getMethodName();
    }

    /** Patient$HibernateProxy$xyz.getName -> name */
    private static String propertyFromProxyTrigger(String triggerFrame) {
        if (triggerFrame == null || !triggerFrame.contains("$HibernateProxy"))
            return null;
        return propertyName(triggerFrame.substring(triggerFrame.lastIndexOf('.') + 1));
    }

    static String propertyName(String method) {
        String p;
        if (method.startsWith("get") && method.length() > 3)
            p = method.substring(3);
        else if (method.startsWith("is") && method.length() > 2)
            p = method.substring(2);
        else
            return method + "()";
        return Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }

    private static String valueState(Object value) {
        if (value == null)
            return null;
        if (Reflect.isPersistentCollection(value))
            return Boolean.TRUE.equals(Reflect.call(value, "wasInitialized")) ? null : "LAZY_COLLECTION";
        if (Reflect.isProxy(value))
            return Reflect.isUninitializedProxy(value) ? "LAZY_PROXY" : null;
        if (value.getClass().getSimpleName().contains("Uninitialized"))
            return "UNINITIALIZED";
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
