package com.anland.shell.ds;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs commands as root: ProcessBuilder("su", "-c", cmd) — exactly one shell
 * layer (the one su spawns), so cmd is written as a plain shell command line
 * with no extra quoting level. stdout and stderr are captured on separate
 * reader threads (a full stderr pipe would otherwise deadlock the child);
 * a hard timeout forcibly kills the process.
 */
public final class RootExec {

    /** Captured outcome. ok = clean exit 0; error is set for local failures
     *  (su missing, spawn failure, timeout). */
    public static final class Result {
        public final String stdout;
        public final String stderr;
        public final int exit;
        public final boolean ok;
        public final String error;

        Result(String stdout, String stderr, int exit, String error) {
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exit = exit;
            this.ok = error == null && exit == 0;
            this.error = error;
        }
    }

    /** Daemon pool for one-shot commands (console sessions spawn their own
     *  process and reader threads). */
    public static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "rootexec-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private RootExec() {}

    public static Result exec(String cmd) {
        return exec(cmd, 30_000);
    }

    public static Result exec(String cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).start();

            final Process proc = p;
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread tOut = reader(proc.getInputStream(), out);
            Thread tErr = reader(proc.getErrorStream(), err);

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            tOut.join(2_000);
            tErr.join(2_000);
            if (!finished) {
                proc.destroyForcibly();
                return new Result(out.toString(), err.toString(), -1,
                        "timeout after " + timeoutMs + "ms");
            }
            return new Result(out.toString(), err.toString(), proc.exitValue(), null);
        } catch (IOException e) {
            if (p != null) p.destroyForcibly();
            return new Result("", "", -1, "su failed: " + e.getMessage());
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result("", "", -1, "interrupted");
        }
    }

    private static Thread reader(java.io.InputStream is, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8), 32 * 1024)) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0)
                    sb.append(buf, 0, n);
            } catch (IOException ignored) {
                /* process died / stream closed — captured output so far is all there is */
            }
        }, "ds-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
