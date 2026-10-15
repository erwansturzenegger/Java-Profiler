package dev.entityprofiler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.BiFunction;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Code injecté (inliné) dans les classes instrumentées.
 * <p>
 * Ce code s'exécute dans le classloader de l'application (module WildFly, WEB-INF/lib...), qui ne voit pas
 * forcément les classes de l'agent. Il ne référence donc que des types java.* : les handlers de
 * {@link Recorder} sont publiés comme {@link BiFunction} dans les propriétés système et récupérés ici par clé.
 * Aucun -Djboss.modules.system.pkgs n'est nécessaire, et l'attachement à chaud fonctionne.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public final class Hooks {

    static final String PROXY = "dev.entityprofiler.proxy";
    static final String COLLECTION = "dev.entityprofiler.collection";
    static final String POST_LOAD = "dev.entityprofiler.postload";
    static final String ACCESS = "dev.entityprofiler.access";
    static final String UTIL = "dev.entityprofiler.util";
    static final String SQL = "dev.entityprofiler.sql";
    static final String LAZY_EX = "dev.entityprofiler.lazyex";
    static final String REQUEST = "dev.entityprofiler.request";
    static final String EL = "dev.entityprofiler.el";
    static final String SESSION = "dev.entityprofiler.session";
    static final String QUERY = "dev.entityprofiler.query";
    static final String LAZY_COLL = "dev.entityprofiler.lazycoll";
    static final String EXIT = "dev.entityprofiler.exit";

    private Hooks() {
    }

    /** Identifiant de la sonde ({@link Probe#id}), lié comme constante au moment de l'instrumentation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface ProbeId {
    }

    /** org.hibernate.proxy.AbstractLazyInitializer#initialize() */
    public static final class ProxyInit {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.This Object self) {
            Object f = System.getProperties().get(PROXY);
            return f == null ? null : ((BiFunction) f).apply(self, null);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { null, thrown });
            }
        }
    }

    /** org.hibernate.event.internal.DefaultInitializeCollectionEventListener#onInitializeCollection(event) */
    public static final class CollectionInit {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.Argument(0) Object event) {
            Object f = System.getProperties().get(COLLECTION);
            return f == null ? null : ((BiFunction) f).apply(event, null);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { null, thrown });
            }
        }
    }

    /** org.hibernate.event.internal.DefaultPostLoadEventListener#onPostLoad(event) : chaque entité hydratée. */
    public static final class PostLoad {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.Argument(0) Object event) {
            Object f = System.getProperties().get(POST_LOAD);
            if (f != null)
                ((BiFunction) f).apply(event, null);
        }
    }

    /** Getters des entités (getXxx / isXxx sans argument). */
    public static final class Access {
        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object self, @Advice.Origin("#m") String method,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result) {
            Object f = System.getProperties().get(ACCESS);
            if (f != null)
                ((BiFunction) f).apply(self, new Object[] { method, result });
        }
    }

    /** Méthodes des sondes "method" (handler générique : durée, arguments, entité ciblée). */
    public static final class Util {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.This(optional = true) Object self, @Advice.Origin("#t") String type,
                @Advice.Origin("#m") String method, @Advice.Origin("#d") String descriptor, @ProbeId String probe,
                @Advice.AllArguments Object[] args) {
            Object f = System.getProperties().get(UTIL);
            return f == null ? null : ((BiFunction) f).apply(new Object[] { type, method, descriptor, self, probe }, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { result, thrown });
            }
        }
    }

    /** Opérations de session (SessionImpl#find/get/refresh/merge/evict/detach/clear/close). */
    public static final class Session {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.This Object self, @Advice.Origin("#m") String method, @Advice.AllArguments Object[] args) {
            Object f = System.getProperties().get(SESSION);
            return f == null ? null : ((BiFunction) f).apply(new Object[] { method, self }, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { result, thrown });
            }
        }
    }

    /** AbstractPersistentCollection#throwLazyInitializationException : la collection en échec (clé, propriétaire). */
    public static final class LazyCollection {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@Advice.This Object self) {
            Object f = System.getProperties().get(LAZY_COLL);
            if (f != null)
                ((BiFunction) f).apply(self, null);
        }
    }

    /** Exécution d'une requête Hibernate (list(), appelé aussi par getResultList / getSingleResult / uniqueResult). */
    public static final class Query {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.This Object self) {
            Object f = System.getProperties().get(QUERY);
            return f == null ? null : ((BiFunction) f).apply(self, null);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { result, thrown });
            }
        }
    }

    /** org.hibernate.engine.jdbc.internal.ResultSetReturnImpl#extract/execute/executeUpdate */
    public static final class Sql {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.Origin("#m") String method, @Advice.AllArguments Object[] args) {
            Object f = System.getProperties().get(SQL);
            return f == null ? null : ((BiFunction) f).apply(method, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { null, thrown });
            }
        }
    }

    /** Constructeurs de LazyInitializationException (et exceptions configurées). */
    public static final class LazyException {
        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This Object self) {
            Object f = System.getProperties().get(LAZY_EX);
            if (f != null)
                ((BiFunction) f).apply(self, null);
        }
    }

    /** HttpServlet#service(ServletRequest, ServletResponse), javax et jakarta. */
    public static final class Request {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.Argument(0) Object request, @Advice.Argument(1) Object response) {
            Object f = System.getProperties().get(REQUEST);
            return f == null ? null : ((BiFunction) f).apply(request, response);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { null, thrown });
            }
        }
    }

    /** ValueExpression#getValue et MethodExpression#invoke : mémorise l'expression EL en cours d'évaluation. */
    public static final class El {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static Object enter(@Advice.This Object expression) {
            Object f = System.getProperties().get(EL);
            return f == null ? null : ((BiFunction) f).apply(expression, null);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable thrown) {
            if (token != null) {
                Object f = System.getProperties().get(EXIT);
                if (f != null)
                    ((BiFunction) f).apply(token, new Object[] { null, thrown });
            }
        }
    }
}
