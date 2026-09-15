package com.anland.shell.ds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Launch-environment KEY=VALUE pairs: the editor/storage format, merging over
 * the built-ins, and the two shell renderings (env prefix for app launches,
 * export line for the console preamble).
 *
 * A pair's value may be empty; in a {@link #merge} that REMOVES the built-in
 * variable of the same name (e.g. FD_FORCE_KGSL= drops the kgsl override).
 */
public final class EnvVars {

    /** POSIX variable name — anything else would break the env/export word. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EnvVars() {}

    /** Parse KEY=VALUE lines: blank and # comment lines are skipped, invalid
     *  lines are skipped too (the editor validates before anything is saved). */
    public static List<String[]> parse(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null)
            return out;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            int eq = line.indexOf('=');
            if (eq <= 0)
                continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1);
            if (!NAME.matcher(k).matches())
                continue;
            out.add(new String[]{k, v});
        }
        return out;
    }

    /** First line that {@link #parse} would reject, or null when all are
     *  valid — used by the editor to refuse saving a bad line. */
    public static String invalidLine(String text) {
        if (text == null)
            return null;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            int eq = line.indexOf('=');
            if (eq <= 0 || !NAME.matcher(line.substring(0, eq)).matches())
                return line;
        }
        return null;
    }

    /** Render pairs back to KEY=VALUE lines (the editor / storage format). */
    public static String format(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(p[0]).append('=').append(p[1]);
        }
        return sb.toString();
    }

    /** defaults overlaid with custom: a custom pair with the same name wins,
     *  an empty custom value removes the built-in entirely. */
    public static List<String[]> merge(List<String[]> defaults, List<String[]> custom) {
        Map<String, String> m = new LinkedHashMap<>();
        if (defaults != null)
            for (String[] p : defaults)
                m.put(p[0], p[1]);
        if (custom != null)
            for (String[] p : custom)
                m.put(p[0], p[1]);
        List<String[]> out = new ArrayList<>(m.size());
        for (Map.Entry<String, String> e : m.entrySet())
            out.add(new String[]{e.getKey(), e.getValue()});
        return out;
    }

    /** "env 'K=V' 'K=V' …" — each K=V quoted whole, so values with spaces or
     *  shell metacharacters stay one word at every layer of the launch path. */
    public static String envPrefix(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder("env");
        for (String[] p : pairs)
            sb.append(' ').append(ShellUtils.shQuote(p[0] + "=" + p[1]));
        return sb.toString();
    }

    /** "export 'K=V' 'K=V' …" for the console preamble (quote removal in sh
     *  yields the same NAME=value words export expects). */
    public static String exportLine(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder("export");
        for (String[] p : pairs)
            sb.append(' ').append(ShellUtils.shQuote(p[0] + "=" + p[1]));
        return sb.toString();
    }
}
