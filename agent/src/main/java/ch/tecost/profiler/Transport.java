package ch.tecost.profiler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Envoi des événements par lots (HTTP POST JSON) et récupération de la config dynamique (poll chaque seconde).
 * Thread daemon dédié : les threads applicatifs ne font que déposer dans une file.
 */
final class Transport implements Runnable {

    private static final int MAX_QUEUE = 500_000;
    private static final int BATCH = 2_000;

    private final String server;
    private final String agentId;
    private final String host;
    private final String pid;
    private final String agentName;
    private final java.util.List<String> profiles;
    private final long startedAt = System.currentTimeMillis();
    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private Recorder recorder;
    private Instrumenter instrumenter;
    private long lastPoll;
    private long lastSuccess;
    private boolean connected;
    private volatile boolean running = true;
    private Runnable onDetach;

    Transport(AgentConfig cfg) {
        this.server = cfg.server;
        String pid = ManagementFactory.getRuntimeMXBean().getName(); // pid@host
        String h;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = "unknown";
        }
        this.host = h;
        this.pid = pid.contains("@") ? pid.substring(0, pid.indexOf('@')) : pid;
        this.agentId = host + ":" + this.pid;
        this.agentName = cfg.name != null ? cfg.name : agentId;
        this.profiles = cfg.profiles;
    }

    void setInstrumenter(Instrumenter instrumenter) {
        this.instrumenter = instrumenter;
    }

    void setRecorder(Recorder recorder) {
        this.recorder = recorder;
    }

    /** Appelé (sur le thread de transport) quand le serveur demande le détachement de l'agent. */
    void setOnDetach(Runnable onDetach) {
        this.onDetach = onDetach;
    }

    /** Envoie ce qui reste dans la file, prévient le serveur et termine le thread. */
    void stop() {
        running = false;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownFlush);
        } catch (IllegalStateException e) {
            // JVM en cours d'arrêt
        }
        flush();
        queue.clear();
        size.set(0);
        StringBuilder sb = new StringBuilder("{\"agent\":");
        Json.str(sb, agentId);
        sb.append('}');
        post("/api/agent/bye", sb.toString());
    }

    /** Premier contact synchrone (démarrage de la JVM) : configuration et sondes installées avant de rendre la main. */
    void pollNow() {
        try {
            lastPoll = System.currentTimeMillis();
            poll();
        } catch (Throwable e) {
            Log.debug("first poll failed: " + e);
        }
        if (instrumenter.version() == null)
            Log.warn("server " + server + " not reachable at startup: probes will be installed on first contact");
    }

    /** Arrêt de la JVM : les derniers événements (spans fermés juste avant) sont envoyés. */
    private final Thread shutdownFlush = new Thread(this::flush, "entity-profiler-shutdown");

    void start() {
        Thread t = new Thread(this, "entity-profiler-transport");
        t.setDaemon(true);
        t.start();
        try {
            Runtime.getRuntime().addShutdownHook(shutdownFlush);
        } catch (IllegalStateException e) {
            // JVM déjà en cours d'arrêt
        }
    }

    void offer(Event e) {
        if (size.get() >= MAX_QUEUE) {
            dropped.incrementAndGet();
            return;
        }
        queue.add(e);
        size.incrementAndGet();
    }

    @Override
    public void run() {
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastPoll >= 1000) {
                    lastPoll = now;
                    poll();
                }
                flush();
                // Serveur injoignable : on arrête d'enregistrer pour ne pas accumuler
                if (connected && System.currentTimeMillis() - lastSuccess > 5000) {
                    connected = false;
                    recorder.rc = RuntimeConfig.offline();
                    Log.warn("server " + server + " unreachable, recording paused");
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                Log.debug("transport error: " + e);
            }
        }
    }

    private void poll() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"agent\":");
        Json.str(sb, agentId);
        Json.field(sb, "name", agentName);
        Json.field(sb, "host", host);
        Json.field(sb, "pid", pid);
        sb.append(",\"startedAt\":").append(startedAt);
        sb.append(",\"queued\":").append(size.get());
        sb.append(",\"dropped\":").append(dropped.get());
        Json.field(sb, "entities", new ArrayList<>(recorder.knownEntities));
        if (!profiles.isEmpty())
            Json.field(sb, "profiles", profiles);
        // Sondes installées : version (le serveur renvoie la spécification si elle a changé) et classes instrumentées
        Json.field(sb, "specVersion", instrumenter.version());
        Json.field(sb, "specError", instrumenter.error());
        sb.append(",\"probes\":{");
        boolean first = true;
        for (Map.Entry<String, Instrumenter.Stats> e : instrumenter.stats().entrySet()) {
            if (!first)
                sb.append(',');
            first = false;
            Json.str(sb, e.getKey());
            sb.append(":{\"classes\":").append(e.getValue().classes.get());
            Json.field(sb, "sample", new ArrayList<>(e.getValue().sample));
            sb.append('}');
        }
        sb.append("}}");
        String resp = post("/api/agent/poll", sb.toString());
        if (resp == null)
            return;
        Object parsed = Json.parse(resp);
        if (parsed instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) parsed).get("detach")) && onDetach != null) {
            onDetach.run();
            return;
        }
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            Object spec = m.get("instrumentation");
            if (spec instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sm = (Map<String, Object>) spec;
                Log.info("applying probes version " + sm.get("version"));
                instrumenter.apply(sm);
                lastSuccess = System.currentTimeMillis(); // la retransformation peut prendre quelques secondes
            }
            EntityIds.configure(m.get("entityIds"));
            RuntimeConfig rc = RuntimeConfig.from(m);
            recorder.rc = rc;
            if (!connected) {
                connected = true;
                Log.info("connected to " + server + " (recording=" + rc.recording + ")");
            }
        }
    }

    private synchronized void flush() {
        while (!queue.isEmpty()) {
            StringBuilder sb = new StringBuilder(64 * 1024);
            sb.append("{\"agent\":");
            Json.str(sb, agentId);
            sb.append(",\"events\":[");
            int n = 0;
            Event e;
            while (n < BATCH && (e = queue.poll()) != null) {
                size.decrementAndGet();
                if (n > 0)
                    sb.append(',');
                e.toJson(sb);
                n++;
            }
            sb.append("]}");
            if (post("/api/agent/events", sb.toString()) == null) {
                dropped.addAndGet(n);
                return;
            }
        }
    }

    private String post(String path, String body) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(server + path).openConnection(Proxy.NO_PROXY);
            con.setConnectTimeout(2000);
            con.setReadTimeout(10000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = con.getOutputStream()) {
                out.write(bytes);
            }
            int code = con.getResponseCode();
            if (code / 100 != 2)
                return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0)
                    bos.write(buf, 0, r);
            }
            lastSuccess = System.currentTimeMillis();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("POST " + path + " failed: " + e);
            return null;
        } finally {
            if (con != null)
                con.disconnect();
        }
    }
}
