package com.anland.shell.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.anland.shell.R;
import com.anland.shell.ds.AppEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** GridView items for the app grid: icon above a two-line name. Click and
 *  long-press are delegated; icons load asynchronously through IconLoader
 *  (stale results from recycled views are dropped via the icon-view tag). */
public final class AppsGridAdapter extends BaseAdapter {

    public interface Listener {
        void onAppClick(AppEntry app);
        void onAppLongClick(AppEntry app, View anchor);
    }

    private static final class Holder {
        final ImageView icon;
        final TextView label;
        Holder(ImageView icon, TextView label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private final Context ctx;
    private final IconLoader icons;
    private final Listener listener;
    private final List<AppEntry> apps = new ArrayList<>();
    private final int pad;
    private final int iconPx;

    public AppsGridAdapter(Context ctx, IconLoader icons, Listener listener) {
        this.ctx = ctx;
        this.icons = icons;
        this.listener = listener;
        pad = dp(8);
        iconPx = dp(56);
    }

    public void setApps(List<AppEntry> list) {
        apps.clear();
        if (list != null)
            apps.addAll(list);
        notifyDataSetChanged();
    }

    public List<AppEntry> apps() {
        return Collections.unmodifiableList(apps);
    }

    @Override public int getCount() {
        return apps.size();
    }

    @Override public AppEntry getItem(int position) {
        return apps.get(position);
    }

    @Override public long getItemId(int position) {
        return position;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout item;
        Holder h;
        if (convertView instanceof LinearLayout && convertView.getTag() instanceof Holder) {
            item = (LinearLayout) convertView;
            h = (Holder) item.getTag();
        } else {
            item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(pad, dp(12), pad, dp(12));

            ImageView icon = new ImageView(ctx);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));

            TextView label = new TextView(ctx);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setTextSize(12);
            label.setTextColor(ctx.getResources().getColor(R.color.text_primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            item.addView(label, lp);

            h = new Holder(icon, label);
            item.setTag(h);
        }

        final AppEntry app = apps.get(position);
        h.label.setText(app.name);
        h.icon.setTag(app.iconKey());
        h.icon.setImageDrawable(null);
        icons.load(app, (key, bmp) -> {
            if (key.equals(h.icon.getTag()))
                h.icon.setImageBitmap(bmp);
        });

        item.setOnClickListener(v -> listener.onAppClick(app));
        item.setOnLongClickListener(v -> {
            listener.onAppLongClick(app, v);
            return true;
        });
        return item;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
