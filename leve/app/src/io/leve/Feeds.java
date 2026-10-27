package io.leve;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The widgets' data, fetched only while the home screen shows and only when stale: the weather (Open-Meteo),
 * the dollar, euro and bitcoin (AwesomeAPI), the Ibovespa (Yahoo Finance) and Google News headlines. No keys.
 * The last answers stay in the preferences, shown at once and offline.
 */
final class Feeds {
    static final double LAT = -16.6869, LON = -49.2648;
    static final String CITY = "Goiânia";

    static final String[] NEWS_TABS = {"Principais", "Mercado"};
    private static final String[] NEWS_URLS = {
        "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419",
        "https://news.google.com/rss/search?q=ibovespa+OR+%22bolsa+brasileira%22+OR+B3+OR+d%C3%B3lar+when:1d&hl=pt-BR&gl=BR&ceid=BR:pt-419",
    };
    private static final String WEATHER = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
            + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code"
            + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2";
    private static final String FX = "https://economia.awesomeapi.com.br/json/last/USD-BRL,EUR-BRL,BTC-BRL";
    private static final String IBOV = "https://query1.finance.yahoo.com/v8/finance/chart/%5EBVSP?range=1d&interval=1d";
    private static final long WEATHER_EVERY = 30 * 60_000L, QUOTES_EVERY = 5 * 60_000L, NEWS_EVERY = 15 * 60_000L;
    private static final int MAX_HEADLINES = 15;

    static final class Weather {
        double temp, max, min;
        int code, rain;
        boolean day;
        final List<int[]> hours = new ArrayList<>(); // {hour, temp, code}
    }

    static final class Quote {
        final String label;
        final double value, change;

        Quote(String label, double value, double change) {
            this.label = label;
            this.value = value;
            this.change = change;
        }
    }

    static final class Headline {
        final String title, source, link;
        final long at;

        Headline(String title, String source, String link, long at) {
            this.title = title;
            this.source = source;
            this.link = link;
            this.at = at;
        }
    }

    volatile Weather weather;
    volatile List<Quote> quotes = Collections.emptyList();
    @SuppressWarnings("unchecked")
    final List<Headline>[] news = new List[NEWS_URLS.length];
    volatile long weatherAt, quotesAt;
    final long[] newsAt = new long[NEWS_URLS.length];

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onChange;
    private boolean busy;

    Feeds(Context c, Runnable onChange) {
        this.onChange = onChange;
        cache = c.getSharedPreferences("feeds", Context.MODE_PRIVATE);
        for (int i = 0; i < news.length; i++) news[i] = Collections.emptyList();
        try {
            String w = cache.getString("weather", null);
            if (w != null) { weather = parseWeather(w); weatherAt = cache.getLong("weatherAt", 0); }
        } catch (Exception ignored) {}
        try {
            String fx = cache.getString("fx", null), ib = cache.getString("ibov", null);
            if (fx != null || ib != null) { quotes = parseQuotes(fx, ib); quotesAt = cache.getLong("quotesAt", 0); }
        } catch (Exception ignored) {}
        for (int i = 0; i < news.length; i++) {
            try {
                String body = cache.getString("news" + i, null);
                if (body != null) { news[i] = parseFeed(body); newsAt[i] = cache.getLong("newsAt" + i, 0); }
            } catch (Exception ignored) {}
        }
    }

    void shutdown() { io.shutdownNow(); }

    /** Everything stale, in one background pass; force ignores the ages (the pull to refresh). */
    void refresh(boolean force) {
        if (busy) return;
        long now = System.currentTimeMillis();
        boolean w = force || stale(weatherAt, WEATHER_EVERY, now), q = force || stale(quotesAt, QUOTES_EVERY, now);
        boolean[] n = new boolean[news.length];
        boolean any = w || q;
        for (int i = 0; i < n.length; i++) { n[i] = force || stale(newsAt[i], NEWS_EVERY, now); any |= n[i]; }
        if (!any) return;
        busy = true;
        io.execute(() -> {
            try {
                SharedPreferences.Editor e = cache.edit();
                if (w) {
                    try {
                        String body = get(WEATHER);
                        weather = parseWeather(body);
                        weatherAt = System.currentTimeMillis();
                        e.putString("weather", body).putLong("weatherAt", weatherAt);
                        post();
                    } catch (Exception ignored) {}
                }
                if (q) {
                    String fx = null, ib = null;
                    try { fx = get(FX); } catch (Exception ignored) {}
                    try { ib = get(IBOV); } catch (Exception ignored) {}
                    if (fx != null || ib != null) {
                        try {
                            quotes = parseQuotes(fx != null ? fx : cache.getString("fx", null), ib != null ? ib : cache.getString("ibov", null));
                            quotesAt = System.currentTimeMillis();
                            if (fx != null) e.putString("fx", fx);
                            if (ib != null) e.putString("ibov", ib);
                            e.putLong("quotesAt", quotesAt);
                            post();
                        } catch (Exception ignored) {}
                    }
                }
                for (int i = 0; i < n.length; i++) {
                    if (!n[i]) continue;
                    try {
                        String body = get(NEWS_URLS[i]);
                        List<Headline> list = parseFeed(body);
                        if (list.isEmpty()) continue;
                        news[i] = list;
                        newsAt[i] = System.currentTimeMillis();
                        e.putString("news" + i, body).putLong("newsAt" + i, newsAt[i]);
                        post();
                    } catch (Exception ignored) {}
                }
                e.apply();
            } finally {
                main.post(() -> busy = false);
            }
        });
    }

    private void post() { main.post(onChange); }

    /** A negative age (the clock went back) counts as stale. */
    private static boolean stale(long at, long every, long now) {
        return now - at > every || now < at;
    }

