package com.anland.shell.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.anland.shell.R;
import com.anland.shell.ds.ContainerState;

import java.util.List;

/** The Containers tab: one row per container (name, running state, action
 *  buttons). Tapping a row selects it as the active container; the buttons
 *  delegate to the activity. */
public final class ContainersView extends LinearLayout {

    public interface Listener {
        void onSelectContainer(String name);
        void onEnterContainer(String name);
        void onStartContainer(String name);
        void onStopContainer(String name);
        void onShowApps(String name);
    }

    private final Context ctx;
    private final Listener listener;

    public ContainersView(Context context, Listener listener) {
        super(context);
        setOrientation(VERTICAL);
        this.ctx = context;
        this.listener = listener;
    }

    /** Rebuild the whole list (few containers; no recycling needed). */
    public void setContainers(List<ContainerState> containers, String activeName) {
        removeAllViews();
        if (containers.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(R.string.status_no_containers);
            empty.setTextSize(13);
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (ContainerState c : containers)
            addRow(c, c.name.equals(activeName));
    }

    private void addRow(final ContainerState c, boolean active) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setBackgroundColor(active
                ? getResources().getColor(R.color.accent_dim) : 0);

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(c.name);
        name.setTextSize(16);
        name.setTextColor(getResources().getColor(R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        info.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(ctx);
        status.setTextSize(12);
        status.setTextColor(getResources().getColor(R.color.text_secondary));
        if (c.running()) {
            status.setText(getContext().getString(R.string.running_fmt, c.pid));
            status.setTextColor(getResources().getColor(R.color.accent));
        } else {
            status.setText(R.string.stopped);
        }
        info.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(actionBtn(R.string.apps, v -> listener.onShowApps(c.name)));
        row.addView(actionBtn(R.string.enter_console, v -> listener.onEnterContainer(c.name)));
        row.addView(actionBtn(c.running() ? R.string.stop : R.string.start,
                c.running()
                        ? v -> listener.onStopContainer(c.name)
                        : v -> listener.onStartContainer(c.name)));

        row.setOnClickListener(v -> listener.onSelectContainer(c.name));
        addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private Button actionBtn(int label, View.OnClickListener onClick) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
