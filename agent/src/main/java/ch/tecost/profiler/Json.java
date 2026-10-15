package ch.tecost.profiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON minimal (écriture + lecture) pour ne dépendre de rien côté application. */
final class Json {

    private Json() {
    }

    static void str(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
            case '"': sb.append("\\\""); break;
            case '\\': sb.append("\\\\"); break;
            case '\n': sb.append("\\n"); break;
            case '\r': sb.append("\\r"); break;
            case '\t': sb.append("\\t"); break;
            default:
                if (c < 0x20)
                    sb.append(String.format("\\u%04x", (int) c));
                else
                    sb.append(c);
            }
        }
        sb.append('"');
    }

    static void field(StringBuilder sb, String name, String value) {
        if (value == null)
            return;
        sb.append(',');
        str(sb, name);
        sb.append(':');
        str(sb, value);
    }

    static void field(StringBuilder sb, String name, Collection<String> values) {
        if (values == null)
            return;
        sb.append(',');
        str(sb, name);
        sb.append(":[");
        boolean first = true;
        for (String v : values) {
            if (!first)
                sb.append(',');
            str(sb, v);
            first = false;
        }
        sb.append(']');
    }

    static Object parse(String s) {
        return new Parser(s).value();
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{')
                return object();
            if (c == '[')
                return array();
            if (c == '"')
                return string();
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0)
                i++;
            return Double.parseDouble(s.substring(start, i));
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // ':'
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}')
                    return m;
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']')
                    return l;
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"')
                    return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i)))
                i++;
        }
    }
}
