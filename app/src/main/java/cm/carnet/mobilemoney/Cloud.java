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

    public static final String VERSION_APP = "0.6.2";
    private static final String DOMAIN = "@carnet-mm.app";
    private static final int PAGE = 1000, BATCH = 500;

    public static final class CloudException extends Exception {
        /** Vrai si l'échec vient du réseau (pas de connexion), faux si le serveur a refusé. */
        public final boolean reseau;
        public CloudException(String message) { this(message, false); }
        public CloudException(String message, boolean reseau) { super(message); this.reseau = reseau; }
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
            // Dernière chance d'envoyer les exports faits hors ligne : ils appartiennent à ce compte.
            try { envoyerExportsEnAttente(c, token(c)); } catch (Exception ignored) {}
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
            if (e.reseau) throw e;
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
                // Position (date de modification, clé) : reprend exactement là où le lot précédent s'est arrêté.
                // Les anciennes versions n'avaient pas de clé : on repart de "" à la même date, ce qui renvoie
                // aussi les lignes de même date jamais envoyées (rattrapage automatique).
                long sent = prefs(c).getLong("push_" + s.table, 0);
                String sentKey = prefs(c).getString("pushk_" + s.table, "");
                int keyIdx = 0;
                for (int k = 0; k < s.cols.length; k++) if (s.cols[k].equals(s.key)) keyIdx = k;
                while (true) {
                    List<Object[]> rows = st.changedSince(s, sent, sentKey, BATCH);
                    if (rows.isEmpty()) break;
                    JSONArray arr = new JSONArray();
                    for (Object[] row : rows) {
                        JSONObject o = new JSONObject();
                        o.put("user_id", userId);
                        for (int k = 0; k < s.cols.length; k++) o.put(s.cols[k], row[k] == null ? JSONObject.NULL : row[k]);
                        arr.put(o);
                    }
                    http("POST", "/rest/v1/" + s.table + "?on_conflict=user_id," + s.key, arr.toString(), tok,
                            "resolution=merge-duplicates,return=minimal");
                    pushed += rows.size();
                    Object[] last = rows.get(rows.size() - 1);
                    Object m = last[s.cols.length - 1];
                    sent = m instanceof Long ? (Long) m : sent;
                    sentKey = last[keyIdx] == null ? "" : last[keyIdx].toString();
                    prefs(c).edit().putLong("push_" + s.table, sent).putString("pushk_" + s.table, sentKey).apply();
                    if (rows.size() < BATCH) break;
                }
            }
            registerDevice(c, tok, userId);
            envoyerPlantages(c, tok, userId);
            try { envoyerExportsEnAttente(c, tok); rafraichirPortefeuille(c, tok); } catch (Exception ignored) {}
            envoyerIncidents(c, tok, userId);
        } catch (CloudException e) {
            saveError(c, e.getMessage());
            prefs(c).edit().putString("incident_attente", e.getMessage()).apply();
            throw e;
        } catch (Exception e) {
            saveError(c, e.getMessage());
            prefs(c).edit().putString("incident_attente", String.valueOf(e.getMessage())).apply();
            throw new CloudException("Sauvegarde interrompue : " + e.getMessage());
        }
        prefs(c).edit().putLong("last_sync", System.currentTimeMillis()).putString("last_error", "").apply();
        return pulled + " élément(s) reçu(s), " + pushed + " envoyé(s).";
    }

