package com.anland.shell.ds;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * All droidspaces CLI interaction, run as root through {@link RootExec}.
 *
 * Conventions (Droidspaces-OSS):
 *   show --format → CONT_<name>=<pid> KEY=VALUE lines
 *   -n <name> pid → init PID or NONE
 *   --config <path> start → boot the container (boot-module convention)
 *   -n <name> run <arg> → execute inside the container (root, sh -c when the
 *     single argument contains spaces)
 *
 * Every container shell snippet is base64-wrapped into the FIXED shape
 *
 *     droidspaces -n '<name>' run "$(echo <B64> | base64 -d)"
 *
 * The $( ) substitution runs in the root-side Android shell (toybox base64 is
 * always present) and yields one argument, which droidspaces then hands to
 * sh -c inside the container. Snippet content therefore never needs escaping
 * at any layer — quotes, $, newlines are all opaque base64.
 *
 * The anland runtime conventions baked in here:
 *   · container sees the host wayland runtime dir at /run/anland
 *     (bind_mounts=/data/local/tmp/awl:/run/anland, pre-configured)
 *   · apps launch as direct anland clients: XDG_RUNTIME_DIR=/run/anland
 *     WAYLAND_DISPLAY=wayland-0 XDG_SESSION_TYPE=wayland (ozone picks
 *     wayland by itself) plus the kgsl Mesa overrides, as the container's
 *     desktop user (chromium/electron refuse root), detached via nohup so
 *     `run` returns immediately and the process survives this app entirely.
 *   · X apps: the anlandx user service (Xwayland -rootless + mini-wm,
 *     xwm/setupanlandx.sh) publishes its display as ":N" in the desktop
 *     user's ~/.anlandx while running — the session probe reads it as
 *     DISPLAY, preferring it over the legacy :0 socket.
 */
public final class DsCli {

    public static final String WORKSPACE = "/data/local/Droidspaces";
    public static final String CONTAINERS = WORKSPACE + "/Containers";

    /** anland xdg dir inside the container (bind-mounted by convention). */
    public static final String XDG_RUNTIME_DIR = "/run/anland";
    public static final String WAYLAND_DISPLAY = "wayland-0";

