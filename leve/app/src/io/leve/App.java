package io.leve;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import java.text.Normalizer;
import java.util.Locale;

/** One launchable activity: what the list and the dock show. */
final class App {
    final ComponentName component;
    final UserHandle user;
    final String label;
    final String key;     // component + user, stored in the favorites
    final String search;  // label without accents, lower case
    final Drawable icon;

    App(LauncherActivityInfo info, int density, boolean mainUser) {
        component = info.getComponentName();
        user = info.getUser();
        label = info.getLabel().toString().trim();
        key = component.flattenToShortString() + (mainUser ? "" : "#" + user.hashCode());
        search = fold(label);
        icon = info.getBadgedIcon(density);
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** First letter for the fast scroller: A-Z, or # for digits and symbols. */
    String section() {
        char c = search.isEmpty() ? '#' : Character.toUpperCase(search.charAt(0));
        return c >= 'A' && c <= 'Z' ? String.valueOf(c) : "#";
    }
}