// ---------- Porte-monnaie et exports payants ----------

    private static String deviceId(Context c) {
        String device = prefs(c).getString("device_id", "");
        if (device.isEmpty()) {
            device = UUID.randomUUID().toString();
            prefs(c).edit().putString("device_id", device).apply();
        }
        return device;
    }

    /** Texte d'un champ JSON ; vide si absent ou null (Android renverrait « null »). */
    private static String str(JSONObject o, String k) {
        return o.isNull(k) ? "" : o.optString(k, "");
    }

    /** État du porte-monnaie tel que connu par ce téléphone (dernière réponse du serveur + exports hors ligne). */
    public static final class Portefeuille {
        public boolean connu;
        public long solde, enAttente, verifie;
        public int offertsRestants, prixPetit = Tarif.PETIT, prixGrand = Tarif.GRAND, seuil = Tarif.SEUIL, rechargeMin = 500;
        public int exportsHorsLigne;
        public boolean suspendu;
        public String numeroOm = "", numeroMomo = "", marchand = "";
        public JSONArray historique = new JSONArray();

        public Tarif.Decision decider(int nb) {
            return Tarif.decider(nb, solde, offertsRestants, prixPetit, prixGrand, seuil);
        }
    }

    public static Portefeuille portefeuille(Context ctx) {
        SharedPreferences p = prefs(ctx.getApplicationContext());
        Portefeuille w = new Portefeuille();
        String raw = p.getString("pf_json", "");
        if (!raw.isEmpty()) {
            try {
                JSONObject o = new JSONObject(raw);
                w.connu = true;
                w.solde = o.optLong("solde", 0);
                w.offertsRestants = o.optInt("offerts_restants", 0);
                w.suspendu = o.optBoolean("suspendu", false);
                w.prixPetit = o.optInt("prix_petit", Tarif.PETIT);
                w.prixGrand = o.optInt("prix_grand", Tarif.GRAND);
                w.seuil = o.optInt("seuil", Tarif.SEUIL);
                w.rechargeMin = o.optInt("recharge_min", 500);
                w.numeroOm = str(o, "numero_om");
                w.numeroMomo = str(o, "numero_momo");
                w.marchand = str(o, "nom_marchand");
                w.enAttente = o.optLong("en_attente", 0);
                JSONArray h = o.optJSONArray("historique");
                if (h != null) w.historique = h;
            } catch (Exception ignored) {
                w.connu = false;
            }
        }
        w.verifie = p.getLong("pf_verifie", 0);
        // Exports faits hors ligne, pas encore transmis : déjà déduits ici.
        try {
            JSONArray q = new JSONArray(p.getString("exports_attente", "[]"));
            for (int i = 0; i < q.length(); i++) {
                JSONObject e = q.getJSONObject(i);
                w.exportsHorsLigne++;
                if (e.optBoolean("offert", false)) w.offertsRestants = Math.max(0, w.offertsRestants - 1);
                else w.solde -= e.optLong("prix", 0);
            }
        } catch (Exception ignored) {}
        return w;
    }

    private static void rafraichirPortefeuille(Context c, String tok) throws Exception {
        String r = http("POST", "/rest/v1/rpc/mon_portefeuille", "{}", tok, null).trim();
        if (!r.startsWith("{")) return;
        new JSONObject(r); // contrôle du format avant de mémoriser
        prefs(c).edit().putString("pf_json", r).putLong("pf_verifie", System.currentTimeMillis()).apply();
    }

    /** Met à jour le porte-monnaie (après une recharge validée, par exemple). */
    public static void rafraichirPortefeuille(Context ctx) throws CloudException {
        Context c = ctx.getApplicationContext();
        String tok = token(c);
        try {
            envoyerExportsEnAttente(c, tok);
            rafraichirPortefeuille(c, tok);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /** Résultat d'un débit d'export. */
    public static final class Debit {
        public int prix;
        public boolean offert, horsLigne;
        public long solde;
    }

    /**
     * Débite un export Excel avant d'écrire le fichier. En ligne, le serveur décide.
     * Sans réseau, l'export passe si le dernier solde connu suffit ; le débit est envoyé plus tard.
     */
    public static Debit payerExport(Context ctx, int nb, String periode) throws CloudException {
        Context c = ctx.getApplicationContext();
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try {
            String tok = token(c);
            JSONObject body = new JSONObject();
            body.put("p_id", id);
            body.put("p_nb", nb);
            body.put("p_periode", periode);
            body.put("p_device", deviceId(c));
            body.put("p_date", now);
            body.put("p_hors_ligne", false);
            JSONObject r = new JSONObject(http("POST", "/rest/v1/rpc/payer_export", body.toString(), tok, null));
            Debit d = new Debit();
            d.prix = r.optInt("prix", 0);
            d.offert = r.optBoolean("offert", false);
            d.solde = r.optLong("solde", 0);
            memoriserSolde(c, d.solde, r.optInt("offerts_restants", 0));
            try { rafraichirPortefeuille(c, tok); } catch (Exception ignored) {}
            return d;
        } catch (CloudException e) {
            if (!e.reseau) throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
        // Hors ligne : décision sur le dernier état connu.
        Portefeuille w = portefeuille(c);
        if (!w.connu) throw new CloudException("Pas de réseau : connectez-vous une première fois à internet pour activer les exports.");
        if (w.suspendu) throw new CloudException("Compte suspendu : contactez le service client.");
        Tarif.Decision dec = w.decider(nb);
        if (!dec.possible) throw new CloudException("Pas de réseau, et le dernier solde connu (" + Tarif.fcfa(Math.max(0, w.solde))
                + ") ne suffit pas pour cet export (" + Tarif.fcfa(dec.prix) + "). Réessayez avec du réseau.");
        try {
            JSONArray q = new JSONArray(prefs(c).getString("exports_attente", "[]"));
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("nb", nb);
            e.put("periode", periode);
            e.put("date", now);
            e.put("prix", dec.prix);
            e.put("offert", dec.offert);
            q.put(e);
            prefs(c).edit().putString("exports_attente", q.toString()).apply();
        } catch (Exception ex) {
            throw new CloudException("Export impossible : " + ex.getMessage());
        }
        Debit d = new Debit();
        d.prix = dec.prix;
        d.offert = dec.offert;
        d.horsLigne = true;
        d.solde = dec.soldeApres;
        return d;
    }

    private static void memoriserSolde(Context c, long solde, int offerts) {
        try {
            String raw = prefs(c).getString("pf_json", "");
            JSONObject o = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            o.put("solde", solde);
            o.put("offerts_restants", offerts);
            prefs(c).edit().putString("pf_json", o.toString()).putLong("pf_verifie", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    /** Transmet les exports faits sans réseau ; chacun a un identifiant unique, il n'est jamais débité deux fois. */
    private static void envoyerExportsEnAttente(Context c, String tok) throws Exception {
        JSONArray q = new JSONArray(prefs(c).getString("exports_attente", "[]"));
        if (q.length() == 0) return;
        JSONArray reste = new JSONArray();
        for (int i = 0; i < q.length(); i++) {
            JSONObject e = q.getJSONObject(i);
            try {
                JSONObject body = new JSONObject();
                body.put("p_id", e.getString("id"));
                body.put("p_nb", e.optInt("nb", 0));
                body.put("p_periode", e.optString("periode", ""));
                body.put("p_device", deviceId(c));
                body.put("p_date", e.optLong("date", System.currentTimeMillis()));
                body.put("p_hors_ligne", true);
                http("POST", "/rest/v1/rpc/payer_export", body.toString(), tok, null);
            } catch (CloudException ex) {
                if (ex.reseau) { reste.put(e); for (int j = i + 1; j < q.length(); j++) reste.put(q.getJSONObject(j)); break; }
                // Refus définitif du serveur (format, identifiant) : on n'insiste pas.
            }
        }
        prefs(c).edit().putString("exports_attente", reste.toString()).apply();
    }

    /** Déclare une recharge OM ou MoMo par son ID de transaction ; renvoie un message pour l'utilisateur. */
    public static String declarerRecharge(Context ctx, String operateur, String reference, long montant) throws CloudException {
        Context c = ctx.getApplicationContext();
        String tok = token(c);
        try {
            JSONObject body = new JSONObject();
            body.put("p_operateur", operateur);
            body.put("p_reference", reference);
            body.put("p_montant", montant);
            JSONObject r = new JSONObject(http("POST", "/rest/v1/rpc/declarer_recharge", body.toString(), tok, null));
            try { rafraichirPortefeuille(c, tok); } catch (Exception ignored) {}
            return "Recharge de " + Tarif.fcfa(r.optLong("montant", montant)) + " déclarée. Elle sera ajoutée à votre solde dès sa validation.";
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    
    // ---------- Incidents (sans contenu personnel) ----------

    private static void envoyerIncidents(Context c, String tok, String userId) {
        try {
            String attente = prefs(c).getString("incident_attente", "");
            if (!attente.isEmpty()) {
                incident(c, tok, userId, "SAUVEGARDE_ECHEC", attente);
                prefs(c).edit().putString("incident_attente", "").apply();
            }
            long inconnus = Store.prefLong(c, "nonReconnus", 0), signales = prefs(c).getLong("nonReconnusSignales", 0);
            if (inconnus > signales) {
                incident(c, tok, userId, "SMS_NON_RECONNUS", inconnus + " SMS mobile money non reconnus sur ce téléphone (contenu non transmis).");
                prefs(c).edit().putLong("nonReconnusSignales", inconnus).apply();
            }
        } catch (Exception ignored) {
            // Un incident non transmis le sera à la prochaine sauvegarde.
        }
    }

    /** Envoie au support la dernière erreur mémorisée (pile d'appels uniquement, aucune donnée de caisse). */
    public static void envoyerPlantages(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!configured() || !loggedIn(c)) return;
        try { envoyerPlantages(c, token(c), prefs(c).getString("user_id", "")); } catch (Exception ignored) {}
    }

    private static void envoyerPlantages(Context c, String tok, String userId) {
        String trace = Crash.aEnvoyer(c);
        if (trace == null || userId.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("user_id", userId);
            o.put("device_id", deviceId(c));
            o.put("type", trace.startsWith("PLANTAGE") ? "PLANTAGE" : "ERREUR_AFFICHAGE");
            o.put("message", trace.length() > 4000 ? trace.substring(0, 4000) : trace);
            o.put("version_app", VERSION_APP);
            http("POST", "/rest/v1/incident", new JSONArray().put(o).toString(), tok, "return=minimal");
            Crash.marquerEnvoye(c);
        } catch (Exception ignored) {
            // Renvoyé à la prochaine sauvegarde.
        }
    }

    private static void incident(Context c, String tok, String userId, String type, String message) throws Exception {
        JSONObject o = new JSONObject();
        o.put("user_id", userId);
        o.put("device_id", deviceId(c));
        o.put("type", type);
        o.put("message", message == null ? "" : (message.length() > 500 ? message.substring(0, 500) : message));
        o.put("version_app", VERSION_APP);
        http("POST", "/rest/v1/incident", new JSONArray().put(o).toString(), tok, "return=minimal");
    }

    /** Fiche de l'appareil, pour le suivi du parc (tableau de bord superadmin). */
    private static void registerDevice(Context c, String tok, String userId) throws Exception {
        String device = deviceId(c);
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
            throw new CloudException("Pas de connexion internet ou serveur injoignable. Réessayez quand le réseau revient.", true);
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
            if ("P0001".equals(o.optString("code", ""))) return raw;
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
