package com.anland.shell;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** App preferences + console command history (SharedPreferences backed). */
public final class Prefs {

    private static final String FILE = "shell";
    private static final int HISTORY_MAX = 100;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Currently selected container ("" = none chosen yet). */
    public static String activeContainer(Context c) {
        return sp(c).getString("active_container", "");
    }

    public static void setActiveContainer(Context c, String name) {
        sp(c).edit().putString("active_container", name == null ? "" : name).apply();
    }

    /** App-launch user override for a container ("" = auto-detect). */
    public static String launchUser(Context c, String container) {
        return sp(c).getString("launch_user." + container, "");
    }

    public static void setLaunchUser(Context c, String container, String user) {
        sp(c).edit().putString("launch_user." + container,
                user == null ? "" : user).apply();
    }

    /** Custom launch environment for a container: KEY=VALUE lines merged
     *  over the built-ins (empty value removes the built-in, # = comment).
     *  "" = built-ins only. */
    public static String launchEnv(Context c, String container) {
        return sp(c).getString("launch_env." + container, "");
    }

    public static void setLaunchEnv(Context c, String container, String env) {
        sp(c).edit().putString("launch_env." + container,
                env == null ? "" : env).apply();
    }

    /** Console font size in sp (volume keys adjust it in ConsoleActivity). */
    public static int consoleFontSp(Context c) {
        return sp(c).getInt("console_font_sp", 12);
    }

    public static void setConsoleFontSp(Context c, int sp) {
        sp(c).edit().putInt("console_font_sp", Math.max(6, Math.min(28, sp))).apply();
    }

    /** Command history, oldest first, newest last. */
    public static List<String> history(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("history", "[]"));
            for (int i = 0; i < a.length(); i++)
                out.add(a.getString(i));
        } catch (Exception ignored) {
            /* corrupt history is simply empty */
        }
        return out;
    }

    /** Add a command (deduped), keeping the newest HISTORY_MAX entries. */
    public static void addHistory(Context c, String cmd) {
        cmd = cmd == null ? "" : cmd.trim();
        if (cmd.isEmpty())
            return;
        List<String> h = history(c);
        h.remove(cmd);
        h.add(cmd);
        while (h.size() > HISTORY_MAX)
            h.remove(0);
        JSONArray a = new JSONArray();
        for (String s : h)
            a.put(s);
        sp(c).edit().putString("history", a.toString()).apply();
    }
}
