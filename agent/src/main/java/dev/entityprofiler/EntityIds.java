package dev.entityprofiler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lecture de l'identifiant d'une instance d'entité, selon les règles des profils ({@code settings.entityIds}) :
 * <pre>
 * "entityIds": [
 *   { "extends": "com.acme.model.AbstractEntity", "id": "pk" },        champ (lu directement : rien n'est déclenché)
 *   { "class": ["com.acme.legacy.*"], "id": "getIdentifiant()" }       méthode sans argument
 * ]
 * </pre>
 * Critères combinés en OU : {@code class} (FQCN, nom simple ou glob, sur la classe ou une super-classe), {@code extends}
 * (classe ou interface parente). Première règle qui correspond. Une classe désignée par une règle est traitée comme une
 * entité même sans annotation {@code @Entity} (mapping XML).
 * <p>
 * Sans règle : champ annoté {@code @Id} / {@code @EmbeddedId} (JPA javax / jakarta), sinon méthode {@code getId()}.
 * <p>
 * Utilisé quand seule l'instance est connue (cible d'une opération, getter, référence d'un instantané). Quand Hibernate
 * fournit l'identifiant (LOAD, proxy, clé de collection), c'est le sien qui est pris : la règle doit donc désigner
 * l'identifiant persistant, pour que les événements d'une même entité portent le même id.
 */
final class EntityIds {

    private static final class Rule {
        final List<String> exact = new ArrayList<>();
        final List<Pattern> globs = new ArrayList<>();
        final List<String> supertypes = new ArrayList<>();
        final String field;
        final String method;

        Rule(Map<?, ?> m) {
            for (String c : Probe.strings(m.get("class"))) {
                if (c.contains("*"))
                    globs.add(Pattern.compile(AgentConfig.globToRegex(c)));
                else
                    exact.add(c);
            }
            supertypes.addAll(Probe.strings(m.get("extends")));
            String id = m.get("id") == null ? "" : String.valueOf(m.get("id")).trim();
            if (id.endsWith("()")) {
                method = id.substring(0, id.length() - 2);
                field = null;
            } else {
                field = id.isEmpty() ? null : id;
                method = null;
            }
        }

        boolean valid() {
            return (field != null || method != null) && !(exact.isEmpty() && globs.isEmpty() && supertypes.isEmpty());
        }

        boolean matches(Class<?> cls) {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                String n = c.getName();
                if (exact.contains(n) || exact.contains(Reflect.simple(n)))
                    return true;
                for (Pattern p : globs)
                    if (p.matcher(n).matches())
                        return true;
                if (!supertypes.isEmpty() && (supertypes.contains(n) || implementsAny(c)))
                    return true;
            }
            return false;
        }

        private boolean implementsAny(Class<?> c) {
            for (Class<?> i : c.getInterfaces())
                if (supertypes.contains(i.getName()) || implementsAny(i))
                    return true;
            return false;
        }
    }

    /** Moyen de lire l'id d'une classe, résolu une fois. */
    private static final class Accessor {
        static final Accessor NONE = new Accessor(false, null, null);
        final boolean declared;
        final Field field;
        final Method method;

        Accessor(boolean declared, Field field, Method method) {
            this.declared = declared;
            this.field = field;
            this.method = method;
        }
    }

    private static volatile EntityIds current = new EntityIds(Collections.emptyList(), "");

    private final List<Rule> rules;
    private final String signature;
    private final Map<Class<?>, Accessor> cache = new ConcurrentHashMap<>();

    private EntityIds(List<Rule> rules, String signature) {
        this.rules = rules;
        this.signature = signature;
    }

    /** Règles reçues du serveur (poll) : reconstruites seulement si elles ont changé. */
    static void configure(Object spec) {
        String sig = String.valueOf(spec);
        if (Objects.equals(sig, current.signature))
            return;
        List<Rule> rules = new ArrayList<>();
        if (spec instanceof List)
            for (Object o : (List<?>) spec)
                if (o instanceof Map) {
                    Rule r = new Rule((Map<?, ?>) o);
                    if (r.valid())
                        rules.add(r);
                    else
                        Log.warn("entityIds: rule ignored (class / extends and id required): " + o);
                }
        current = new EntityIds(rules, sig);
        if (!rules.isEmpty())
            Log.info(rules.size() + " entity id rule(s)");
    }

    /** Classe désignée par une règle (entité même sans @Entity). */
    static boolean declares(Class<?> cls) {
        EntityIds ids = current;
        return !ids.rules.isEmpty() && ids.accessor(cls).declared;
    }

    /** Identifiant d'une instance réelle (pas un proxy), null s'il est illisible. */
    static Object read(Object entity) {
        if (entity == null)
            return null;
        Accessor a = current.accessor(entity.getClass());
        try {
            if (a.field != null)
                return a.field.get(entity);
            if (a.method != null)
                return a.method.invoke(entity);
        } catch (Throwable e) {
            // module fermé, exception de l'application
        }
        return null;
    }

    private Accessor accessor(Class<?> cls) {
        Accessor a = cache.get(cls);
        if (a == null) {
            a = resolve(cls);
            cache.put(cls, a);
        }
        return a;
    }

    private Accessor resolve(Class<?> cls) {
        for (Rule r : rules) {
            if (!r.matches(cls))
                continue;
            Field f = r.field != null ? field(cls, r.field) : null;
            Method m = r.method != null ? method(cls, r.method) : null;
            if (f == null && m == null)
                Log.warn("entityIds: " + (r.field != null ? "field " + r.field : "method " + r.method + "()") + " not found on " + cls.getName());
            return new Accessor(true, f, m);
        }
        Field f = annotatedId(cls);
        if (f != null)
            return new Accessor(false, f, null);
        Method m = method(cls, "getId");
        return m == null ? Accessor.NONE : new Accessor(false, null, m);
    }

    private static Field field(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (!Modifier.isStatic(f.getModifiers()))
                    return accessible(f);
            } catch (NoSuchFieldException e) {
                // super-classe
            }
        }
        return null;
    }

    private static Field annotatedId(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                for (java.lang.annotation.Annotation an : f.getDeclaredAnnotations()) {
                    String n = an.annotationType().getName();
                    if (n.endsWith("persistence.Id") || n.endsWith("persistence.EmbeddedId"))
                        return accessible(f);
                }
        return null;
    }

    private static Method method(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // super-classe
            } catch (RuntimeException e) {
                return null; // module fermé
            }
        }
        return null;
    }

    private static Field accessible(Field f) {
        try {
            f.setAccessible(true);
            return f;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