    // --- Parsing

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        Weather w = new Weather();
        JSONObject cur = o.getJSONObject("current");
        w.temp = cur.getDouble("temperature_2m");
        w.code = cur.getInt("weather_code");
        w.day = cur.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.rain = daily.getJSONArray("precipitation_probability_max").optInt(0, 0);
        // The next six hours from now, from the hourly series (local times, "2026-10-27T21:00").
        JSONObject hourly = o.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time"), temps = hourly.getJSONArray("temperature_2m"), codes = hourly.getJSONArray("weather_code");
        String nowHour = cur.getString("time").substring(0, 13);
        int start = 0;
        for (int i = 0; i < times.length(); i++) if (times.getString(i).substring(0, 13).compareTo(nowHour) <= 0) start = i;
        for (int i = start + 1; i < times.length() && w.hours.size() < 6; i++) {
            int hour = Integer.parseInt(times.getString(i).substring(11, 13));
            w.hours.add(new int[]{hour, (int) Math.round(temps.getDouble(i)), codes.getInt(i)});
        }
        return w;
    }

    /** Dólar, Euro, Ibovespa, Bitcoin: whichever of the two answers arrived. */
    private static List<Quote> parseQuotes(String fx, String ibov) throws Exception {
        List<Quote> out = new ArrayList<>();
        JSONObject o = fx == null ? new JSONObject() : new JSONObject(fx);
        String[][] pairs = {{"USDBRL", "Dólar"}, {"EURBRL", "Euro"}, {null, "Ibovespa"}, {"BTCBRL", "Bitcoin"}};
        for (String[] p : pairs) {
            if (p[0] == null) {
                if (ibov != null) out.add(ibov(ibov));
                continue;
            }
            JSONObject q = o.optJSONObject(p[0]);
            if (q != null) out.add(new Quote(p[1], Double.parseDouble(q.getString("bid")), q.optDouble("pctChange", Double.NaN)));
        }
        return out;
    }

    private static Quote ibov(String body) throws Exception {
        JSONObject meta = new JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
        double price = meta.getDouble("regularMarketPrice");
        double change = meta.optDouble("regularMarketChangePercent", Double.NaN);
        double previous = meta.optDouble("chartPreviousClose", Double.NaN);
        if (Double.isNaN(change) && previous > 0) change = (price / previous - 1) * 100;
        return new Quote("Ibovespa", price, change);
    }

    /** RSS 2.0 items: title (Google News appends " - Source"), source, link and date, newest first. */
    private static List<Headline> parseFeed(String body) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(body));
        SimpleDateFormat rfc822 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        List<Headline> out = new ArrayList<>();
        String title = null, source = null, link = null, date = null, tag = null;
        boolean inItem = false;
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event == XmlPullParser.START_TAG) {
                tag = p.getName();
                if ("item".equals(tag)) {
                    inItem = true;
                    title = source = link = date = null;
                }
            } else if (event == XmlPullParser.TEXT && inItem && tag != null) {
                String text = p.getText();
                if ("title".equals(tag)) title = title == null ? text : title + text;
                else if ("source".equals(tag)) source = source == null ? text : source + text;
                else if ("link".equals(tag)) link = link == null ? text : link + text;
                else if ("pubDate".equals(tag)) date = date == null ? text : date + text;
            } else if (event == XmlPullParser.END_TAG) {
                if ("item".equals(p.getName()) && title != null) {
                    inItem = false;
                    String t = title.trim().replaceAll("\\s+", " "), s = source == null ? "" : source.trim();
                    if (!s.isEmpty() && t.endsWith(" - " + s)) t = t.substring(0, t.length() - s.length() - 3).trim();
                    long at = 0;
                    try {
                        Date d = date == null ? null : rfc822.parse(date.trim());
                        if (d != null) at = d.getTime();
                    } catch (Exception ignored) {}
                    if (!t.isEmpty()) out.add(new Headline(t, s, link == null ? null : link.trim(), at));
                }
                tag = null;
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        return out.size() > MAX_HEADLINES ? new ArrayList<>(out.subList(0, MAX_HEADLINES)) : out;
    }

    // --- Text

    private static final Locale BR = new Locale("pt", "BR");

    static String value(Quote q) {
        if (q.label.equals("Ibovespa")) return String.format(BR, "%,.0f", q.value);
        if (q.label.equals("Bitcoin")) return String.format(BR, "R$ %,.0f", q.value);
        return String.format(BR, "R$ %.2f", q.value);
    }

    /** +0,42% · −1,10% (a real minus sign). */
    static String change(double c) {
        if (Double.isNaN(c)) return "";
        return (c < 0 ? "−" : "+") + String.format(BR, "%.2f%%", Math.abs(c));
    }

    /** "agora" · "há 12 min" · "há 3 h" · "há 2 d"; empty without a date. */
    static String ago(long at) {
        if (at == 0) return "";
        long min = (System.currentTimeMillis() - at) / 60_000;
        if (min < 1) return "agora";
        if (min < 60) return "há " + min + " min";
        if (min < 48 * 60) return "há " + min / 60 + " h";
        return "há " + min / 1440 + " d";
    }

    /** WMO weather code to a word and a symbol. */
    static String sky(int code) {
        if (code == 0) return "Céu limpo";
        if (code <= 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code <= 48) return "Neblina";
        if (code <= 57) return "Garoa";
        if (code <= 67 || (code >= 80 && code <= 82)) return "Chuva";
        if (code <= 77 || code == 85 || code == 86) return "Neve";
        return "Tempestade";
    }

    static String symbol(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫️";
        if (code <= 67 || (code >= 80 && code <= 82)) return "🌧️";
        if (code <= 77 || code == 85 || code == 86) return "❄️";
        return "⛈️";
    }

    // --- Network

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Leve");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
