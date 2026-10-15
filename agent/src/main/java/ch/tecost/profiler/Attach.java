package ch.tecost.profiler;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * Ligne de commande du jar, utilisée aussi par le serveur pour l'attachement depuis l'UI (nécessite un JDK, module
 * jdk.attach) :
 * <pre>
 * java -jar entity-profiler-agent.jar list                       JVM locales, en JSON : [{"pid":"1234","name":"..."}]
 * java -jar entity-profiler-agent.jar &lt;pid&gt; ["server=...;name=..."]   attache l'agent à la JVM
 * </pre>
 * L'API d'attachement est appelée par réflexion : le jar reste chargeable par un JRE (mode -javaagent).
 */
final class Attach {

    private Attach() {
    }

    static void main(String[] argv) throws Exception {
        if (argv.length < 1) {
            System.err.println("usage: java -jar entity-profiler-agent.jar list");
            System.err.println("       java -jar entity-profiler-agent.jar <pid> [\"server=http://localhost:7070;name=...\"]");
            System.exit(1);
        }
        try {
            if (argv[0].equals("list"))
                list();
            else
                attach(argv[0], argv.length > 1 ? argv[1] : "");
        } catch (ClassNotFoundException e) {
            fail("API d'attachement introuvable : lancez avec un JDK (pas un JRE)");
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            fail(t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    private static void list() throws Exception {
        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Class<?> descClass = Class.forName("com.sun.tools.attach.VirtualMachineDescriptor");
        String self = String.valueOf(ProcessHandle.current().pid());
        StringBuilder sb = new StringBuilder("[");
        for (Object d : (List<?>) vmClass.getMethod("list").invoke(null)) {
            String pid = (String) descClass.getMethod("id").invoke(d);
            if (pid.equals(self))
                continue;
            if (sb.length() > 1)
                sb.append(',');
            sb.append("{\"pid\":");
            Json.str(sb, pid);
            Json.field(sb, "name", (String) descClass.getMethod("displayName").invoke(d));
            sb.append('}');
        }
        System.out.println(sb.append(']'));
    }

    private static void attach(String pid, String args) throws Exception {
        String jar = new File(Attach.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
        try {
            vmClass.getMethod("loadAgent", String.class, String.class).invoke(vm, jar, args);
            System.out.println("agent attached to " + pid);
        } finally {
            vmClass.getMethod("detach").invoke(vm);
        }
    }

    private static void fail(String msg) {
        System.err.println(msg);
        System.exit(2);
    }
}
