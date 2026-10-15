package ch.tecost.profiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Configuration dynamique, poussée par le serveur (modifiable depuis l'UI, sans redémarrage).
 * Immuable : une nouvelle instance remplace l'ancienne à chaque changement.
 */
final class RuntimeConfig {

    boolean recording;
    final Set<String> types = new HashSet<>();
    private boolean allEntities;
    private final Set<String> exactEntities = new HashSet<>();
    private final Set<String> simpleEntities = new HashSet<>();
    private final List<Pattern> globEntities = new ArrayList<>();
    /** Packages applicatifs. Vide : tout ce qui n'est pas dans {@link #libraryPackages} est applicatif. */
    List<String> callerPackages = new ArrayList<>();
    /** Bibliothèques et frameworks (jamais "appelant" quand {@link #callerPackages} est vide). */
    List<String> libraryPackages = new ArrayList<>(Arrays.asList("java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.hibernate.", "org.springframework.", "org.apache.", "org.jboss.", "org.wildfly.", "io.undertow.",
            "org.eclipse.", "org.glassfish.", "net.bytebuddy.", "org.primefaces.", "org.omnifaces.", "com.zaxxer.",
            "io.quarkus.", "io.netty.", "io.smallrye.", "com.fasterxml.", "org.slf4j.", "ch.qos.", "kotlin.", "scala.",
            "groovy.", "org.codehaus.", "org.aspectj.", "com.google.", "io.micrometer.", "reactor.", "org.postgresql.",
            "oracle.", "com.microsoft.", "com.mysql.", "org.mariadb.", "org.h2.", "org.hsqldb.", "org.junit."));
    private final List<Pattern> callerExclude = new ArrayList<>();
    final Set<String> accessExclude = new HashSet<>();
    boolean ignoreHibernateAccess = true;
    boolean accessStacks;
    boolean loadStacks;
    int stackDepth = 80;
    /** Instantané de l'état des associations après chaque opération sur une entité. */
    boolean snapshots = true;

    /**
     * Mode focus : une seule entité (type, et éventuellement id) est suivie. Seuls ses changements d'état (chargement,
     * initialisations, opérations de session qui l'attachent ou la détachent, méthodes qui la ciblent) et ses accès
     * sont envoyés ; pas de SQL, et seules les requêtes HTTP qui la touchent apparaissent.
     */
    boolean focus;
    /** Mode focus : id suivi (null = toutes les instances du type). */
    String focusId;

    private final Map<String, Boolean> nameCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> classCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> callerCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> appCache = new ConcurrentHashMap<>();

    /** Avant le premier contact avec le serveur : rien n'est enregistré. */
    static RuntimeConfig offline() {
        return new RuntimeConfig();
    }

    @SuppressWarnings("unchecked")
    static RuntimeConfig from(Map<String, Object> m) {
        RuntimeConfig c = new RuntimeConfig();
        c.recording = Boolean.TRUE.equals(m.get("recording"));
        Object types = m.get("types");
        if (types instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) types).entrySet())
                if (Boolean.TRUE.equals(e.getValue()))
                    c.types.add(e.getKey());
        }
        Object f = m.get("focus");
        String focusEntity = f instanceof Map ? str(((Map<String, Object>) f).get("entity")) : null;
        if (focusEntity != null) {
            Map<String, Object> fm = (Map<String, Object>) f;
            c.focus = true;
            c.focusId = str(fm.get("id"));
            c.types.clear();
            c.types.addAll(Arrays.asList("LOAD", "PROXY", "COLLECTION", "UTIL", "SESSION", "LAZY_ERROR"));
            if (!Boolean.FALSE.equals(fm.get("access")))
                c.types.add("ACCESS");
        }
        for (String e : focusEntity != null ? Arrays.asList(focusEntity) : strings(m.get("entities"))) {
            if (e.equals("*"))
                c.allEntities = true;
            else if (e.contains("*"))
                c.globEntities.add(Pattern.compile(AgentConfig.globToRegex(e)));
            else if (e.indexOf('.') < 0)
                c.simpleEntities.add(e);
            else
                c.exactEntities.add(e);
        }
        c.callerPackages = strings(m.get("callerPackages"));
        List<String> lib = strings(m.get("libraryPackages"));
        if (!lib.isEmpty())
            c.libraryPackages = lib;
        for (String e : strings(m.get("callerExclude")))
            c.callerExclude.add(Pattern.compile(AgentConfig.globToRegex(e) + (e.endsWith(".") ? ".*" : "(\\$.*)?")));
        c.accessExclude.addAll(strings(m.get("accessExclude")));
        if (m.get("ignoreHibernateAccess") instanceof Boolean)
            c.ignoreHibernateAccess = (Boolean) m.get("ignoreHibernateAccess");
        c.accessStacks = Boolean.TRUE.equals(m.get("accessStacks"));
        c.loadStacks = Boolean.TRUE.equals(m.get("loadStacks"));
        if (m.get("snapshots") instanceof Boolean)
            c.snapshots = (Boolean) m.get("snapshots");
        if (m.get("stackDepth") instanceof Number)
            c.stackDepth = ((Number) m.get("stackDepth")).intValue();
        if (c.focus)
            c.snapshots = true;
        return c;
    }

    private static String str(Object o) {
        String s = o == null ? null : String.valueOf(o).trim();
        return s == null || s.isEmpty() ? null : s;
    }

    /** Mode focus : l'id fait partie de ceux suivis (toujours vrai sans id). {@code ids} : un id, ou une liste "1,2,3" (UTIL). */
    boolean focusId(String ids) {
        if (focusId == null)
            return true;
        if (ids == null)
            return false;
        if (ids.equals(focusId))
            return true;
        for (String id : ids.split(","))
            if (id.equals(focusId))
                return true;
        return false;
    }

    /** Mode focus : l'entité (nom, id) est celle suivie. */
    boolean focusRef(String entity, String eid) {
        return entity != null && matchesEntity(entity) && focusId(eid);
    }

    private static List<String> strings(Object o) {
        List<String> r = new ArrayList<>();
        if (o instanceof List)
            for (Object x : (List<?>) o)
                if (x != null && !x.toString().trim().isEmpty())
                    r.add(x.toString().trim());
        return r;
    }

    boolean on(String type) {
        return recording && types.contains(type);
    }

    /** Nom d'entité Hibernate (FQCN en général). */
    boolean matchesEntity(String name) {
        if (allEntities)
            return true;
        if (name == null)
            return false;
        Boolean r = nameCache.get(name);
        if (r == null) {
            r = computeMatch(name);
            nameCache.put(name, r);
        }
        return r;
    }

    /** Classe d'instance : une sous-classe d'une entité listée est aussi concernée. */
    boolean matchesEntity(Class<?> cls) {
        if (allEntities)
            return true;
        Boolean r = classCache.get(cls);
        if (r == null) {
            r = false;
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                if (computeMatch(c.getName())) {
                    r = true;
                    break;
                }
            }
            classCache.put(cls, r);
        }
        return r;
    }

    private boolean computeMatch(String name) {
        if (exactEntities.contains(name))
            return true;
        int dot = name.lastIndexOf('.');
        if (simpleEntities.contains(name.substring(dot + 1)))
            return true;
        for (Pattern p : globEntities)
            if (p.matcher(name).matches())
                return true;
        return false;
    }

    /** Frame "métier" : dans les packages appelants, hors exclusions et hors proxies générés. */
    boolean isCaller(String className) {
        Boolean r = callerCache.get(className);
        if (r == null) {
            r = false;
            if (!className.contains("$HibernateProxy") && !className.contains("$$")) {
                r = isApp(className);
                if (r)
                    for (Pattern p : callerExclude)
                        if (p.matcher(className).matches()) {
                            r = false;
                            break;
                        }
            }
            callerCache.put(className, r);
        }
        return r;
    }

    /** Frame applicatif : dans les packages configurés, ou hors bibliothèques si aucun package n'est configuré. */
    boolean isApp(String className) {
        Boolean r = appCache.get(className);
        if (r == null) {
            r = computeApp(className);
            appCache.put(className, r);
        }
        return r;
    }

    private boolean computeApp(String className) {
        if (className.contains("$HibernateProxy") || className.contains("$$") || className.startsWith("ch.tecost.profiler."))
            return false;
        if (callerPackages.isEmpty())
            return !isLibrary(className);
        for (String p : callerPackages)
            if (className.startsWith(p))
                return true;
        return false;
    }

    boolean isLibrary(String className) {
        for (String p : libraryPackages)
            if (className.startsWith(p))
                return true;
        return false;
    }
}
