package ch.tecost.profiler;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Une sonde, définie dans un profil du serveur (server/profiles/*.json) :
 * <pre>
 * { "id": "entity-utils", "handler": "method", "class": ["com.acme.EntityUtils"], "methods": ["initialize*", "reload"],
 *   "role": "load", "tags": ["init"] }
 * </pre>
 * Classes : {@code class} (globs), {@code extends} (type ou super-type, FQCN), {@code annotated} (FQCN), {@code concrete} ;
 * critères combinés en ET. Méthodes : {@code methods} (globs, {@code <init>} pour les constructeurs), {@code args},
 * {@code minArgs}, {@code params} (type par argument, alternatives séparées par « | », « * » = quelconque),
 * {@code public}, {@code static}, {@code nonVoid}.
 * <p>
 * Le {@code handler} choisit le code injecté et l'interprétation de l'appel (voir {@link Handler}).
 */
final class Probe {

    /** Code injecté et interprétation : chaque handler correspond à une advice de {@link Hooks}. */
    enum Handler {
        /** Méthode quelconque mesurée (span UTIL), avec entité ciblée et instantané. */
        METHOD("method", Hooks.Util.class),
        /** Initialisation d'un proxy lazy (AbstractLazyInitializer#initialize). */
        PROXY_INIT("proxy-init", Hooks.ProxyInit.class),
        /** Initialisation d'une collection lazy (listener d'InitializeCollectionEvent). */
        COLLECTION_INIT("collection-init", Hooks.CollectionInit.class),
        /** Entité hydratée (listener de PostLoadEvent). */
        POST_LOAD("post-load", Hooks.PostLoad.class),
        /** Opérations de session : find / get / refresh / merge / evict / detach / clear / close. */
        SESSION("session", Hooks.Session.class),
        /** Exécution d'une requête (list()). */
        QUERY("query", Hooks.Query.class),
        /** Collection lazy qui va lever une LazyInitializationException. */
        LAZY_COLLECTION("lazy-collection", Hooks.LazyCollection.class),
        /** Constructeur d'une exception d'accès lazy. */
        LAZY_EXCEPTION("lazy-exception", Hooks.LazyException.class),
        /** Exécution SQL (premier argument String = SQL, sinon le statement). */
        SQL("sql", Hooks.Sql.class),
        /** Requête HTTP : service(request, response). */
        HTTP_REQUEST("http-request", Hooks.Request.class),
        /** Expression EL en cours d'évaluation. */
        EL("el", Hooks.El.class),
        /** Getter d'entité (ACCESS). */
        GETTER("getter", Hooks.Access.class);

        final String key;
        final Class<?> advice;

        Handler(String key, Class<?> advice) {
            this.key = key;
            this.advice = advice;
        }

        static Handler of(String key) {
            for (Handler h : values())
                if (h.key.equals(key))
                    return h;
            return null;
        }
    }

    /** Identifiant global : profil/sonde. */
    final String id;
    final Handler handler;
    final List<String> tags;
    /** Les tags s'appliquent aussi aux événements produits pendant l'exécution (enfants du span). */
    final boolean propagate;
    /** METHOD : "load" = chargement / initialisation voulus (cause des LOAD enfants) ; "measure" = simple mesure. */
    final String role;
    /** Libellé affiché à la place de Classe.méthode (optionnel). */
    final String label;

    final List<String> classes;
    final List<String> supertypes;
    final List<String> annotations;
    final Boolean concrete;
    final List<String> methods;
    final Integer args;
    final Integer minArgs;
    final List<String> params;
    final Boolean isPublic;
    final Boolean isStatic;
    final boolean nonVoid;

    private Probe(Map<String, Object> m) {
        id = str(m.get("id"));
        handler = Handler.of(str(m.get("handler")) == null ? "method" : str(m.get("handler")));
        tags = Collections.unmodifiableList(strings(m.get("tags")));
        propagate = Boolean.TRUE.equals(m.get("propagate"));
        role = "load".equals(m.get("role")) ? "load" : "measure";
        label = str(m.get("label"));
        classes = strings(m.get("class"));
        supertypes = strings(m.get("extends"));
        annotations = strings(m.get("annotated"));
        concrete = bool(m.get("concrete"));
        methods = strings(m.get("methods"));
        args = integer(m.get("args"));
        minArgs = integer(m.get("minArgs"));
        params = m.containsKey("params") ? strings(m.get("params")) : null;
        isPublic = bool(m.get("public"));
        isStatic = bool(m.get("static"));
        nonVoid = Boolean.TRUE.equals(m.get("nonVoid"));
    }

    /** null si la définition est inutilisable (erreur journalisée). */
    static Probe parse(Map<String, Object> m) {
        Probe p = new Probe(m);
        if (p.id == null) {
            Log.warn("probe without id ignored: " + m);
            return null;
        }
        if (p.handler == null) {
            Log.warn("probe " + p.id + ": unknown handler " + m.get("handler"));
            return null;
        }
        if (p.classes.isEmpty() && p.supertypes.isEmpty() && p.annotations.isEmpty()) {
            Log.warn("probe " + p.id + ": class, extends or annotated required");
            return null;
        }
        return p;
    }

    boolean wantsLoadCause() {
        return "load".equals(role);
    }

    ElementMatcher.Junction<TypeDescription> typeMatcher() {
        ElementMatcher.Junction<TypeDescription> t = any();
        if (!classes.isEmpty()) {
            ElementMatcher.Junction<TypeDescription> c = none();
            List<String> exact = new ArrayList<>();
            for (String glob : classes) {
                if (glob.contains("*"))
                    c = c.or(nameMatches(AgentConfig.globToRegex(glob)));
                else
                    exact.add(glob);
            }
            if (!exact.isEmpty())
                c = c.or(namedOneOf(exact.toArray(new String[0])));
            t = t.and(c);
        }
        if (!supertypes.isEmpty())
            t = t.and(hasSuperType(namedOneOf(supertypes.toArray(new String[0]))));
        if (!annotations.isEmpty())
            t = t.and(isAnnotatedWith(namedOneOf(annotations.toArray(new String[0]))));
        if (Boolean.TRUE.equals(concrete))
            t = t.and(not(isInterface())).and(not(isAbstract()));
        else
            t = t.and(not(isInterface()));
        return t;
    }

    ElementMatcher.Junction<MethodDescription> methodMatcher() {
        boolean ctor = methods.contains("<init>");
        ElementMatcher.Junction<MethodDescription> m;
        if (ctor) {
            m = isConstructor();
        } else {
            m = isMethod().and(not(isAbstract())).and(not(isBridge())).and(not(isSynthetic()));
            if (!methods.isEmpty()) {
                ElementMatcher.Junction<MethodDescription> names = none();
                for (String glob : methods)
                    names = names.or(nameMatches(AgentConfig.globToRegex(glob)));
                m = m.and(names);
            }
        }
        if (args != null)
            m = m.and(takesArguments(args));
        if (minArgs != null && minArgs > 0) {
            int min = minArgs;
            m = m.and(new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
                @Override
                public boolean matches(MethodDescription target) {
                    return target.getParameters().size() >= min;
                }
            });
        }
        if (params != null) {
            m = m.and(takesArguments(params.size()));
            for (int i = 0; i < params.size(); i++) {
                String p = params.get(i);
                if (!"*".equals(p))
                    m = m.and(takesArgument(i, namedOneOf(p.split("\\|"))));
            }
        }
        if (isPublic != null)
            m = m.and(isPublic ? isPublic() : not(isPublic()));
        if (isStatic != null)
            m = m.and(isStatic ? isStatic() : not(isStatic()));
        if (nonVoid)
            m = m.and(not(returns(void.class)));
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static Boolean bool(Object o) {
        return o instanceof Boolean ? (Boolean) o : null;
    }

    private static Integer integer(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    static List<String> strings(Object o) {
        List<String> r = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o)
                if (x != null && !String.valueOf(x).trim().isEmpty())
                    r.add(String.valueOf(x).trim());
        } else if (o instanceof String && !((String) o).trim().isEmpty()) {
            r.add(((String) o).trim());
        }
        return r;
    }
}
