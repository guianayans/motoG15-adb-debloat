package io.leve;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole launcher: three pages over the wallpaper (widgets, clock, pinned apps) above a dock,
 * and a drawer with every app. Swipe sideways between pages, up for the drawer, down for the notifications.
 */
public class Home extends Activity {
    private static final int DOCK_MAX = 5, PINNED_MAX = 24;
    private static final int PAGE_WIDGETS = 0, PAGE_CLOCK = 1, PAGE_APPS = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LauncherApps launcher;
    private SharedPreferences prefs;
    private final Map<String, App> byKey = new HashMap<>();
    private List<App> apps = new ArrayList<>();
    private int loadGeneration;

    private Feeds feeds;
    private Widgets widgets;
    private Pager pager;
    private LinearLayout homeLayer, dock, dots, drawer;
    private TextView weatherLine, appsEmpty;
    private PinnedGrid pinned;
    private EditText search;
    private ListView list;
    private AppList adapter;
    private GestureDetector gestures;
    private boolean drawerOpen;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        launcher = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        prefs = getSharedPreferences("leve", MODE_PRIVATE);
        feeds = new Feeds(this, this::onFeeds);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(buildViews());
        gestures = new GestureDetector(this, new Swipes());
        launcher.registerCallback(changes, main);
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        feeds.refresh(false);
        widgets.bind(); // the "há X min" ages
    }

    @Override protected void onDestroy() {
        launcher.unregisterCallback(changes);
        worker.shutdownNow();
        feeds.shutdown();
        super.onDestroy();
    }

    /** The home button while already home: back to the clock. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (drawerOpen) closeDrawer(true);
        else if (hasWindowFocus()) pager.setPage(PAGE_CLOCK, true);
    }

    /** Coming back from an app lands where it left, but never on a half-typed search. */
    @Override protected void onStop() {
        super.onStop();
        closeDrawer(false);
    }

    @Override public void onBackPressed() {
        if (drawerOpen) closeDrawer(true);
        else pager.setPage(PAGE_CLOCK, true);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // --- Views

    private View buildViews() {
        FrameLayout root = new FrameLayout(this);

        homeLayer = new LinearLayout(this);
        homeLayer.setOrientation(LinearLayout.VERTICAL);

        pager = new Pager(this);
        widgets = new Widgets(this, feeds);
        pager.addView(widgets.view);
        pager.addView(clockPage());
        pager.addView(appsPage());
        homeLayer.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.WHITE);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(d, lp);
        }
        homeLayer.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        lp.bottomMargin = dp(12);
        homeLayer.addView(dock, lp);
        root.addView(homeLayer, new FrameLayout.LayoutParams(-1, -1));

        WallpaperManager wm = WallpaperManager.getInstance(this);
        pager.setListener(new Pager.Listener() {
            @Override public void onScrolled(float position) {
                for (int i = 0; i < dots.getChildCount(); i++) {
                    dots.getChildAt(i).setAlpha(0.35f + 0.65f * Math.max(0f, 1f - Math.abs(position - i)));
                }
                if (pager.getWindowToken() != null) {
                    wm.setWallpaperOffsets(pager.getWindowToken(), Math.max(0f, Math.min(1f, position / 2f)), 0.5f);
                }
            }
            @Override public void onPage(int page) {
                if (page == PAGE_WIDGETS) feeds.refresh(false);
            }
        });
        wm.setWallpaperOffsetSteps(0.5f, 0f);
        pager.setPage(PAGE_CLOCK, false);

        root.addView(buildDrawer(), new FrameLayout.LayoutParams(-1, -1));

        // Edge to edge: the bars' room as padding; with the keyboard up, its room too.
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getSystemWindowInsetTop(), bottom = in.getSystemWindowInsetBottom();
            homeLayer.setPadding(0, top, 0, in.getStableInsetBottom());
            drawer.setPadding(0, top, 0, 0);
            list.setPadding(0, 0, 0, bottom);
            return in;
        });
        return root;
    }

    private View clockPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setOnLongClickListener(v -> { homeMenu(v); return true; });

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        shadowed(clock);
        clock.setOnClickListener(v -> start(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(56);
        lp.leftMargin = dp(28);
        page.addView(clock, lp);

        TextClock date = new TextClock(this);
        String day = "EEEE, d 'de' MMMM";
        date.setFormat24Hour(day);
        date.setFormat12Hour(day);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        shadowed(date);
        date.setOnClickListener(v -> start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)));
        lp = wrap();
        lp.leftMargin = dp(30);
        page.addView(date, lp);

        weatherLine = new TextView(this);
        weatherLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        shadowed(weatherLine);
        weatherLine.setAlpha(0.9f);
        weatherLine.setOnClickListener(v -> pager.setPage(PAGE_WIDGETS, true));
        lp = wrap();
        lp.leftMargin = dp(30);
        lp.topMargin = dp(6);
        page.addView(weatherLine, lp);
        bindWeatherLine();
        return page;
    }

    private View appsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setOnLongClickListener(v -> { homeMenu(v); return true; });
        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        shadowed(title);
        title.setPadding(dp(22), dp(20), 0, dp(12));
        page.addView(title);

        appsEmpty = new TextView(this);
        appsEmpty.setText("Abra a lista de apps (deslize para cima), toque e segure um app e escolha \"Fixar na página de apps\".");
        appsEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        shadowed(appsEmpty);
        appsEmpty.setAlpha(0.85f);
        appsEmpty.setPadding(dp(24), dp(8), dp(24), 0);
        page.addView(appsEmpty);

        pinned = new PinnedGrid();
        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(18));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setPadding(dp(8), dp(4), dp(8), dp(8));
        grid.setClipToPadding(false);
        grid.setAdapter(pinned);
        grid.setOnItemClickListener((p, v, i, id) -> launch(pinned.getItem(i), v));
        grid.setOnItemLongClickListener((p, v, i, id) -> { appMenu(pinned.getItem(i), v); return true; });
        page.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private View buildDrawer() {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xEB0A0A0C);
        drawer.setVisibility(View.GONE);

        search = new EditText(this);
        search.setHint("Buscar apps");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(Color.WHITE);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x22FFFFFF);
        pill.setCornerRadius(dp(26));
        search.setBackground(pill);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { adapter.filter(s.toString()); list.setSelection(0); }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            if (adapter.getCount() > 0) launch(adapter.getItem(0), v);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(dp(16), dp(12), dp(16), dp(8));
        drawer.addView(search, lp);

        adapter = new AppList(this);
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setFastScrollEnabled(true);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setOnItemClickListener((p, v, i, id) -> launch(adapter.getItem(i), v));
        list.setOnItemLongClickListener((p, v, i, id) -> { appMenu(adapter.getItem(i), v); return true; });
        drawer.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return drawer;
    }

    private void fillDock() {
        dock.removeAllViews();
        for (String key : keys("favorites")) {
            App a = byKey.get(key);
            if (a == null) continue;
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(a.icon);
            icon.setContentDescription(a.label);
            icon.setPadding(dp(6), dp(6), dp(6), dp(6));
            icon.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null));
            icon.setOnClickListener(v -> launch(a, v));
            icon.setOnLongClickListener(v -> { appMenu(a, v); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
            lp.setMargins(dp(5), 0, dp(5), 0);
            dock.addView(icon, lp);
        }
    }

    private void fillPinned() {
        List<App> list = new ArrayList<>();
        for (String key : keys("pinned")) {
            App a = byKey.get(key);
            if (a != null) list.add(a);
        }
        pinned.apps = list;
        pinned.notifyDataSetChanged();
        appsEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onFeeds() {
        widgets.bind();
        bindWeatherLine();
    }

    private void bindWeatherLine() {
        Feeds.Weather w = feeds.weather;
        weatherLine.setText(w == null ? "" : Feeds.symbol(w.code, w.day) + "  " + Math.round(w.temp) + "° · " + Feeds.sky(w.code));
    }

    /** The apps page grid: icon over a one-line name. */
    private final class PinnedGrid extends BaseAdapter {
        List<App> apps = new ArrayList<>();

        @Override public int getCount() { return apps.size(); }
        @Override public App getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View view, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) view;
            if (cell == null) {
                cell = new LinearLayout(Home.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.addView(new ImageView(Home.this), new LinearLayout.LayoutParams(dp(56), dp(56)));
                TextView name = new TextView(Home.this);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                name.setSingleLine();
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER);
                name.setPadding(dp(4), dp(6), dp(4), 0);
                shadowed(name);
                cell.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            App a = apps.get(i);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
            ((TextView) cell.getChildAt(1)).setText(a.label);
            return cell;
        }
    }

    // --- Drawer

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        list.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(dp(48));
        drawer.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(null).start();
        homeLayer.animate().alpha(0f).setDuration(150).start();
    }

    private void closeDrawer(boolean animate) {
        if (!drawerOpen) return;
        drawerOpen = false;
        hideKeyboard();
        search.setText("");
        search.clearFocus();
        drawer.animate().cancel();
        homeLayer.animate().cancel();
        if (!animate) {
            drawer.setVisibility(View.GONE);
            homeLayer.setAlpha(1f);
            return;
        }
        drawer.animate().alpha(0f).translationY(dp(48)).setDuration(170).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        homeLayer.animate().alpha(1f).setDuration(200).start();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private final class Swipes extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
            if (down == null || Math.abs(vy) < Math.abs(vx) * 1.4f || Math.abs(vy) < dp(350)) return false;
            if (!drawerOpen) {
                // The widgets page scrolls up and down itself.
                if (pager.isDragging() || pager.page() == PAGE_WIDGETS) return false;
                if (vy < 0) openDrawer();
                else expandNotifications();
            } else if (vy > 0 && !list.canScrollVertically(-1) && down.getY() > search.getBottom()) {
                closeDrawer(true);
            }
            return false;
        }
    }

    /** No public API for this; the permission is a normal one, the method has been there since Android 4.2. */
    private void expandNotifications() {
        try {
            Object bar = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(bar);
        } catch (Exception ignored) {
        }
    }

    // --- Apps

    private void launch(App a, View from) {
        Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        try {
            launcher.startMainActivity(a.component, a.user, null, opts);
        } catch (RuntimeException e) {
            reload(); // uninstalled meanwhile
        }
    }

    private void appMenu(App a, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean fav = keys("favorites").contains(a.key), pin = keys("pinned").contains(a.key);
        if (fav) menu.getMenu().add(0, 1, 0, "Remover dos favoritos");
        else if (keys("favorites").size() < DOCK_MAX) menu.getMenu().add(0, 1, 0, "Adicionar aos favoritos");
        if (pin) menu.getMenu().add(0, 4, 0, "Remover da página de apps");
        else if (keys("pinned").size() < PINNED_MAX) menu.getMenu().add(0, 4, 0, "Fixar na página de apps");
        menu.getMenu().add(0, 2, 0, "Informações do app");
        menu.getMenu().add(0, 3, 0, "Desinstalar");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    toggle("favorites", a.key);
                    fillDock();
                    break;
                case 4:
                    toggle("pinned", a.key);
                    fillPinned();
                    break;
                case 2:
                    launcher.startAppDetailsActivity(a.component, a.user, null, null);
                    break;
                case 3:
                    start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.component.getPackageName())));
                    break;
            }
            return true;
        });
        menu.show();
    }

    private void homeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.CENTER);
        menu.getMenu().add(0, 1, 0, "Papel de parede");
        menu.getMenu().add(0, 2, 0, "Configurações");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) start(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Papel de parede"));
            else start(new Intent(Settings.ACTION_SETTINGS));
            return true;
        });
        menu.show();
    }

    private final LauncherApps.Callback changes = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { reload(); }
        @Override public void onPackageAdded(String p, UserHandle u) { reload(); }
        @Override public void onPackageChanged(String p, UserHandle u) { reload(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reload(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reload(); }
    };

    /** Reads the app list and the icons off the main thread; the newest request wins. */
    private void reload() {
        int gen = ++loadGeneration;
        int density = getResources().getDisplayMetrics().densityDpi;
        worker.execute(() -> {
            UserHandle me = Process.myUserHandle();
            List<App> found = new ArrayList<>();
            for (UserHandle u : launcher.getProfiles()) {
                for (LauncherActivityInfo info : launcher.getActivityList(null, u)) {
                    if (info.getComponentName().getPackageName().equals(getPackageName())) continue;
                    found.add(new App(info, density, u.equals(me)));
                }
            }
            Collator order = Collator.getInstance(new Locale("pt", "BR"));
            order.setStrength(Collator.PRIMARY);
            found.sort((x, y) -> order.compare(x.label, y.label));
            main.post(() -> {
                if (gen != loadGeneration || isDestroyed()) return;
                apps = found;
                byKey.clear();
                for (App a : apps) byKey.put(a.key, a);
                if (!prefs.contains("favorites")) setKeys("favorites", defaultFavorites());
                adapter.setApps(apps);
                fillDock();
                fillPinned();
            });
        });
    }

    private List<String> keys(String pref) {
        String s = prefs.getString(pref, "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    private void setKeys(String pref, List<String> keys) {
        prefs.edit().putString(pref, String.join("\n", keys)).apply();
    }

    private void toggle(String pref, String key) {
        List<String> k = keys(pref);
        if (!k.remove(key)) k.add(key);
        setKeys(pref, k);
    }

    /** First run: phone, messages, WhatsApp, browser and camera, whichever of them exist. */
    private List<String> defaultFavorites() {
        List<String> pkgs = new ArrayList<>();
        TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (tm != null) pkgs.add(tm.getDefaultDialerPackage());
        pkgs.add(Telephony.Sms.getDefaultSmsPackage(this));
        pkgs.add("com.whatsapp");
        pkgs.add("com.whatsapp.w4b");
        pkgs.add(handler(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))));
        pkgs.add(handler(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
        List<String> keys = new ArrayList<>();
        for (String p : pkgs) {
            if (p == null || keys.size() >= DOCK_MAX) continue;
            for (App a : apps) {
                if (a.component.getPackageName().equals(p) && a.user.equals(Process.myUserHandle()) && !keys.contains(a.key)) {
                    keys.add(a.key);
                    break;
                }
            }
        }
        return keys;
    }

    private String handler(Intent intent) {
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(intent, 0);
        return r == null || r.activityInfo == null ? null : r.activityInfo.packageName;
    }

    // --- Helpers

    void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private static void shadowed(TextView t) {
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(10, 0, 2, 0x80000000);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    Drawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, new ColorDrawable(Color.WHITE));
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
