package io.leve;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;

/** The left page: weather, quotes and headlines in glass cards over the wallpaper. */
final class Widgets {
    private static final int GREEN = 0xFF7EE0A8, RED = 0xFFFF8A80;

    private final Home home;
    private final Feeds feeds;
    final ScrollView view;
    private final LinearLayout weatherCard, quotesCard, newsCard;
    private final TextView updated;
    private int tab;

    Widgets(Home home, Feeds feeds) {
        this.home = home;
        this.feeds = feeds;
        view = new ScrollView(home);
        view.setVerticalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setVerticalFadingEdgeEnabled(true);
        view.setFadingEdgeLength(home.dp(28));
        LinearLayout col = new LinearLayout(home);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(home.dp(16), home.dp(20), home.dp(16), home.dp(16));
        view.addView(col);

        TextView title = text("Resumo", 32, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(home.dp(6), 0, 0, 0);
        col.addView(title);
        TextClock date = new TextClock(home);
        String day = "EEEE, d 'de' MMMM";
        date.setFormat24Hour(day);
        date.setFormat12Hour(day);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setTextColor(0xCCFFFFFF);
        date.setShadowLayer(8, 0, 1, 0x80000000);
        date.setPadding(home.dp(6), 0, 0, home.dp(14));
        col.addView(date);

        weatherCard = card(col);
        quotesCard = card(col);
        newsCard = card(col);

        updated = text("", 12, 0x99FFFFFF);
        updated.setGravity(Gravity.CENTER);
        updated.setPadding(0, home.dp(6), 0, home.dp(8));
        updated.setOnClickListener(v -> { updated.setText("Atualizando…"); feeds.refresh(true); });
        col.addView(updated);
        bind();
    }

    /** Rebuilds the cards from the current data; cheap, a few dozen views. */
    void bind() {
        bindWeather();
        bindQuotes();
        bindNews();
        long at = Math.max(feeds.quotesAt, feeds.newsAt[tab]);
        updated.setText(at == 0 ? "Toque para atualizar" : "Atualizado " + Feeds.ago(at) + " · toque para atualizar");
    }

    private void bindWeather() {
        weatherCard.removeAllViews();
        Feeds.Weather w = feeds.weather;
        weatherCard.addView(label(Feeds.CITY));
        if (w == null) {
            weatherCard.addView(text("Carregando o clima…", 15, 0xCCFFFFFF));
            return;
        }
        LinearLayout row = new LinearLayout(home);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(Feeds.symbol(w.code, w.day), 40, Color.WHITE));
        TextView temp = text(Math.round(w.temp) + "°", 48, Color.WHITE);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temp.setPadding(home.dp(10), 0, home.dp(16), 0);
        row.addView(temp);
        LinearLayout side = new LinearLayout(home);
        side.setOrientation(LinearLayout.VERTICAL);
        side.addView(text(Feeds.sky(w.code), 16, Color.WHITE));
        side.addView(text("Máx " + Math.round(w.max) + "° · Mín " + Math.round(w.min) + "°", 14, 0xCCFFFFFF));
        if (w.rain > 0) side.addView(text("Chuva " + w.rain + "%", 14, 0xCCFFFFFF));
        row.addView(side);
        weatherCard.addView(row);

        LinearLayout hours = new LinearLayout(home);
        hours.setPadding(0, home.dp(12), 0, 0);
        for (int[] h : w.hours) {
            LinearLayout c = new LinearLayout(home);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView(text(h[0] + "h", 13, 0xB3FFFFFF));
            boolean day = h[0] >= 6 && h[0] < 18;
            c.addView(text(Feeds.symbol(h[2], day), 20, Color.WHITE));
            c.addView(text(h[1] + "°", 15, Color.WHITE));
            hours.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        weatherCard.addView(hours);
    }

    private void bindQuotes() {
        quotesCard.removeAllViews();
        quotesCard.addView(label("Cotações"));
        List<Feeds.Quote> qs = feeds.quotes;
        if (qs.isEmpty()) {
            quotesCard.addView(text("Carregando as cotações…", 15, 0xCCFFFFFF));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < qs.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(home);
                row.setPadding(0, i == 0 ? 0 : home.dp(12), 0, 0);
                quotesCard.addView(row);
            }
            Feeds.Quote q = qs.get(i);
            LinearLayout c = new LinearLayout(home);
            c.setOrientation(LinearLayout.VERTICAL);
            c.addView(text(q.label, 13, 0xB3FFFFFF));
            c.addView(text(Feeds.value(q), 19, Color.WHITE));
            String ch = Feeds.change(q.change);
            if (!ch.isEmpty()) c.addView(text(ch, 13, q.change < 0 ? RED : GREEN));
            row.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }

    private void bindNews() {
        newsCard.removeAllViews();
        LinearLayout tabs = new LinearLayout(home);
        tabs.setPadding(0, 0, 0, home.dp(6));
        for (int i = 0; i < Feeds.NEWS_TABS.length; i++) {
            int index = i;
            TextView t = text(Feeds.NEWS_TABS[i], 14, i == tab ? 0xFF0E0F12 : Color.WHITE);
            t.setPadding(home.dp(14), home.dp(6), home.dp(14), home.dp(6));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(home.dp(16));
            bg.setColor(i == tab ? Color.WHITE : 0x1FFFFFFF);
            t.setBackground(bg);
            t.setOnClickListener(v -> { tab = index; bind(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = home.dp(8);
            tabs.addView(t, lp);
        }
        newsCard.addView(tabs);
        List<Feeds.Headline> list = feeds.news[tab];
        if (list.isEmpty()) {
            newsCard.addView(text("Carregando as notícias…", 15, 0xCCFFFFFF));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Feeds.Headline h = list.get(i);
            LinearLayout item = new LinearLayout(home);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(home.dp(4), home.dp(12), home.dp(4), home.dp(12));
            item.setBackground(home.ripple());
            String meta = h.source.isEmpty() ? Feeds.ago(h.at) : h.source + (h.at == 0 ? "" : " · " + Feeds.ago(h.at));
            TextView m = text(meta, 12, sourceColor(h.source));
            m.setTypeface(Typeface.DEFAULT_BOLD);
            m.setSingleLine();
            m.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(m);
            TextView t = text(h.title, 16, Color.WHITE);
            t.setMaxLines(3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setLineSpacing(0, 1.12f);
            t.setPadding(0, home.dp(3), 0, 0);
            item.addView(t);
            if (h.link != null) item.setOnClickListener(v -> home.start(new Intent(Intent.ACTION_VIEW, Uri.parse(h.link))));
            if (i > 0) {
                View line = new View(home);
                line.setBackgroundColor(0x1AFFFFFF);
                newsCard.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            newsCard.addView(item);
        }
    }

    /** Each paper keeps one light color, from its name. */
    private static int sourceColor(String source) {
        float hue = (source.hashCode() & 0x7fffffff) % 360;
        return Color.HSVToColor(new float[]{hue, 0.38f, 1f});
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(home);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(home.dp(18), home.dp(16), home.dp(18), home.dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99101114);
        bg.setCornerRadius(home.dp(24));
        bg.setStroke(1, 0x22FFFFFF);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = home.dp(12);
        parent.addView(c, lp);
        return c;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(), 12, 0x99FFFFFF);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, home.dp(8));
        return t;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(home);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }
}
