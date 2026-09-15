package com.anland.shell.ds;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * POSIX shell single-quote escaping and .desktop Exec= parsing.
 *
 * The anland shell never builds nested shell strings by hand for container
 * snippets (they are base64-wrapped — see {@link DsCli}); shQuote is only
 * used for short fixed-position values (container names, icon paths).
 */
public final class ShellUtils {

    private ShellUtils() {}

    /** Wrap s in single quotes the POSIX way ('"'"' dance for embedded quotes). */
    public static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Desktop-entry field codes (%f %F %u %U %i %c %k and the deprecated
     *  %d %D %n %N %v %m) — stripped from parsed Exec arguments. */
    private static final Pattern FIELD_CODE = Pattern.compile("%[fFuUiIcCkKdDnNvVmM]");

    /**
     * Parse a .desktop Exec= value into an argument list, per the spec:
     * whitespace-separated, honoring double/single quotes and backslash
     * escapes, with field codes removed and empty arguments dropped.
     */
    public static List<String> parseExec(String exec) {
        List<String> out = new ArrayList<>();
        if (exec == null)
            return out;
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false, hasToken = false;
        for (int i = 0; i < exec.length(); i++) {
            char c = exec.charAt(i);
            if (inSingle) {
                if (c == '\'')
                    inSingle = false;
                else
                    cur.append(c);
            } else if (inDouble) {
                if (c == '\\') {
                    if (i + 1 < exec.length()) {
                        cur.append(exec.charAt(++i));
                        hasToken = true;
                    }
                } else if (c == '"') {
                    inDouble = false;
                } else {
                    cur.append(c);
                    hasToken = true;
                }
            } else if (c == '\\' && i + 1 < exec.length()) {
                cur.append(exec.charAt(++i));
                hasToken = true;
            } else if (c == '\'') {
                inSingle = true;
                hasToken = true;   /* '' is a legitimate empty argument */
            } else if (c == '"') {
                inDouble = true;
                hasToken = true;
            } else if (c == ' ' || c == '\t') {
                if (hasToken)
                    out.add(cur.toString());
                cur.setLength(0);
                hasToken = false;
            } else {
                cur.append(c);
                hasToken = true;
            }
        }
        if (hasToken)
            out.add(cur.toString());

        /* strip field codes; drop arguments that become empty */
        List<String> cleaned = new ArrayList<>(out.size());
        for (String a : out) {
            String s = FIELD_CODE.matcher(a).replaceAll("");
            if (!s.isEmpty())
                cleaned.add(s);
        }
        return cleaned;
    }
}
