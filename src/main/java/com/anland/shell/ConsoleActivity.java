package com.anland.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.anland.shell.ds.DsCli;
import com.anland.shell.ds.EnvVars;
import com.anland.shell.ds.RootExec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-container console over the persistent session started by
 * {@link DsCli#consoleArgv} (DS_NO_PROXY=1 … run sh): lines are written to
 * the process stdin, output streams back through a reader thread, and cd/env
 * state persists for the life of the session. The session runs as the
 * selected launch user (auto-detected desktop user by default) — the same
 * user app launches use — so its ~/.anlandx display is exported and Xwayland
 * admits the connection. This is a plain pipe session, not a PTY — no prompt
 * echo, no Tab completion, no control characters. Volume keys adjust the
 * font size; the manifest's configChanges keeps the process alive across
 * rotation.
 */
public final class ConsoleActivity extends Activity {

    private static final int BUF_MAX = 128 * 1024;   /* chars kept on screen */

    private String container;
    private Process proc;
    private OutputStream stdin;
    private final StringBuilder buf = new StringBuilder();

    private ScrollView scroll;
    private TextView output;
    private TextView title;
    private EditText input;
    private Button sendBtn;
    private boolean sessionDead;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        container = getIntent().getStringExtra("container");
        if (container == null || container.isEmpty()) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.bg));

        /* header: title + history */
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(12), dp(4));
        TextView titleView = new TextView(this);
        titleView.setText(getString(R.string.console_title_fmt, container));
        titleView.setTextSize(16);
        titleView.setTextColor(getResources().getColor(R.color.text_primary));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title = titleView;
        header.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button history = new Button(this);
        history.setText(R.string.history);
        history.setOnClickListener(v -> showHistory());
        header.addView(history);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* output */
        scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.consoleFontSp(this));
        output.setTextColor(getResources().getColor(R.color.text_primary));
        output.setPadding(dp(10), dp(8), dp(10), dp(8));
        output.setTextIsSelectable(true);
        scroll.addView(output, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* input row */
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(8));
        input = new EditText(this);
        input.setHint(R.string.console_hint);
        input.setSingleLine(true);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.consoleFontSp(this));
        input.setTextColor(getResources().getColor(R.color.text_primary));
        input.setHintTextColor(getResources().getColor(R.color.text_secondary));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = new Button(this);
        sendBtn.setText(R.string.console_send);
        sendBtn.setOnClickListener(v -> send());
        row.addView(sendBtn);
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        startSession();
    }

    // ----------------------------------------------------------------- session

    private void startSession() {
        /* resolve the launch user first (auto = first non-root account of
         * the container user list, same rule as app launches): the session
         * runs as that user, so its home, env and ~/.anlandx display all
         * match what app launches see */
        final String chosen = Prefs.launchUser(this, container);
        RootExec.POOL.execute(() -> {
            String user = chosen;
            if (user.isEmpty())
                user = DsCli.autoUser(container);
            final String resolved = user;
            runOnUiThread(() -> openSession(resolved));
        });
    }

    private void openSession(String user) {
        if (isDestroyed() || isFinishing())
            return;
        if (!user.isEmpty() && !"root".equals(user))
            title.setText(getString(R.string.console_title_user_fmt, container, user));
        try {
            proc = new ProcessBuilder(DsCli.consoleArgv(container, user))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            sessionDead = true;
            input.setEnabled(false);
            sendBtn.setEnabled(false);
            append("[su failed: " + e.getMessage() + "]\n");
            return;
        }
        stdin = proc.getOutputStream();

        Thread reader = new Thread(() -> {
            char[] chunk = new char[4096];
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    proc.getInputStream(), StandardCharsets.UTF_8))) {
                int n;
                while ((n = r.read(chunk)) > 0) {
                    final String s = new String(chunk, 0, n);
                    runOnUiThread(() -> append(s));
                }
            } catch (IOException ignored) {
                /* process killed — endSession reports the exit code */
            }
            try {
                final int code = proc.waitFor();
                runOnUiThread(() -> endSession(code));
            } catch (InterruptedException ignored) {
            }
        }, "console-reader");
        reader.setDaemon(true);
        reader.start();

        /* preamble: cd ~ + launch env (built-ins + this container's
         * customizations) + DISPLAY from ~/.anlandx when anlandx runs.
         * Echoed locally — the non-tty sh prints nothing back for it. */
        List<String[]> customEnv = EnvVars.parse(Prefs.launchEnv(this, container));
        List<String[]> merged = EnvVars.merge(DsCli.defaultEnvPairs(), customEnv);
        append(getString(R.string.console_env_note,
                EnvVars.format(merged).replace("\n", " ")) + "\n");
        writeLine(DsCli.consolePreamble(customEnv));
    }

    private void endSession(int code) {
        if (sessionDead)
            return;
        sessionDead = true;
        append("\n" + getString(R.string.console_ended) + " (exit " + code + ")\n");
        input.setEnabled(false);
        sendBtn.setEnabled(false);
    }

    private void send() {
        if (sessionDead)
            return;
        String cmd = input.getText().toString();
        append("$ " + cmd + "\n");
        if (!cmd.trim().isEmpty())
            Prefs.addHistory(this, cmd);
        writeLine(cmd);
        input.setText("");
    }

    private void writeLine(String line) {
        if (stdin == null || sessionDead)
            return;
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            endSession(-1);
        }
    }

    // -------------------------------------------------------------------- UI

    private synchronized void append(String s) {
        buf.append(s);
        if (buf.length() > BUF_MAX)
            buf.delete(0, buf.length() - BUF_MAX);
        output.setText(buf);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showHistory() {
        List<String> h = Prefs.history(this);
        if (h.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> items = new ArrayList<>(h);
        Collections.reverse(items);   /* newest first */
        new AlertDialog.Builder(this)
                .setTitle(R.string.history)
                .setItems(items.toArray(new CharSequence[0]), (d, which) ->
                        input.setText(items.get(which)))
                .show();
    }

    /** Volume keys adjust the console font size (persisted). */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            int sp = Prefs.consoleFontSp(this)
                    + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
            Prefs.setConsoleFontSp(this, sp);
            sp = Prefs.consoleFontSp(this);
            output.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (sessionDead) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.console_exit_confirm)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> finish())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override protected void onDestroy() {
        if (proc != null)
            proc.destroyForcibly();
        if (stdin != null) {
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
