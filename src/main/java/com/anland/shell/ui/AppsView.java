package com.anland.shell.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.anland.shell.R;
import com.anland.shell.ds.AppEntry;
import com.anland.shell.ds.DesktopEntry;
import com.anland.shell.ds.DsCli;
import com.anland.shell.ds.RootExec;

import java.util.List;

/** The Apps tab: status line + optional start button + the app grid of the
 *  active container. Loading happens here (one dump per refresh, parsed by
 *  DesktopEntry); container selection and lifecycle stay in the activity. */
public final class AppsView extends LinearLayout {

    /** Lets the status row's Start button reach the activity's start logic. */
    public interface Listener {
        void onRequestStart(String container);
    }

    private final TextView status;
    private final Button startBtn;
    private final GridView grid;
    private final AppsGridAdapter adapter;

    private String container = "";

    public AppsView(Context context, AppsGridAdapter adapter, final Listener listener) {
        super(context);
        setOrientation(VERTICAL);
        this.adapter = adapter;

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(16), dp(4));

        status = new TextView(context);
        status.setTextSize(13);
        status.setTextColor(getResources().getColor(R.color.text_secondary));
        head.addView(status, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        startBtn = new Button(context);
        startBtn.setText(R.string.start);
        startBtn.setVisibility(GONE);
        startBtn.setOnClickListener(v -> listener.onRequestStart(container));
        head.addView(startBtn);

        grid = new GridView(context);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(88));
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(adapter);

        addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** Point at a container; shows the start button when it is stopped. */
    public void setContainer(String name, boolean running) {
        container = name == null ? "" : name;
        startBtn.setVisibility(GONE);
        if (container.isEmpty()) {
            adapter.setApps(null);
            status.setText(R.string.no_container_selected);
            return;
        }
        if (!running) {
            adapter.setApps(null);
            status.setText(R.string.apps_need_running);
            startBtn.setVisibility(VISIBLE);
            return;
        }
        refresh();
    }

    public String container() {
        return container;
    }

    /** Re-read .desktop entries from the container (no-op when unselected). */
    public void refresh() {
        if (container.isEmpty())
            return;
        final String name = container;
        status.setText(getContext().getString(R.string.loading_apps_fmt, name));
        RootExec.POOL.execute(() -> {
            RootExec.Result r = DsCli.listDesktopDump(name);
            final List<AppEntry> apps = r.ok ? DesktopEntry.parse(name, r.stdout) : null;
            final RootExec.Result res = r;
            post(() -> {
                if (!name.equals(container))
                    return;   /* switched containers meanwhile */
                if (apps == null) {
                    adapter.setApps(null);
                    status.setText(getContext().getString(R.string.load_failed_fmt,
                            errText(res)));
                } else if (apps.isEmpty()) {
                    adapter.setApps(apps);
                    status.setText(R.string.no_apps);
                } else {
                    adapter.setApps(apps);
                    status.setText(getContext().getString(R.string.app_count_fmt, apps.size()));
                }
            });
        });
    }

    private static String errText(RootExec.Result r) {
        if (r.error != null)
            return r.error;
        String e = r.stderr.trim();
        return e.isEmpty() ? "exit " + r.exit : e.split("\n", 2)[0];
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
