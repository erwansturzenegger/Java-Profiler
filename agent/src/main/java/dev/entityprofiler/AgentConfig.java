package dev.entityprofiler;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Configuration statique, lue au démarrage depuis les arguments de l'agent :
 * <pre>
 * -javaagent:entity-profiler-agent.jar=server=http://localhost:7070;name=mon-app;profiles=hibernate,servlet
 * -javaagent:entity-profiler-agent.jar=config=/opt/profiler/profiler.properties
 * </pre>
 * Seuls la connexion et le choix des profils sont ici. Les sondes (quoi instrumenter) viennent des profils du serveur,
 * le filtrage (entités, types d'événements, appelants) de sa configuration dynamique : voir {@link RuntimeConfig}.
 */
final class AgentConfig {

    String server = "http://localhost:7070";
    String name;
    boolean debug;

    /**
     * Profils du serveur à appliquer (noms séparés par des virgules). Vide : profils actifs dont {@code appliesTo}
     * correspond au nom de l'agent.
     */
    List<String> profiles = new ArrayList<>();

    static AgentConfig parse(String args) {
        AgentConfig c = new AgentConfig();
        Properties p = new Properties();
        if (args != null && !args.trim().isEmpty()) {
            for (String part : args.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0)
                    p.setProperty(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        String file = p.getProperty("config");
        if (file != null) {
            Properties fp = new Properties();
            try (InputStream in = new FileInputStream(file)) {
                fp.load(in);
            } catch (IOException e) {
                Log.warn("cannot read config file " + file + ": " + e);
            }
            fp.putAll(p); // les arguments en ligne priment sur le fichier
            p = fp;
        }
        c.server = trimSlash(p.getProperty("server", c.server));
        c.name = p.getProperty("name");
        c.debug = Boolean.parseBoolean(p.getProperty("debug", "false"));
        c.profiles = list(p.getProperty("profiles"));
        return c;
    }

    static List<String> list(String s) {
        List<String> r = new ArrayList<>();
        if (s == null)
            return r;
        for (String x : s.split(","))
            if (!x.trim().isEmpty())
                r.add(x.trim());
        return r;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** '*' = n'importe quelle suite de caractères. */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char ch : glob.toCharArray()) {
            if (ch == '*')
                sb.append(".*");
            else if ("\\.[]{}()+-?^$|".indexOf(ch) >= 0)
                sb.append('\\').append(ch);
            else
                sb.append(ch);
        }
        return sb.toString();
    }
}
