package ch.tecost.profiler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accès réflexif aux objets Hibernate / Servlet / EL, sans dépendance de compilation :
 * fonctionne avec Hibernate 5 et 6, javax et jakarta.
 */
final class Reflect {

    private static final Method NONE;
    static {
        try {
            NONE = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ClassValue<Map<String, Method>> METHODS = new ClassValue<Map<String, Method>>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static final ClassValue<Boolean> PROXY = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return implementsNamed(type, "org.hibernate.proxy.HibernateProxy");
        }
    };

    private static final ClassValue<Boolean> PERSISTENT_COLLECTION = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return implementsNamed(type, "org.hibernate.collection.spi.PersistentCollection")
                    || implementsNamed(type, "org.hibernate.collection.internal.PersistentCollection");
        }
    };

    private Reflect() {
    }

    static Object call(Object target, String name) {
        if (target == null)
            return null;
        Method m = find(target.getClass(), name, 0);
        if (m == null)
            return null;
        try {
            return m.invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }

    static Object call(Object target, String name, Object arg) {
        if (target == null)
            return null;
        Method m = find(target.getClass(), name, 1);
        if (m == null)
            return null;
        try {
            return m.invoke(target, arg);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Method find(Class<?> cls, String name, int argc) {
        Map<String, Method> cache = METHODS.get(cls);
        String key = name + "/" + argc;
        Method m = cache.get(key);
        if (m == null) {
            m = lookup(cls, name, argc);
            cache.put(key, m == null ? NONE : m);
        }
        return m == NONE ? null : m;
    }

    private static Method lookup(Class<?> cls, String name, int argc) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                // Une méthode publique d'une classe non publique doit passer par l'interface ou être rendue accessible
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // module fermé : on tente l'appel quand même
                }
                return m;
            }
        }
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    try {
                        m.setAccessible(true);
                        return m;
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static boolean implementsNamed(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name))
                return true;
            for (Class<?> i : c.getInterfaces())
                if (implementsNamed(i, name))
                    return true;
        }
        return false;
    }

    static boolean isProxy(Object o) {
        return o != null && PROXY.get(o.getClass());
    }

    static boolean isPersistentCollection(Object o) {
        return o != null && PERSISTENT_COLLECTION.get(o.getClass());
    }

    static boolean isUninitializedProxy(Object o) {
        if (!isProxy(o))
            return false;
        Object li = call(o, "getHibernateLazyInitializer");
        return Boolean.TRUE.equals(call(li, "isUninitialized"));
    }

    static Object proxyId(Object proxy) {
        Object li = call(proxy, "getHibernateLazyInitializer");
        return lazyInitializerId(li);
    }

    /** getInternalIdentifier() n'initialise jamais le proxy (contrairement à getIdentifier() en mode JPA compliance). */
    static Object lazyInitializerId(Object li) {
        Object id = call(li, "getInternalIdentifier");
        return id != null ? id : call(li, "getIdentifier");
    }

    /** Nom de classe sans les sous-classes générées (proxies Hibernate, Weld...). */
    static String entityClassName(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && (c.getName().contains("$HibernateProxy") || c.getName().contains("$$")))
            c = c.getSuperclass();
        return c == null ? cls.getName() : c.getName();
    }

    /** Identité JVM d'un objet (hexa), sans appeler hashCode() de l'application. */
    static String identity(Object o) {
        return o == null ? null : Integer.toHexString(System.identityHashCode(o));
    }

    /**
     * Référence vers une entité sans déclencher de chargement : { entité, id, identité }.
     * Proxy initialisé : identité de l'implémentation. Proxy non initialisé : "p" + identité du proxy.
     * Collection persistante : propriétaire. Collection simple d'entités : élément type et ids (20 max).
     * Retourne null si l'objet n'est pas une entité.
     */
    static String[] entityRef(Object o) {
        if (o == null)
            return null;
        Class<?> c = o.getClass();
        if (isProxy(o)) {
            Object li = call(o, "getHibernateLazyInitializer");
            String name = String.valueOf(call(li, "getEntityName"));
            String id = shortId(lazyInitializerId(li));
            if (Boolean.TRUE.equals(call(li, "isUninitialized")))
                return new String[] { name, id, "p" + identity(o) };
            return new String[] { name, id, identity(call(li, "getImplementation")) };
        }
        if (isPersistentCollection(o)) {
            Object owner = call(o, "getOwner");
            Object role = call(o, "getRole");
            String r = role == null ? null : String.valueOf(role);
            String ownerName = owner != null ? entityClassName(owner.getClass())
                    : r != null && r.lastIndexOf('.') > 0 ? r.substring(0, r.lastIndexOf('.')) : null;
            return ownerName == null ? null : new String[] { ownerName, shortId(call(o, "getKey")), identity(owner) };
        }
        if (looksLikeEntity(c))
            return new String[] { entityClassName(c), shortId(EntityIds.read(o)), identity(o) };
        if (o instanceof Collection && !(o instanceof Map)) {
            String type = null;
            StringBuilder ids = new StringBuilder();
            int n = 0;
            try {
                for (Object x : (Collection<?>) o) {
                    String[] ref = entityRef(x);
                    if (ref == null)
                        return null;
                    type = type == null ? ref[0] : type;
                    if (n++ < 20)
                        ids.append(ids.length() > 0 ? "," : "").append(ref[1]);
                }
            } catch (Throwable e) {
                return null;
            }
            return type == null ? null : new String[] { type, n > 20 ? ids + ",…" : ids.toString(), null };
        }
        return null;
    }

    private static final int MAX_SNAPSHOT = 80;

    /** Champs d'association d'une classe d'entité (collections, maps, références vers une entité), hors static / transient. */
    private static final ClassValue<Field[]> ASSOCIATIONS = new ClassValue<Field[]>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> out = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic() || hasAnnotation(f, "Transient"))
                        continue;
                    Class<?> t = f.getType();
                    if (!Collection.class.isAssignableFrom(t) && !Map.class.isAssignableFrom(t) && !looksLikeEntity(t))
                        continue;
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (RuntimeException ignored) {
                        // module fermé
                    }
                }
            }
            return out.toArray(new Field[0]);
        }
    };

    private static boolean hasAnnotation(Field f, String simpleName) {
        for (java.lang.annotation.Annotation a : f.getDeclaredAnnotations())
            if (a.annotationType().getSimpleName().equals(simpleName))
                return true;
        return false;
    }

    /**
     * État d'une instance d'entité, lu sur les champs (jamais via les getters : aucun chargement déclenché).
     * Entrées {@code nom|genre|état|détail} :
     * <ul>
     * <li>{@code @|S|<session>} attachée à la session, {@code @|D|<session>} détachée (ou session fermée),
     * {@code @|P|} proxy non initialisé (pas encore d'instance réelle)</li>
     * <li>{@code stays|C|I|3} collection initialisée (3 éléments), {@code stays|C|L|} collection non initialisée</li>
     * <li>{@code doctor|R|I|Doctor#5} référence chargée, {@code doctor|R|L|Doctor#5} proxy non initialisé</li>
     * <li>{@code doctor|N||} null</li>
     * </ul>
     *
     * @param session session connue de l'appelant (plus fiable que la déduction depuis les collections), ou null
     */
    static List<String> snapshot(Object o, Object session) {
        if (o == null)
            return null;
        if (isProxy(o)) {
            Object li = call(o, "getHibernateLazyInitializer");
            if (Boolean.TRUE.equals(call(li, "isUninitialized"))) {
                List<String> out = new ArrayList<>();
                out.add("@|P|");
                return out;
            }
            o = call(li, "getImplementation");
            if (o == null)
                return null;
        }
        if (!looksLikeEntity(o.getClass()))
            return null;
        List<String> out = new ArrayList<>();
        out.add(null); // attachement, renseigné à la fin
        boolean sawCollection = false;
        String collectionSession = null;
        for (Field f : ASSOCIATIONS.get(o.getClass())) {
            if (out.size() >= MAX_SNAPSHOT)
                break;
            Object v;
            try {
                v = f.get(o);
            } catch (Throwable e) {
                continue;
            }
            String name = f.getName();
            if (v == null) {
                out.add(name + "|N||");
            } else if (isPersistentCollection(v)) {
                sawCollection = true;
                Object s = call(v, "getSession");
                if (s != null && collectionSession == null && !Boolean.FALSE.equals(call(s, "isOpen")))
                    collectionSession = identity(s);
                out.add(Boolean.TRUE.equals(call(v, "wasInitialized")) ? name + "|C|I|" + count(v)
                        : name + "|C|L|");
            } else if (v instanceof Collection || v instanceof Map) {
                out.add(name + "|C|I|" + count(v));
            } else {
                String[] ref = entityRef(v);
                String target = ref == null ? v.getClass().getSimpleName() : simple(ref[0]) + "#" + ref[1];
                out.add(name + (isUninitializedProxy(v) ? "|R|L|" : "|R|I|") + target);
            }
        }
        out.set(0, attachment(o, session, sawCollection, collectionSession));
        if (out.get(0) == null)
            out.remove(0);
        return out;
    }

    private static String attachment(Object o, Object session, boolean sawCollection, String collectionSession) {
        if (session != null) {
            if (Boolean.FALSE.equals(call(session, "isOpen")))
                return "@|D|" + identity(session);
            Object contains = call(session, "contains", o);
            if (contains instanceof Boolean)
                return ((Boolean) contains ? "@|S|" : "@|D|") + identity(session);
        }
        // Sans session connue : une collection persistante reliée à une session ouverte => attachée
        if (collectionSession != null)
            return "@|S|" + collectionSession;
        return sawCollection ? "@|D|" : null;
    }

    static String shortId(Object id) {
        if (id == null)
            return null;
        String s = String.valueOf(id);
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** Description d'un argument / résultat qui ne déclenche jamais de chargement lazy. */
    static String describe(Object o) {
        if (o == null)
            return "null";
        Class<?> c = o.getClass();
        if (isProxy(o)) {
            String n = simple(entityClassName(c)) + "#" + shortId(proxyId(o));
            return isUninitializedProxy(o) ? n + " (proxy non init.)" : n + " (proxy)";
        }
        if (isPersistentCollection(o)) {
            Object role = call(o, "getRole");
            String r = role == null ? c.getSimpleName() : shortRole(String.valueOf(role));
            if (!Boolean.TRUE.equals(call(o, "wasInitialized")))
                return r + " (lazy non init.)";
            return r + sizeOf(o);
        }
        if (c.getSimpleName().contains("Uninitialized"))
            return c.getSimpleName();
        if (o instanceof Collection || o instanceof Map)
            return c.getSimpleName() + sizeOf(o);
        if (o instanceof Object[])
            return c.getComponentType().getSimpleName() + "[" + ((Object[]) o).length + "]";
        if (o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Enum)
            return truncate(String.valueOf(o), 60);
        if (looksLikeEntity(c)) {
            Object id = EntityIds.read(o);
            return simple(entityClassName(c)) + "#" + shortId(id);
        }
        // Objets "définition" (RelationDefinition...) : leur toString est en général parlant
        try {
            return truncate(String.valueOf(o), 120);
        } catch (Throwable e) {
            return c.getSimpleName();
        }
    }

    /** Taille d'une collection / map déjà chargée, "" si illisible. */
    private static String count(Object o) {
        try {
            if (o instanceof Collection)
                return String.valueOf(((Collection<?>) o).size());
            if (o instanceof Map)
                return String.valueOf(((Map<?, ?>) o).size());
        } catch (Throwable ignored) {
            // collection détachée
        }
        return "";
    }

    private static String sizeOf(Object o) {
        try {
            if (o instanceof Collection)
                return "[" + ((Collection<?>) o).size() + "]";
            if (o instanceof Map)
                return "[" + ((Map<?, ?>) o).size() + "]";
        } catch (Throwable ignored) {
            // LazyInitializationException possible sur une collection détachée
        }
        return "";
    }

    private static final ClassValue<Boolean> ENTITY = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                for (java.lang.annotation.Annotation a : c.getDeclaredAnnotations()) {
                    String n = a.annotationType().getName();
                    if (n.endsWith("persistence.Entity") || n.endsWith("persistence.MappedSuperclass"))
                        return true;
                }
            return false;
        }
    };

    static boolean looksLikeEntity(Class<?> c) {
        return ENTITY.get(c) || EntityIds.declares(c);
    }

    static String simple(String fqcn) {
        if (fqcn == null)
            return null;
        int dot = fqcn.lastIndexOf('.', fqcn.indexOf('$') > 0 ? fqcn.indexOf('$') : fqcn.length());
        return fqcn.substring(dot + 1);
    }

    /** ch.x.Patient.stays -> Patient.stays */
    static String shortRole(String role) {
        int last = role.lastIndexOf('.');
        int prev = last > 0 ? role.lastIndexOf('.', last - 1) : -1;
        return role.substring(prev + 1);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
