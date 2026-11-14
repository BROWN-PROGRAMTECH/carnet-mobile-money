package cm.carnet.mobilemoney;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Compte et sauvegarde en ligne (Supabase), en HTTP simple, sans bibliothèque externe.
 * Connexion : numéro de téléphone + code secret (converti en identifiant interne, aucun e-mail n'est envoyé).
 * Synchronisation : chaque table reçoit d'abord les lignes modifiées ailleurs, puis envoie les siennes.
 * Les règles d'accès par ligne de Supabase garantissent qu'un compte ne voit que ses propres données.
 */
public final class Cloud {
    private Cloud() {}

    public static final String VERSION_APP = "0.4.1";
    private static final String DOMAIN = "@carnet-mm.app";
    private static final int PAGE = 1000, BATCH = 500;

    public static final class CloudException extends Exception {
        public CloudException(String message) { super(message); }
    }

    public static boolean configured() {
        return !CloudConfig.URL.trim().isEmpty() && !CloudConfig.ANON_KEY.trim().isEmpty();
    }

    private static String base() {
        String u = CloudConfig.URL.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** "+237 6 99 11 22 33" -> "699112233", ou null si ce n'est pas un numéro camerounais. */
    public static String phone9(String input) {
        String d = input == null ? "" : input.replaceAll("\\D", "");
        if (d.length() > 9) d = d.substring(d.length() - 9);
        return d.matches("6\\d{8}") ? d : null;
    }

    private static String email(String phone9) { return "237" + phone9 + DOMAIN; }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cloud", Context.MODE_PRIVATE);
    }

    public static boolean loggedIn(Context c) { return !prefs(c).getString("refresh_token", "").isEmpty(); }

    public static String phone(Context c) { return prefs(c).getString("phone", ""); }

    public static long lastSync(Context c) { return prefs(c).getLong("last_sync", 0); }

    public static String lastError(Context c) { return prefs(c).getString("last_error", ""); }

    static void saveError(Context c, String message) {
        prefs(c).edit().putString("last_error", message == null ? "Erreur inconnue" : message).apply();
    }

    // ---------- Compte ----------

    public static void signUp(Context c, String phone9, String secret) throws CloudException {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email(phone9));
            body.put("password", secret);
            body.put("data", new JSONObject().put("telephone", phone9));
            JSONObject r = new JSONObject(http("POST", "/auth/v1/signup", body.toString(), null, null));
            if (!r.has("access_token")) {
                throw new CloudException("Compte créé, mais Supabase attend une confirmation par e-mail. Dans Supabase : Authentication → Sign In / Providers → Email, désactivez « Confirm email », puis touchez « Me connecter ».");
            }
            saveSession(c, r, phone9);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    public static void signIn(Context c, String phone9, String secret) throws CloudException {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email(phone9));
            body.put("password", secret);
            JSONObject r = new JSONObject(http("POST", "/auth/v1/token?grant_type=password", body.toString(), null, null));
            saveSession(c, r, phone9);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /** Déconnecte ce téléphone. Les données restent sur le téléphone et dans le compte en ligne. */
    public static void signOut(Context c) {
        String token = prefs(c).getString("access_token", "");
        if (!token.isEmpty()) {
            try { http("POST", "/auth/v1/logout", "{}", token, null); } catch (Exception ignored) {}
        }
        String device = prefs(c).getString("device_id", "");
        prefs(c).edit().clear().putString("device_id", device).apply();
    }

    private static void saveSession(Context c, JSONObject r, String phone9) throws Exception {
        String access = r.getString("access_token");
        String refresh = r.getString("refresh_token");
        long expiresIn = r.optLong("expires_in", 3600);
        JSONObject user = r.optJSONObject("user");
        SharedPreferences.Editor e = prefs(c).edit()
                .putString("access_token", access)
                .putString("refresh_token", refresh)
                .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L);
        if (user != null) e.putString("user_id", user.getString("id"));
        if (phone9 != null) e.putString("phone", phone9);
        e.apply();
    }

    /** Jeton d'accès valide, renouvelé si besoin. */
    static String token(Context c) throws CloudException {
        SharedPreferences p = prefs(c);
        String access = p.getString("access_token", "");
        if (!access.isEmpty() && p.getLong("expires_at", 0) - 60000 > System.currentTimeMillis()) return access;
        String refresh = p.getString("refresh_token", "");
        if (refresh.isEmpty()) throw new CloudException("Non connecté.");
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refresh);
            JSONObject r = new JSONObject(http("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), null, null));
            saveSession(c, r, null);
            return r.getString("access_token");
        } catch (CloudException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Pas de connexion")) throw e;
            signOut(c);
            throw new CloudException("Session expirée : reconnectez-vous dans « Compte et sauvegarde ».");
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    // ---------- Synchronisation ----------

    /** Reçoit puis envoie toutes les modifications ; renvoie un résumé lisible. */
    public static synchronized String sync(Context ctx) throws CloudException {
        Context c = ctx.getApplicationContext();
        if (!configured()) throw new CloudException("Sauvegarde en ligne non configurée dans cette version de l'application.");
        String tok = token(c);
        String userId = prefs(c).getString("user_id", "");
        if (userId.isEmpty()) throw new CloudException("Compte incomplet : reconnectez-vous.");
        Store st = Store.get(c);
        int pulled = 0, pushed = 0;
        try {
            for (Store.Spec s : Store.SYNC) {
                String select = String.join(",", s.cols);

                // 1. Recevoir ce qui a été modifié ailleurs (autre téléphone, restauration).
                long since = prefs(c).getLong("pull_" + s.table, 0), newest = since;
                for (int offset = 0; ; offset += PAGE) {
                    String q = "/rest/v1/" + s.table + "?select=" + select + "&maj=gt." + since + "&order=maj.asc&limit=" + PAGE + "&offset=" + offset;
                    JSONArray arr = new JSONArray(http("GET", q, null, tok, null));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        Object[] row = new Object[s.cols.length];
                        for (int k = 0; k < s.cols.length; k++) {
                            String col = s.cols[k];
                            if (!o.has(col) || o.isNull(col)) row[k] = null;
                            else row[k] = s.numeric[k] ? (Object) o.getLong(col) : o.getString(col);
                        }
                        st.merge(s, row);
                        Object m = row[s.cols.length - 1];
                        if (m instanceof Long && (Long) m > newest) newest = (Long) m;
                    }
                    pulled += arr.length();
                    if (arr.length() < PAGE) break;
                }
                prefs(c).edit().putLong("pull_" + s.table, newest).apply();

                // 2. Envoyer ce qui a été modifié sur ce téléphone.
                long sent = prefs(c).getLong("push_" + s.table, 0);
                while (true) {
                    List<Object[]> rows = st.changedSince(s, sent, BATCH);
                    if (rows.isEmpty()) break;
                    JSONArray arr = new JSONArray();
                    long max = sent;
                    for (Object[] row : rows) {
                        JSONObject o = new JSONObject();
                        o.put("user_id", userId);
                        for (int k = 0; k < s.cols.length; k++) o.put(s.cols[k], row[k] == null ? JSONObject.NULL : row[k]);
                        Object m = row[s.cols.length - 1];
                        if (m instanceof Long && (Long) m > max) max = (Long) m;
                        arr.put(o);
                    }
                    http("POST", "/rest/v1/" + s.table + "?on_conflict=user_id," + s.key, arr.toString(), tok,
                            "resolution=merge-duplicates,return=minimal");
                    pushed += rows.size();
                    sent = max;
                    prefs(c).edit().putLong("push_" + s.table, sent).apply();
                    if (rows.size() < BATCH) break;
                }
            }
            registerDevice(c, tok, userId);
        } catch (CloudException e) {
            saveError(c, e.getMessage());
            throw e;
        } catch (Exception e) {
            saveError(c, e.getMessage());
            throw new CloudException("Sauvegarde interrompue : " + e.getMessage());
        }
        prefs(c).edit().putLong("last_sync", System.currentTimeMillis()).putString("last_error", "").apply();
        return pulled + " élément(s) reçu(s), " + pushed + " envoyé(s).";
    }

    /** Fiche de l'appareil, pour le suivi du parc (tableau de bord superadmin à venir). */
    private static void registerDevice(Context c, String tok, String userId) throws Exception {
        String device = prefs(c).getString("device_id", "");
        if (device.isEmpty()) {
            device = UUID.randomUUID().toString();
            prefs(c).edit().putString("device_id", device).apply();
        }
        JSONObject o = new JSONObject();
        o.put("user_id", userId);
        o.put("device_id", device);
        o.put("modele", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        o.put("version_app", VERSION_APP);
        o.put("dernier_sync", System.currentTimeMillis());
        http("POST", "/rest/v1/appareil?on_conflict=user_id,device_id", new JSONArray().put(o).toString(), tok,
                "resolution=merge-duplicates,return=minimal");
    }

    // ---------- HTTP ----------

    static String http(String method, String path, String body, String bearer, String prefer) throws CloudException {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(base() + path).openConnection();
            con.setRequestMethod(method);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("apikey", CloudConfig.ANON_KEY.trim());
            // Jeton de l'utilisateur seulement : la clé publique (ancienne « anon » ou nouvelle « publishable ») passe par apikey.
            if (bearer != null) con.setRequestProperty("Authorization", "Bearer " + bearer);
            con.setRequestProperty("Accept", "application/json");
            if (prefer != null) con.setRequestProperty("Prefer", prefer);
            if (body != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String text = read(in);
            if (code >= 400) throw new CloudException(message(code, text));
            return text.isEmpty() ? "[]" : text;
        } catch (CloudException e) {
            throw e;
        } catch (IOException e) {
            throw new CloudException("Pas de connexion internet ou serveur injoignable. Réessayez quand le réseau revient.");
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Traduit les erreurs du serveur en messages compréhensibles. */
    static String message(int code, String text) {
        String raw = text == null ? "" : text;
        try {
            JSONObject o = new JSONObject(raw);
            for (String k : new String[] {"msg", "error_description", "message", "error"}) {
                if (o.has(k) && !o.isNull(k)) { raw = o.getString(k); break; }
            }
        } catch (Exception ignored) {}
        String low = raw.toLowerCase();
        if (low.contains("invalid login credentials")) return "Numéro ou code secret incorrect.";
        if (low.contains("already registered") || low.contains("already exists")) return "Un compte existe déjà avec ce numéro : touchez « Me connecter ».";
        if (low.contains("at least 6") || low.contains("password should")) return "Le code secret doit faire au moins 6 caractères.";
        if (low.contains("email not confirmed")) return "Compte non confirmé. Dans Supabase, désactivez « Confirm email » (Authentication → Sign In / Providers → Email).";
        if (low.contains("signups not allowed") || low.contains("signup") && low.contains("disabled")) return "Les inscriptions sont désactivées dans Supabase (Authentication → Sign In / Providers).";
        if (low.contains("rate limit")) return "Trop de tentatives : patientez quelques minutes.";
        if (low.contains("relation") && low.contains("does not exist") || low.contains("could not find the table")) return "Tables absentes sur le serveur : exécutez le fichier supabase/schema.sql dans Supabase.";
        if (code == 401 || code == 403) return "Accès refusé par le serveur (" + code + "). Reconnectez-vous ; si le problème continue, vérifiez la clé dans CloudConfig.java.";
        return "Erreur du serveur (" + code + ") : " + (raw.length() > 160 ? raw.substring(0, 160) : raw);
    }
}
