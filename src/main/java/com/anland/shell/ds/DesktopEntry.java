package com.anland.shell.ds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for the concatenated .desktop dump produced by
 * {@link DsCli#listDesktopDump}: "=== <path>" markers, each followed by the
 * raw file contents. Implements the Desktop Entry Specification subset the
 * shell needs: [Desktop Entry] group only, Key[locale]=Value localization
 * (exact tag, then language-only, then unlocalized), Type/NoDisplay/Hidden
 * filtering, and Name/Icon/Exec extraction.
 *
 * Duplicate ids across directories: later entries override earlier ones —
 * the dump walks system dirs before user dirs, so ~/.local wins, matching
 * the spec's precedence.
 */
public final class DesktopEntry {

    private DesktopEntry() {}

    /** Parse a whole dump into a sorted (by display name) app list. */
    public static List<AppEntry> parse(String container, String dump) {
        LinkedHashMap<String, AppEntry> byId = new LinkedHashMap<>();

        String path = null;
        Map<String, String> kv = null;
        boolean inMainGroup = false;

        String[] lines = dump.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith("=== ") && line.length() > 4) {
                if (path != null)
                    addIfApp(container, path, kv, byId);
                path = line.substring(4).trim();
                kv = new HashMap<>();
                inMainGroup = false;
                continue;
            }
            if (path == null)
                continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
                continue;
            if (trimmed.startsWith("[")) {
                inMainGroup = "[Desktop Entry]".equals(trimmed);
                continue;
            }
            if (!inMainGroup)
                continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0)
                continue;
            kv.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        if (path != null)
            addIfApp(container, path, kv, byId);

        List<AppEntry> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static void addIfApp(String container, String path,
                                 Map<String, String> kv, Map<String, AppEntry> byId) {
        if (kv == null || kv.isEmpty())
            return;
        if (!"Application".equals(kv.get("Type")))
            return;
        if (isTrue(kv.get("NoDisplay")) || isTrue(kv.get("Hidden")))
            return;
        String name = localized(kv, "Name");
        String exec = kv.get("Exec");
        if (name == null || name.isEmpty() || exec == null || exec.isEmpty())
            return;

        String id = path;
        int slash = id.lastIndexOf('/');
        if (slash >= 0)
            id = id.substring(slash + 1);
        if (id.endsWith(".desktop"))
            id = id.substring(0, id.length() - ".desktop".length());

        byId.put(id, new AppEntry(container, id, name, exec,
                kv.get("Icon"), path));
    }

    private static boolean isTrue(String v) {
        return v != null && "true".equalsIgnoreCase(v.trim());
    }

    /** Name[<locale>] → Name[<language>] → Name, matching the device locale. */
    private static String localized(Map<String, String> kv, String key) {
        Locale loc = Locale.getDefault();
        String country = loc.getCountry();
        if (country != null && !country.isEmpty()) {
            String v = kv.get(key + "[" + loc.getLanguage() + "_" + country + "]");
            if (v != null)
                return v;
        }
        String v = kv.get(key + "[" + loc.getLanguage() + "]");
        if (v != null)
            return v;
        return kv.get(key);
    }
}
