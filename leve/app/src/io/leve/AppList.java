package io.leve;

import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SectionIndexer;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The drawer's rows: icon and name, filtered by the search, with A-Z sections for the fast scroller. */
final class AppList extends BaseAdapter implements SectionIndexer {
    private final Home home;
    private List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private String query = "";
    private String[] sections = new String[0];
    private int[] sectionStart = new int[0];

    AppList(Home home) { this.home = home; }

    void setApps(List<App> apps) { all = apps; filter(query); }

    void filter(String q) {
        query = App.fold(q.trim());
        shown.clear();
        if (query.isEmpty()) shown.addAll(all);
        else {
            // Names that start with the search first, then names that contain it.
            List<App> later = new ArrayList<>();
            for (App a : all) {
                if (a.search.startsWith(query) || a.search.contains(" " + query)) shown.add(a);
                else if (a.search.contains(query)) later.add(a);
            }
            shown.addAll(later);
        }
        buildSections();
        notifyDataSetChanged();
    }

    private void buildSections() {
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            String s = shown.get(i).section();
            if (names.isEmpty() || !names.get(names.size() - 1).equals(s)) { names.add(s); starts.add(i); }
        }
        sections = names.toArray(new String[0]);
        sectionStart = new int[starts.size()];
        for (int i = 0; i < sectionStart.length; i++) sectionStart[i] = starts.get(i);
    }

    @Override public int getCount() { return shown.size(); }
    @Override public App getItem(int i) { return shown.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override public View getView(int i, View view, ViewGroup parent) {
        Row row = view == null ? new Row(home) : (Row) view.getTag();
        App a = shown.get(i);
        row.icon.setImageDrawable(a.icon);
        row.name.setText(a.label);
        return row.view;
    }

    @Override public Object[] getSections() { return sections; }
    @Override public int getPositionForSection(int s) {
        return sectionStart.length == 0 ? 0 : sectionStart[Math.max(0, Math.min(s, sectionStart.length - 1))];
    }
    @Override public int getSectionForPosition(int p) {
        int s = 0;
        while (s + 1 < sectionStart.length && sectionStart[s + 1] <= p) s++;
        return s;
    }

    private static final class Row {
        final LinearLayout view;
        final ImageView icon;
        final TextView name;

        Row(Home home) {
            view = new LinearLayout(home);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int pad = home.dp(10);
            view.setPadding(home.dp(20), pad, home.dp(20), pad);
            view.setBackground(home.ripple());
            icon = new ImageView(home);
            view.addView(icon, new LinearLayout.LayoutParams(home.dp(42), home.dp(42)));
            name = new TextView(home);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = home.dp(18);
            view.addView(name, lp);
            view.setTag(this);
        }
    }
}
