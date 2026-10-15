package dev.entityprofiler;

import java.lang.instrument.Instrumentation;

/**
 * Point d'entrée. L'agent ne connaît aucune classe applicative : ce qu'il instrumente (sondes) est décrit
 * dans les profils du serveur (server/profiles/*.json) et lui est envoyé au premier contact.
 * <ul>
 * <li>Au démarrage : {@code -javaagent:entity-profiler-agent.jar=server=http://localhost:7070}</li>
 * <li>À chaud : {@code java -jar entity-profiler-agent.jar <pid> "server=http://localhost:7070"}</li>
 * <li>Lister les JVM locales : {@code java -jar entity-profiler-agent.jar list}</li>
 * </ul>
 * Le détachement est demandé par le serveur (UI) : l'instrumentation est retirée et les classes retrouvent leur
 * bytecode d'origine. Le jar reste dans le classpath système de la JVM (aucun moyen de le décharger), ce qui permet
 * un nouvel attachement ultérieur.
 */
public final class EntityProfilerAgent {

    private static boolean started;
    private static Instrumenter instrumenter;
    private static Transport transport;
    private static Recorder recorder;

    private EntityProfilerAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args, inst, true);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args, inst, false);
    }

    /** Ligne de commande : voir {@link Attach}. */
    public static void main(String[] argv) throws Exception {
        Attach.main(argv);
    }

    /**
     * @param startup au démarrage de la JVM : premier contact synchrone avec le serveur, pour que les sondes soient
     *                installées avant le code applicatif (une méthode déjà en cours d'exécution n'est jamais instrumentée)
     */
    private static synchronized void start(String args, Instrumentation inst, boolean startup) {
        if (started) {
            Log.warn("already started");
            return;
        }
        started = true;
        AgentConfig cfg = AgentConfig.parse(args);
        Log.debug = cfg.debug;
        transport = new Transport(cfg);
        recorder = new Recorder(transport);
        // Les sondes viennent du serveur (profils) : installées au premier contact, remplacées à chaque modification
        instrumenter = new Instrumenter(inst, recorder);
        transport.setRecorder(recorder);
        transport.setInstrumenter(instrumenter);
        transport.setOnDetach(EntityProfilerAgent::stop);
        recorder.install();
        if (startup)
            transport.pollNow();
        transport.start();
        Log.info("started, server=" + cfg.server + (cfg.profiles.isEmpty() ? "" : ", profiles=" + cfg.profiles));
    }

    /** Retire l'instrumentation (bytecode d'origine restauré) et arrête l'envoi. Un nouvel attachement reste possible. */
    static synchronized void stop() {
        if (!started)
            return;
        Log.info("detaching...");
        recorder.uninstall();
        instrumenter.reset();
        transport.stop();
        instrumenter = null;
        transport = null;
        recorder = null;
        started = false;
        Log.info("detached");
    }
}
