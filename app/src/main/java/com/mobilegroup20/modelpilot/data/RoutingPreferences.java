package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.mobilegroup20.modelpilot.chat.AutoRouter;

/** Device-local routing preference. It only affects Auto, never an explicitly selected model. */
public final class RoutingPreferences {
    private RoutingPreferences() { }
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("auto_routing", Context.MODE_PRIVATE);
    }
    public static AutoRouter.Preference preference(Context context) {
        try {
            return AutoRouter.Preference.valueOf(prefs(context).getString("mode", "LOWEST_COST"));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return AutoRouter.Preference.LOWEST_COST;
        }
    }
    public static String preferredProvider(Context context) {
        return prefs(context).getString("preferred_provider", null);
    }
    public static void save(Context context, AutoRouter.Preference mode, String provider) {
        SharedPreferences.Editor editor = prefs(context).edit().putString("mode", mode.name());
        if (provider == null) editor.remove("preferred_provider");
        else editor.putString("preferred_provider", provider);
        editor.apply();
    }
}