    /** Built-in launch environment (anland + kgsl GPU conventions); user
     *  customizations are merged over these (see EnvVars.merge). */
    public static List<String[]> defaultEnvPairs() {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"XDG_RUNTIME_DIR", XDG_RUNTIME_DIR});
        p.add(new String[]{"WAYLAND_DISPLAY", WAYLAND_DISPLAY});
        p.add(new String[]{"XDG_SESSION_TYPE", "wayland"});
        p.add(new String[]{"MESA_LOADER_DRIVER_OVERRIDE", "kgsl"});
        p.add(new String[]{"GALLIUM_DRIVER", "kgsl"});
        p.add(new String[]{"FD_FORCE_KGSL", "1"});
        return p;
    }

    private static volatile String dsBin;    /* resolved once: "droidspaces" or full path */
    private static volatile String dsError;  /* why resolution failed (null when ok) */

    private DsCli() {}

    // ------------------------------------------------------------------ core

    /** Resolve the droidspaces binary: PATH first, canonical install path as
     *  fallback. Returns null (and records a reason) when neither is usable. */
    public static synchronized String ds() {
        if (dsBin != null)
            return dsBin.isEmpty() ? null : dsBin;
        RootExec.Result r = RootExec.exec(
                "command -v droidspaces 2>/dev/null || printf '%s' " +
                ShellUtils.shQuote(WORKSPACE + "/bin/droidspaces"), 10_000);
        String v = r.stdout == null ? "" : r.stdout.trim();
        if (!r.ok || v.isEmpty()) {
            if (r.error != null)
                dsError = "su: " + r.error;
            else if (r.exit != 0)
                dsError = "su exit " + r.exit;
            else
                dsError = "not found in PATH or " + WORKSPACE + "/bin";
            dsBin = "";
            return null;
        }
        dsBin = v.split("\n", 2)[0].trim();
        return dsBin;
    }

    /** Human-readable reason the CLI is unusable, or null when it is fine. */
    public static String unavailableReason() {
        ds();
        return dsError;
    }

    /** Run a shell snippet inside the container (base64-wrapped, root). */
    public static RootExec.Result runSh(String name, String snippet, long timeoutMs) {
        String bin = ds();
        if (bin == null)
            return new RootExec.Result("", "", -1, "droidspaces binary not found");
        String b64 = Base64.encodeToString(
                snippet.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String cmd = bin + " -n " + ShellUtils.shQuote(name) +
                     " run \"$(echo " + b64 + " | base64 -d)\"";
        return RootExec.exec(cmd, timeoutMs);
    }

    public static RootExec.Result runSh(String name, String snippet) {
        return runSh(name, snippet, 30_000);
    }

    // ------------------------------------------------------------- containers

    /** All known containers: defined dirs + running-only ones, running first. */
    public static List<ContainerState> listContainers() {
        List<ContainerState> out = new ArrayList<>();
        String bin = ds();
        if (bin == null)
            return out;

        /* running map from show --format (CONT_<name>=<pid>) */
        RootExec.Result r = RootExec.exec(bin + " show --format", 15_000);
        List<ContainerState> running = new ArrayList<>();
        if (r.stdout != null) {
            for (String line : r.stdout.split("\n")) {
                line = line.trim();
                if (!line.startsWith("CONT_"))
                    continue;
                String rest = line.substring(5);
                int eq = rest.indexOf('=');
                if (eq <= 0)
                    continue;
                String name = rest.substring(0, eq);
                int pid;
                try {
                    pid = Integer.parseInt(rest.substring(eq + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (name.isEmpty() || pid <= 0)
                    continue;
                ContainerState c = new ContainerState(
                        name, CONTAINERS + "/" + name + "/container.config");
                c.pid = pid;
                running.add(c);
            }
        }

        /* defined (may be stopped) */
        List<ContainerState> defined = new ArrayList<>();
        RootExec.Result l = RootExec.exec("ls " + CONTAINERS + " 2>/dev/null", 10_000);
        if (l.stdout != null) {
            for (String line : l.stdout.split("\n")) {
                String name = line.trim();
                if (name.isEmpty() || name.contains(":") || name.contains(" "))
                    continue;
                defined.add(new ContainerState(
                        name, CONTAINERS + "/" + name + "/container.config"));
            }
        }

        /* merge: running first (with their pids from show --format), then
         * stopped defined ones; running containers missing from Containers/
         * are still shown */
        running.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        defined.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (ContainerState c : running)
            out.add(c);
        for (ContainerState c : defined) {
            boolean known = false;
            for (ContainerState o : out)
                if (o.name.equals(c.name)) { known = true; break; }
            if (!known)
                out.add(c);
        }
        return out;
    }

    /** Init PID or -1 (not running / unknown). */
    public static int pid(String name) {
        String bin = ds();
        if (bin == null)
            return -1;
        RootExec.Result r = RootExec.exec(
                bin + " -n " + ShellUtils.shQuote(name) + " pid", 10_000);
        return parsePid(r);
    }

    private static int parsePid(RootExec.Result r) {
        if (!r.ok || r.stdout == null)
            return -1;
        String v = r.stdout.trim();
        if (v.isEmpty() || "NONE".equals(v))
            return -1;
        try {
            int pid = Integer.parseInt(v.split("\n", 2)[0].trim());
            return pid > 0 ? pid : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Boot a container from its stored config (boot-module convention). */
    public static RootExec.Result start(String name) {
        String bin = ds();
        if (bin == null)
            return new RootExec.Result("", "", -1, "droidspaces binary not found");
        String cfg = CONTAINERS + "/" + name + "/container.config";
        return RootExec.exec(bin + " --config " + ShellUtils.shQuote(cfg) + " start",
                180_000);
    }

    /** Stop a container. */
    public static RootExec.Result stop(String name) {
        String bin = ds();
        if (bin == null)
            return new RootExec.Result("", "", -1, "droidspaces binary not found");
        return RootExec.exec(bin + " -n " + ShellUtils.shQuote(name) + " stop",
                120_000);
    }

    /** Poll pid() until the container is up or the deadline passes. */
    public static boolean awaitRunning(String name, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pid(name) > 0)
                return true;
            Thread.sleep(1_000);
        }
        return pid(name) > 0;
    }

    // ------------------------------------------------------------------- apps

    /** Concatenated .desktop dump of every applications dir (system + user). */
    public static RootExec.Result listDesktopDump(String name) {
        return runSh(name,
                "for f in /usr/share/applications/*.desktop " +
                "/usr/local/share/applications/*.desktop " +
                "/root/.local/share/applications/*.desktop " +
                "/home/*/.local/share/applications/*.desktop; do " +
                "[ -f \"$f\" ] || continue; echo \"=== $f\"; cat \"$f\"; echo; done",
                90_000);
    }

    /** Candidate icon files for a themed icon name (name → hicolor/pixmaps paths). */
    public static RootExec.Result findIcons(String name, String icon) {
        return runSh(name,
                "find /usr/share/icons /usr/share/pixmaps " +
                "/root/.local/share/icons /home/*/.local/share/icons -type f " +
                "-name " + ShellUtils.shQuote(icon + ".*") + " 2>/dev/null | head -20",
                20_000);
    }

    /** base64 of an icon file (single line, tr strips base64's line wraps). */
    public static RootExec.Result fetchIconB64(String name, String path) {
        return runSh(name,
                "base64 " + ShellUtils.shQuote(path) + " | tr -d '\\n'",
                20_000);
    }

    // ------------------------------------------------------------ app launch

    /** Desktop session user discovered inside the container (app launch
     *  target — chromium/electron refuse to run as root). */
    public static final class SessionInfo {
        public String uid;
        public String user;
        public String home;
        public String bus;    /* unix:path=/run/user/<uid>/bus, if present */
        public String disp;   /* X display: anlandx ":N" from ~/.anlandx, else :0 */
        public String xa;     /* first /run/user/<uid>/xauth_* file, if any */
    }

    /** Emit UID/USER/HOME/BUS/DISP/XA KEY=VALUE lines for $ent (a passwd
     *  entry); BUS/DISP/XA only when the corresponding sockets exist. */
    private static final String PROBE_EMIT =
            "uid=$(echo \"$ent\" | cut -d: -f3)\n" +
            "home=$(echo \"$ent\" | cut -d: -f6)\n" +
            "echo \"UID=$uid\"\n" +
            "echo \"USER=${ent%%:*}\"\n" +
            "echo \"HOME=$home\"\n" +
            "[ -S \"/run/user/$uid/bus\" ] && echo \"BUS=unix:path=/run/user/$uid/bus\"\n" +
            "[ -S /tmp/.X11-unix/X0 ] && echo \"DISP=:0\"\n" +
            "ax=$(cat \"$home/.anlandx\" 2>/dev/null)\n" +
            "[ -n \"$ax\" ] && echo \"DISP=$ax\"\n" +
            "xa=$(ls /run/user/$uid/xauth_* 2>/dev/null | head -1)\n" +
            "[ -n \"$xa\" ] && echo \"XA=$xa\"\n";

    /** Auto-detect probe: uid owning /run/user/<uid>, else the first regular
     *  (uid>=1000) account. Empty output = no desktop user. */
    private static final String SESSION_PROBE =
            "uid=\n" +
            "for d in /run/user/*; do\n" +
            "  [ -d \"$d\" ] || continue\n" +
            "  uid=${d#/run/user/}\n" +
            "  case \"$uid\" in (*[!0-9]*|'') uid=; continue;; esac\n" +
            "  break\n" +
            "done\n" +
            "if [ -z \"$uid\" ]; then\n" +
            "  ent=$(getent passwd | awk -F: '$3>=1000 && $3<60000 {print; exit}')\n" +
            "else\n" +
            "  ent=$(getent passwd \"$uid\")\n" +
            "fi\n" +
            "[ -n \"$ent\" ] || exit 0\n" + PROBE_EMIT;

    /** Probe one explicit user (empty output when the account doesn't exist). */
    private static String userProbe(String user) {
        return "ent=$(getent passwd " + ShellUtils.shQuote(user) + ") || exit 0\n" +
               PROBE_EMIT;
    }

    public static SessionInfo probeSession(String name) {
        return probeSession(name, "");
    }

    private static SessionInfo probeSession(String name, String user) {
        RootExec.Result r = runSh(name,
                user.isEmpty() ? SESSION_PROBE : userProbe(user), 15_000);
        if (!r.ok)
            return null;
        SessionInfo s = new SessionInfo();
        for (String line : r.stdout.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0)
                continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "UID":  s.uid = v;  break;
                case "USER": s.user = v; break;
                case "HOME": s.home = v; break;
                case "BUS":  s.bus = v;  break;
                case "DISP": s.disp = v; break;
                case "XA":   s.xa = v;   break;
                default: break;
            }
        }
        return s.user == null || s.user.isEmpty() ? null : s;
    }

    /**
     * Launch an app detached inside the container, as a direct anland client:
     * XDG_RUNTIME_DIR=/run/anland + WAYLAND_DISPLAY=wayland-0 (every window
     * auto-attaches as its own Android window), XDG_SESSION_TYPE=wayland so
     * ozone/chromium picks the wayland backend on its own, plus the kgsl
     * Mesa overrides. The app runs as the container's desktop user (probed)
     * rather than root — chromium/electron refuse root — via `su -`, and is
     * nohup-detached so `run` returns immediately and the process survives
     * this APK entirely.
     */
    public static RootExec.Result launchApp(String name, List<String> execArgs) {
        return launchApp(name, execArgs, "");
    }

    /**
     * @param userOverride "" = auto-detect the desktop user, "root" = run as
     *        root, anything else = that account (error when it doesn't exist)
     */
    public static RootExec.Result launchApp(String name, List<String> execArgs,
                                            String userOverride) {
        return launchApp(name, execArgs, userOverride, null);
    }

    /**
     * @param customEnv KEY=VALUE pairs merged over the built-in launch
     *        environment — same name wins, empty value removes the built-in
     *        (EnvVars.merge); null = built-ins only
     */
    public static RootExec.Result launchApp(String name, List<String> execArgs,
                                            String userOverride,
                                            List<String[]> customEnv) {
        SessionInfo s;
        if (userOverride == null || userOverride.isEmpty()) {
            s = probeSession(name, "");
        } else if ("root".equals(userOverride)) {
            s = null;
        } else {
            s = probeSession(name, userOverride);
            if (s == null)
                return new RootExec.Result("", "", -1,
                        "user " + userOverride + " not found in container");
        }

        StringBuilder sb = new StringBuilder("nohup ")
                .append(EnvVars.envPrefix(
                        EnvVars.merge(defaultEnvPairs(), customEnv)));
        if (s != null) {
            if (s.bus != null)
                sb.append(" DBUS_SESSION_BUS_ADDRESS=").append(ShellUtils.shQuote(s.bus));
            if (s.disp != null)
                sb.append(" DISPLAY=").append(s.disp);
            if (s.xa != null)
                sb.append(" XAUTHORITY=").append(ShellUtils.shQuote(s.xa));
        }
        for (String a : execArgs)
            sb.append(' ').append(ShellUtils.shQuote(a));
        sb.append(" >/dev/null 2>&1 &");
        String inner = "cd ~ 2>/dev/null; " + sb;

        if (s == null || "root".equals(s.user) || "0".equals(s.uid))
            return runSh(name, inner, 20_000);

        /* login shell supplies HOME/USER/PATH for the desktop user; the inner
         * command is base64-wrapped again so nothing needs re-escaping */
        String b64 = Base64.encodeToString(
                inner.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return runSh(name,
                "su - " + ShellUtils.shQuote(s.user) +
                " -c \"$(echo " + b64 + " | base64 -d)\"", 20_000);
    }

    /** root + regular accounts with a real shell, ordered by uid (root first). */
    public static List<String> listUsers(String name) {
        RootExec.Result r = runSh(name,
                "getent passwd | awk -F: " +
                "'($3==0 || ($3>=1000 && $3<60000)) && $7 !~ /(nologin|false)$/ " +
                "{print $3\" \"$1}'", 15_000);
        List<String[]> byUid = new ArrayList<>();
        if (r.ok && r.stdout != null) {
            for (String line : r.stdout.split("\n")) {
                line = line.trim();
                int sp = line.indexOf(' ');
                if (sp <= 0)
                    continue;
                try {
                    byUid.add(new String[]{
                            String.valueOf(Integer.parseInt(line.substring(0, sp))),
                            line.substring(sp + 1)});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        byUid.sort((a, b) -> Integer.parseInt(a[0]) - Integer.parseInt(b[0]));
        List<String> out = new ArrayList<>();
        for (String[] e : byUid)
            out.add(e[1]);
        return out;
    }

    /** Whether anlandx (rootless Xwayland + mini-wm user service) is
     *  installed in the container: setupanlandx.sh drops anlandx-start into
     *  a user's ~/.local/bin — any account counts. */
    public static boolean anlandxInstalled(String name) {
        RootExec.Result r = runSh(name,
                "[ -e /root/.local/bin/anlandx-start ] && exit 0\n" +
                "for h in /home/*; do " +
                "[ -e \"$h/.local/bin/anlandx-start\" ] && exit 0; done\n" +
                "exit 1", 15_000);
        return r.ok;
    }

    // ---------------------------------------------------------------- console

    /**
     * Persistent in-container root shell, as a direct ProcessBuilder argv.
     *
     * DS_NO_PROXY=1 is the key: the daemon-proxied `run` path does NOT
     * forward stdin (only the PTY protocol does, and `enter` hard-fails
     * without a tty). With proxying disabled, run_in_rootfs fork/execs with
     * inherited stdio, so plain pipes give a fully interactive session whose
     * cd/env state persists across lines.
     */
    public static String[] consoleArgv(String name) {
        String bin = ds() == null ? "droidspaces" : ds();
        return new String[]{
                "su", "-c",
                "DS_NO_PROXY=1 " + bin + " -n " + ShellUtils.shQuote(name) + " run sh"
        };
    }

    /** Preamble written to a fresh console session: home dir + the launch
     *  environment (built-ins overlaid with customEnv) as exports. */
    public static String consolePreamble(List<String[]> customEnv) {
        return "cd ~; " + EnvVars.exportLine(
                EnvVars.merge(defaultEnvPairs(), customEnv));
    }
}
