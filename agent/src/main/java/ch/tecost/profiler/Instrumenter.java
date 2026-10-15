package ch.tecost.profiler;

import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Installe les sondes reçues du serveur et les remplace à chaud quand le serveur en envoie une nouvelle version :
 * l'ancienne instrumentation est retirée (bytecode d'origine restauré), puis la nouvelle est appliquée,
 * y compris aux classes déjà chargées. Aucun nom de classe applicatif n'est codé en dur ici.
 */
final class Instrumenter {

    private final Instrumentation inst;
    private final Recorder recorder;
    private ResettableClassFileTransformer transformer;
    private String version;
    private String error;

    /** Statistiques par sonde, remontées au serveur : classes instrumentées (échantillon). */
    static final class Stats {
        final AtomicInteger classes = new AtomicInteger();
        final Set<String> sample = ConcurrentHashMap.newKeySet();
    }

    private volatile Map<String, Stats> stats = new LinkedHashMap<>();

    Instrumenter(Instrumentation inst, Recorder recorder) {
        this.inst = inst;
        this.recorder = recorder;
    }

    String version() {
        return version;
    }

    String error() {
        return error;
    }

    Map<String, Stats> stats() {
        return stats;
    }

    /** Applique la spécification { version, probes: [...] } envoyée par le serveur. */
    @SuppressWarnings("unchecked")
    synchronized void apply(Map<String, Object> spec) {
        String v = String.valueOf(spec.get("version"));
        List<Probe> probes = new ArrayList<>();
        Object list = spec.get("probes");
        if (list instanceof List)
            for (Object o : (List<?>) list)
                if (o instanceof Map) {
                    Probe p = Probe.parse((Map<String, Object>) o);
                    if (p != null)
                        probes.add(p);
                }
        reset();
        recorder.setProbes(probes);
        Map<String, Stats> s = new LinkedHashMap<>();
        for (Probe p : probes)
            s.put(p.id, new Stats());
        stats = s;
        try {
            transformer = install(probes, s);
            error = null;
        } catch (Throwable e) {
            error = String.valueOf(e);
            Log.warn("cannot install probes: " + e);
        }
        version = v;
    }

    /** Retire l'instrumentation : les classes retrouvent leur bytecode d'origine. */
    synchronized void reset() {
        if (transformer == null)
            return;
        try {
            transformer.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        } catch (Throwable e) {
            Log.warn("cannot restore original classes: " + e);
        }
        transformer = null;
        version = null;
    }

    private ResettableClassFileTransformer install(List<Probe> probes, Map<String, Stats> stats) {
        AtomicInteger transformed = new AtomicInteger();
        AgentBuilder ab = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new Listener(transformed))
                .ignore(nameStartsWith("net.bytebuddy.")
                        .or(nameStartsWith("ch.tecost.profiler."))
                        .or(nameStartsWith("java."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun.proxy."))
                        .or(nameContains("$HibernateProxy$"))
                        .or(nameContains("$$"))
                        .or(isSynthetic()));
        for (Probe p : probes)
            ab = ab.type(p.typeMatcher()).transform(transformer(p, stats.get(p.id)));
        ResettableClassFileTransformer t = ab.installOn(inst);
        Log.info(probes.size() + " probes installed (" + transformed.get() + " already loaded classes retransformed)");
        return t;
    }

    private AgentBuilder.Transformer transformer(Probe p, Stats st) {
        Advice advice = Advice.withCustomMapping().bind(Hooks.ProbeId.class, p.id).to(p.handler.advice);
        return (DynamicType.Builder<?> builder, TypeDescription type, ClassLoader loader, JavaModule module,
                java.security.ProtectionDomain pd) -> {
            if (st.classes.incrementAndGet() <= 20)
                st.sample.add(type.getName());
            if (p.handler == Probe.Handler.GETTER && isEntity(type))
                recorder.knownEntities.add(type.getName());
            return builder.visit(advice.on(p.methodMatcher()));
        };
    }

    /** Classe annotée @Entity (JPA javax / jakarta, ou toute annotation nommée Entity). */
    private static boolean isEntity(TypeDescription type) {
        for (AnnotationDescription a : type.getDeclaredAnnotations())
            if ("Entity".equals(a.getAnnotationType().getSimpleName()))
                return true;
        return false;
    }

    private static final class Listener extends AgentBuilder.Listener.Adapter {
        private final AtomicInteger transformed;

        Listener(AtomicInteger transformed) {
            this.transformed = transformed;
        }

        @Override
        public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded,
                DynamicType dynamicType) {
            if (loaded)
                transformed.incrementAndGet();
            Log.debug("transformed " + type.getName());
        }

        @Override
        public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable throwable) {
            // Fréquent et sans gravité (classe dont une dépendance est absente) : visible avec debug=true
            Log.debug("cannot transform " + typeName + ": " + throwable);
        }
    }
}
