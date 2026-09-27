package com.mobilegroup20.tokentrail.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.mobilegroup20.tokentrail.BuildConfig;
import com.mobilegroup20.tokentrail.data.repository.SessionProvider;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One session for the team's existing account service, encrypted with Android Keystore. */
public final class TeamAccountSession implements SessionProvider {
    private static final String KEY_ALIAS = "tokentrail-account-session";
    private static TeamAccountSession instance;
    private final SharedPreferences preferences;
    private volatile Snapshot current;

    private static final class Snapshot {
        final String token, id, name;
        final boolean forumTest;
        Snapshot(String token, String id, String name, boolean forumTest) {
            this.token = token; this.id = id; this.name = name; this.forumTest = forumTest;
        }
    }
    public static synchronized TeamAccountSession get(Context context) {
        if (instance == null) instance = new TeamAccountSession(context.getApplicationContext());
        return instance;
    }
    private TeamAccountSession(Context context) {
        preferences = context.getSharedPreferences("account_session", Context.MODE_PRIVATE);
        String stored = preferences.getString("encrypted", null);
        if (stored == null) return;
        try {
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONObject value = new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            if (!BuildConfig.FORUM_BASE_URL.equals(value.getString("origin"))) throw new IllegalStateException("Account endpoint changed");
            String token = value.getString("token"), id = value.getString("id"), name = value.getString("name");
            if (token.isEmpty() || id.isEmpty() || name.isEmpty()) throw new IllegalStateException("Empty session");
            boolean forumTest = value.optBoolean("forumTest", false);
            if (forumTest && !BuildConfig.DEBUG) throw new IllegalStateException("Test sessions are debug only");
            current = new Snapshot(token, id, name, forumTest);
        } catch (Exception ignored) {
            // Keystore keys do not survive a backup restore; require a fresh login.
            preferences.edit().remove("encrypted").apply();
        }
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public synchronized void save(String token, String id, String name) throws Exception {
        save(token, id, name, false);
    }
    public synchronized void saveForumTest(String token, String id, String name) throws Exception {
        if (!BuildConfig.DEBUG || token == null || !token.startsWith("tt_test_") || id == null || !id.startsWith("test_"))
            throw new IllegalArgumentException("Invalid test session");
        save(token, id, name, true);
    }
    private void save(String token, String id, String name, boolean forumTest) throws Exception {
        if (token == null || token.isEmpty() || id == null || id.isEmpty() || name == null || name.isEmpty())
            throw new IllegalArgumentException("Account response is incomplete");
        JSONObject value = new JSONObject().put("origin", BuildConfig.FORUM_BASE_URL).put("token", token).put("id", id).put("name", name).put("forumTest", forumTest);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!preferences.edit().putString("encrypted", stored).commit()) throw new IllegalStateException("Could not save session");
        current = new Snapshot(token, id, name, forumTest);
    }
    public synchronized void clear() {
        current = null; preferences.edit().remove("encrypted").apply();
    }
    @Override public String token() { Snapshot value = current; return value == null ? null : value.token; }
    @Override public String accountId() { Snapshot value = current; return value == null ? null : value.id; }
    public String accountName() { Snapshot value = current; return value == null ? null : value.name; }
    public boolean signedIn() { return current != null; }
    public boolean forumTest() { Snapshot value = current; return value != null && value.forumTest; }
    public String forumBaseUrl() { return forumTest() ? testBaseUrl() : BuildConfig.FORUM_BASE_URL; }
    public static String testBaseUrl() {
        return okhttp3.HttpUrl.get(BuildConfig.FORUM_BASE_URL).newBuilder().encodedPath("/test-api/").build().toString();
    }
}
