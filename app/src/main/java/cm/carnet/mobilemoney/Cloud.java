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
import java.util.ArrayList;
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

    public static final String VERSION_APP = "0.14";
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
            // 0. Caisse de ce téléphone : créée automatiquement pour le premier téléphone du compte ;
            //    sinon l'utilisateur choisit (onglet Compte) et rien n'est envoyé d'ici là.
            String caisse = caisseId(c);
            if (caisse.isEmpty()) {
                Caisses cs = rafraichirCaisses(c, tok);
                if (cs.liste.isEmpty()) {
                    JSONObject r = creerCaisseRpc(c, tok, "Caisse principale");
                    assigner(c, r.getString("id"), r.optString("nom", "Caisse principale"));
                    caisse = caisseId(c);
                } else {
                    prefs(c).edit().putBoolean("caisse_a_choisir", true).apply();
                    return "choisissez d'abord la caisse de ce téléphone (onglet Compte)";
                }
            }
            migrerOrigine(c, tok, st);
            // Classements à envoyer : seulement ceux des opérations de ce téléphone (un compte utilisé sur plusieurs
            // téléphones avant la version 0.8 a pu recevoir ceux des autres).
            java.util.Set<String> mesCles = null;

            for (Store.Spec s : Store.SYNC) {
                String select = String.join(",", s.cols);
                boolean parCaisse = PAR_CAISSE.contains(s.table);
                String filtre = parCaisse ? "&caisse=eq." + caisse : "";

                // 1. Recevoir ce qui a été modifié ailleurs (restauration, reprise de caisse) : seulement cette caisse.
                long since = prefs(c).getLong("pull_" + s.table, 0), newest = since;
                for (int offset = 0; ; offset += PAGE) {
                    String q = "/rest/v1/" + s.table + "?select=" + select + filtre + "&maj=gt." + since + "&order=maj.asc&limit=" + PAGE + "&offset=" + offset;
                    JSONArray arr = new JSONArray(http("GET", q, null, tok, null));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        Object[] row = new Object[s.cols.length];
                        for (int k = 0; k < s.cols.length; k++) {
                            String col = s.cols[k];
                            if (!o.has(col) || o.isNull(col)) row[k] = null;
                            else row[k] = s.numeric[k] ? (Object) o.getLong(col) : o.getString(col);
                        }
                        // Journées, budgets, avoirs : clé « caisse|… » sur le serveur, « … » sur le téléphone.
                        if (CLE_PAR_CAISSE.contains(s.table) && row[0] instanceof String) {
                            String j = (String) row[0];
                            int bar = j.lastIndexOf('|');
                            row[0] = bar >= 0 ? j.substring(bar + 1) : j;
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
                    if ("classement".equals(s.table) && mesCles == null) {
                        mesCles = new java.util.HashSet<>();
                        Analyzer.Result r = Analyzer.analyse(st.all(), Store.config(c), st.lecture(c));
                        for (Txn t : r.tx) mesCles.add(Caisse.cle(t));
                    }
                    JSONArray arr = new JSONArray();
                    for (Object[] row : rows) {
                        if ("classement".equals(s.table) && !mesCles.contains(String.valueOf(row[0]))) continue;
                        JSONObject o = new JSONObject();
                        o.put("user_id", userId);
                        for (int k = 0; k < s.cols.length; k++) o.put(s.cols[k], row[k] == null ? JSONObject.NULL : row[k]);
                        if (parCaisse) o.put("caisse", caisse);
                        if (CLE_PAR_CAISSE.contains(s.table)) o.put(s.key, caisse + "|" + row[0]);
                        arr.put(o);
                    }
                    if (arr.length() > 0) {
                        http("POST", "/rest/v1/" + s.table + "?on_conflict=user_id," + s.key, arr.toString(), tok,
                                "resolution=merge-duplicates,return=minimal");
                    }
                    pushed += arr.length();
                    Object[] last = rows.get(rows.size() - 1);
                    Object m = last[s.cols.length - 1];
                    sent = m instanceof Long ? (Long) m : sent;
                    sentKey = last[keyIdx] == null ? "" : last[keyIdx].toString();
                    prefs(c).edit().putLong("push_" + s.table, sent).putString("pushk_" + s.table, sentKey).apply();
                    if (rows.size() < BATCH) break;
                }
            }
            registerDevice(c, tok, userId);
            try { recharges(c, tok, st, null); } catch (Exception ignored) {}
            try { envoyerResumes(c, tok, userId, caisse, st); } catch (Exception e) { saveErrorResume(c, e); }
            try { rafraichirCaisses(c, tok); } catch (Exception ignored) {}
            envoyerPlantages(c, tok, userId);
            try { envoyerExportsEnAttente(c, tok); rafraichirPortefeuille(c, tok); } catch (Exception ignored) {}
            try { rafraichirModules(c, tok); rappelModules(c); } catch (Exception ignored) {}
            try { alertes(c, tok, st, false); } catch (Exception ignored) {}
            try { rafraichirReglesIa(c, tok); lireInconnusIa(c, tok, st); } catch (Exception ignored) {}
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

// ---------- Caisses (un compte, plusieurs téléphones) ----------

    /** Tables dont les lignes appartiennent à une caisse ; les règles par contact restent communes au compte. */
    private static final java.util.Set<String> PAR_CAISSE = new java.util.HashSet<>(java.util.Arrays.asList("sms", "classement", "saisie", "jour", "budget", "avoir", "suivi"));
    /** Tables dont la clé locale (« AAAA-MM-JJ », poste, identifiant) devient « caisse|clé » sur le serveur. */
    private static final java.util.Set<String> CLE_PAR_CAISSE = new java.util.HashSet<>(java.util.Arrays.asList("jour", "budget", "avoir"));

    public static String caisseId(Context ctx) { return prefs(ctx.getApplicationContext()).getString("caisse_id", ""); }

    public static String caisseNom(Context ctx) { return prefs(ctx.getApplicationContext()).getString("caisse_nom", ""); }

    /** Vrai si le compte a déjà des caisses et que ce téléphone doit en choisir une (créer ou reprendre). */
    public static boolean caisseAChoisir(Context ctx) {
        Context c = ctx.getApplicationContext();
        return loggedIn(c) && caisseId(c).isEmpty() && prefs(c).getBoolean("caisse_a_choisir", false);
    }

    public static final class Caisse1 {
        public String id, nom, modele;
        public long activite;
    }

    public static final class Caisses {
        public boolean codeDefini;
        public final List<Caisse1> liste = new ArrayList<>();
    }

    /** Caisses du compte telles que connues à la dernière connexion. */
    public static Caisses caisses(Context ctx) {
        return lireCaisses(prefs(ctx.getApplicationContext()).getString("caisses_json", ""));
    }

    static Caisses lireCaisses(String raw) {
        Caisses cs = new Caisses();
        if (raw == null || raw.isEmpty()) return cs;
        try {
            JSONObject o = new JSONObject(raw);
            cs.codeDefini = o.optBoolean("code_defini", false);
            JSONArray a = o.optJSONArray("caisses");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                Caisse1 k = new Caisse1();
                k.id = str(x, "id");
                k.nom = str(x, "nom");
                k.modele = str(x, "modele");
                k.activite = x.optLong("activite", 0);
                cs.liste.add(k);
            }
        } catch (Exception ignored) {}
        return cs;
    }

    private static Caisses rafraichirCaisses(Context c, String tok) throws Exception {
        String r = http("POST", "/rest/v1/rpc/mes_caisses", "{}", tok, null).trim();
        if (r.startsWith("{")) {
            prefs(c).edit().putString("caisses_json", r).apply();
            // Le nom de la caisse de ce téléphone suit les changements faits ailleurs.
            for (Caisse1 k : lireCaisses(r).liste) if (k.id.equals(caisseId(c))) prefs(c).edit().putString("caisse_nom", k.nom).apply();
        }
        return lireCaisses(r);
    }

    /** Liste à jour des caisses du compte (écran Compte). */
    public static Caisses rafraichirCaisses(Context ctx) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            return rafraichirCaisses(c, token(c));
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    private static JSONObject creerCaisseRpc(Context c, String tok, String nom) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_nom", nom);
        body.put("p_device", deviceId(c));
        body.put("p_modele", Build.MANUFACTURER + " " + Build.MODEL);
        return new JSONObject(http("POST", "/rest/v1/rpc/creer_caisse", body.toString(), tok, null));
    }

    /**
     * Ce téléphone travaille désormais sur cette caisse : toutes ses données y seront renvoyées, et celles
     * de la caisse (si elle existait déjà) seront reçues à la prochaine sauvegarde.
     */
    private static void assigner(Context c, String id, String nom) {
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString("caisse_id", id).putString("caisse_nom", nom).putBoolean("caisse_a_choisir", false).putBoolean("origine_a_verifier", true);
        for (String t : PAR_CAISSE) e.remove("pull_" + t).remove("push_" + t).remove("pushk_" + t);
        e.remove("resume_envoyes");
        e.apply();
    }

    /** Crée une nouvelle caisse pour ce téléphone puis sauvegarde. */
    public static String creerCaisse(Context ctx, String nom) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject r = creerCaisseRpc(c, token(c), nom);
            assigner(c, r.getString("id"), r.optString("nom", nom));
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
        return sync(c);
    }

    /** Reprend la caisse d'un autre téléphone (remplacement, perte) ; demande le code patron s'il existe. */
    public static String reprendreCaisse(Context ctx, String id, String code) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            body.put("p_caisse", id);
            body.put("p_code", code == null ? "" : code);
            body.put("p_device", deviceId(c));
            body.put("p_modele", Build.MANUFACTURER + " " + Build.MODEL);
            JSONObject r = new JSONObject(http("POST", "/rest/v1/rpc/reprendre_caisse", body.toString(), token(c), null));
            if (!r.optBoolean("ok", false)) throw new CloudException(str(r, "erreur"));
            assigner(c, id, str(r, "nom"));
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
        return sync(c);
    }

    public static void renommerCaisse(Context ctx, String nom) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            body.put("p_caisse", caisseId(c));
            body.put("p_nom", nom);
            String tok = token(c);
            http("POST", "/rest/v1/rpc/renommer_caisse", body.toString(), tok, null);
            prefs(c).edit().putString("caisse_nom", nom.trim()).apply();
            try { rafraichirCaisses(c, tok); } catch (Exception ignored) {}
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /** Définit ou change le code patron ; renvoie null si c'est fait, sinon le message d'erreur. */
    public static String definirCodePatron(Context ctx, String ancien, String nouveau) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            body.put("p_ancien", ancien == null ? "" : ancien);
            body.put("p_nouveau", nouveau);
            String tok = token(c);
            JSONObject r = new JSONObject(http("POST", "/rest/v1/rpc/definir_code_patron", body.toString(), tok, null));
            try { rafraichirCaisses(c, tok); } catch (Exception ignored) {}
            return r.optBoolean("ok", false) ? null : str(r, "erreur");
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /** Vue « Toutes mes caisses » sur une période (jours AAAA-MM-JJ inclus). Erreur de code : CloudException. */
    public static JSONObject vueCaisses(Context ctx, String code, String debut, String fin) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            body.put("p_code", code == null ? "" : code);
            body.put("p_debut", debut);
            body.put("p_fin", fin);
            JSONObject r = new JSONObject(http("POST", "/rest/v1/rpc/vue_caisses", body.toString(), token(c), null));
            if (!r.optBoolean("ok", false)) throw new CloudException(str(r, "erreur"));
            return r;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /**
     * Une seule fois après le choix de la caisse : si d'autres téléphones ont utilisé ce compte, seuls les SMS
     * présents dans la messagerie de ce téléphone restent dans sa caisse ; les autres sont mis de côté (pas effacés).
     */
    private static void migrerOrigine(Context c, String tok, Store st) throws Exception {
        if (!prefs(c).getBoolean("origine_a_verifier", false)) return;
        JSONArray a = new JSONArray(http("GET", "/rest/v1/appareil?select=device_id", null, tok, null));
        String moi = deviceId(c);
        boolean autres = false;
        for (int i = 0; i < a.length(); i++) if (!moi.equals(a.getJSONObject(i).optString("device_id", ""))) autres = true;
        java.util.Set<String> boite = autres ? Boite.empreintes(c) : null;
        if (boite == null) st.toutIci();
        else prefs(c).edit().putInt("mis_de_cote", st.marquerOrigine(boite)).apply();
        prefs(c).edit().putBoolean("origine_a_verifier", false).apply();
    }

    /** Envoie les résumés journaliers qui ont changé depuis le dernier envoi. */
    private static void envoyerResumes(Context c, String tok, String userId, String caisse, Store st) throws Exception {
        String sig = st.signature() + Store.prefBool(c, "agent" + Caisse.OM, false) + Store.prefBool(c, "agent" + Caisse.MOMO, false);
        if (sig.equals(prefs(c).getString("resume_sig", ""))) return;
        SmsParser.Config cfg = Store.config(c);
        Caisse.definirPerso(st.categoriesPerso());
        Analyzer.Cache cache = st.lecture(c);
        Analyzer.Result r = Analyzer.analyse(st.all(), cfg, cache);
        List<Resumes.Jour> tous = Resumes.calculer(r, st.classements(), st.regles(), st.saisies(), st.jours(),
                Store.prefBool(c, "agent" + Caisse.OM, false), Store.prefBool(c, "agent" + Caisse.MOMO, false));
        java.util.Map<String, String> envoyes = new java.util.HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs(c).getString("resume_envoyes", "{}"));
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) envoyes.put(names.getString(i), o.getString(names.getString(i)));
        } catch (Exception ignored) {}
        List<Resumes.Jour> diff = Resumes.aEnvoyer(tous, envoyes);
        long now = System.currentTimeMillis();
        for (int from = 0; from < diff.size(); from += BATCH) {
            JSONArray arr = new JSONArray();
            for (Resumes.Jour j : diff.subList(from, Math.min(diff.size(), from + BATCH))) {
                JSONObject o = new JSONObject();
                o.put("user_id", userId);
                o.put("caisse", caisse);
                o.put("jour", j.jour);
                o.put("nb_operations", j.nbOperations);
                o.put("a_classer", j.aClasser);
                o.put("ventes", j.ventes);
                o.put("achats", j.achats);
                o.put("charges", j.charges);
                o.put("frais", j.frais);
                o.put("resultat", j.resultat);
                o.put("apports", j.apports);
                o.put("retraits", j.retraits);
                o.put("solde_om", j.soldeOm == null ? JSONObject.NULL : j.soldeOm);
                o.put("solde_momo", j.soldeMomo == null ? JSONObject.NULL : j.soldeMomo);
                o.put("especes_compte", j.especesCompte == null ? JSONObject.NULL : j.especesCompte);
                o.put("ecart_om", j.ecartOm == null ? JSONObject.NULL : j.ecartOm);
                o.put("ecart_momo", j.ecartMomo == null ? JSONObject.NULL : j.ecartMomo);
                o.put("ecart_especes", j.ecartEspeces == null ? JSONObject.NULL : j.ecartEspeces);
                o.put("tresorerie", j.tresorerie == null ? JSONObject.NULL : j.tresorerie);
                o.put("maj", now);
                arr.put(o);
            }
            http("POST", "/rest/v1/resume?on_conflict=user_id,caisse,jour", arr.toString(), tok, "resolution=merge-duplicates,return=minimal");
            for (Resumes.Jour j : diff.subList(from, Math.min(diff.size(), from + BATCH))) envoyes.put(j.jour, j.signature());
            prefs(c).edit().putString("resume_envoyes", new JSONObject(envoyes).toString()).apply();
        }
        prefs(c).edit().putString("resume_sig", sig).apply();
    }

    private static void saveErrorResume(Context c, Exception e) {
        prefs(c).edit().putString("incident_attente", "Résumés non envoyés : " + e.getMessage()).apply();
    }

    /** SMS mis de côté lors de la séparation des caisses (venus d'un autre téléphone du compte). */
    public static void recupererMisDeCote(Context ctx) {
        Context c = ctx.getApplicationContext();
        Store.get(c).recupererMisDeCote();
        prefs(c).edit().putInt("mis_de_cote", 0).remove("resume_sig").apply();
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
        public String numeroOm = "", numeroMomo = "", marchand = "", ussdOm = "#150#", ussdMomo = "*126#";
        /** Ce compte est le superadmin : son téléphone transmet les transferts reçus pour la validation automatique. */
        public boolean admin;
        /** Export Excel inclus avec l'application Finances & budgets (version 0.11). */
        public boolean exportsInclus;
        public JSONArray historique = new JSONArray();

        public Tarif.Decision decider(int nb) {
            return Tarif.decider(nb, solde, offertsRestants, prixPetit, prixGrand, seuil, exportsInclus);
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
                if (!str(o, "ussd_om").isEmpty()) w.ussdOm = str(o, "ussd_om");
                if (!str(o, "ussd_momo").isEmpty()) w.ussdMomo = str(o, "ussd_momo");
                w.admin = o.optBoolean("admin", false);
                w.exportsInclus = o.optBoolean("exports_inclus", false);
                w.enAttente = o.optLong("en_attente", 0);
                JSONArray h = o.optJSONArray("historique");
                if (h != null) w.historique = h;
            } catch (Exception ignored) {
                w.connu = false;
            }
        }
        w.verifie = p.getLong("pf_verifie", 0);
        // Hors ligne : l'état des applications en mémoire suffit (Finances & budgets active = exports inclus).
        if (modules(ctx).actif(Modules.FINANCES, System.currentTimeMillis())) w.exportsInclus = true;
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
        public boolean offert, horsLigne, inclus;
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
            d.inclus = r.optBoolean("inclus", false);
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
            String m = Tarif.fcfa(r.optLong("montant", montant));
            if ("valide".equals(r.optString("statut", ""))) return "Recharge de " + m + " validée : votre porte-monnaie est crédité.";
            return "Recharge de " + m + " déclarée (ID " + reference + "). Elle est ajoutée à votre solde dès que le paiement est reçu.";
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    
    // ---------- Applications (0.11) ----------

    /** État des applications, lu au dernier contact avec le serveur (vide si jamais lu). */
    public static Modules.Etat modules(Context ctx) {
        Modules.Etat e = new Modules.Etat();
        String raw = prefs(ctx.getApplicationContext()).getString("mod_json", "");
        if (raw.isEmpty()) return e;
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray cat = o.optJSONArray("catalogue");
            if (cat != null) for (int i = 0; i < cat.length(); i++) {
                JSONObject m = cat.getJSONObject(i);
                Modules.Module x = new Modules.Module();
                x.code = m.optString("code", "");
                x.nom = m.optString("nom", x.code);
                x.description = m.optString("description", "");
                x.prix = m.optLong("prix", 0);
                x.prixCompte = m.isNull("prix_compte") ? x.prix : m.optLong("prix_compte", x.prix);
                x.prixNormal = m.isNull("prix_normal") ? x.prixCompte : m.optLong("prix_normal", x.prixCompte);
                x.promo = m.isNull("promo") ? null : m.optString("promo", "");
                if (x.promo != null && x.promo.isEmpty()) x.promo = null;
                x.prixAnnuel = m.isNull("prix_annuel") ? 0 : m.optLong("prix_annuel", 0);
                x.prixAnnuelCompte = m.isNull("prix_annuel_compte") ? x.prixAnnuel : m.optLong("prix_annuel_compte", x.prixAnnuel);
                x.parTelephone = m.optBoolean("par_telephone", false);
                x.estPack = m.optBoolean("est_pack", false);
                x.telephones = m.optInt("telephones", 1);
                JSONArray ct = m.optJSONArray("contenu");
                if (ct != null) for (int j = 0; j < ct.length(); j++) x.contenu.add(ct.getString(j));
                e.catalogue.add(x);
            }
            JSONArray ab = o.optJSONArray("abonnements");
            if (ab != null) for (int i = 0; i < ab.length(); i++) {
                JSONObject a = ab.getJSONObject(i);
                Modules.Abonnement x = new Modules.Abonnement();
                x.code = a.optString("code", "");
                x.fin = a.optLong("fin", 0);
                x.renouveler = a.optBoolean("renouveler", false);
                x.essai = a.optBoolean("essai", false);
                x.essaiUtilise = a.optBoolean("essai_utilise", false);
                x.offert = a.optBoolean("offert", false);
                x.periode = a.isNull("periode") ? "mois" : a.optString("periode", "mois");
                e.abonnements.add(x);
            }
            JSONArray pr = o.optJSONArray("promos");
            if (pr != null) for (int i = 0; i < pr.length(); i++) {
                JSONObject a = pr.getJSONObject(i);
                Modules.Promo x = new Modules.Promo();
                x.nom = a.optString("nom", "");
                x.description = a.isNull("description") ? "" : a.optString("description", "");
                x.type = a.optString("type", "");
                x.valeur = a.optLong("valeur", 0);
                x.seuil = a.optLong("seuil", 0);
                x.fin = a.isNull("fin") ? 0 : a.optLong("fin", 0);
                x.duree = a.optInt("duree", 1);
                x.restants = a.isNull("restants") ? -1 : a.optInt("restants", -1);
                JSONArray mo = a.optJSONArray("modules");
                if (mo != null) for (int j = 0; j < mo.length(); j++) x.modules.add(mo.getString(j));
                e.promos.add(x);
            }
            e.solde = o.optLong("solde", 0);
            e.essaiJours = o.optInt("essai_jours", 14);
            e.caisses = o.optInt("caisses", 0);
            e.decalage = o.optLong("decalage", 0);
            e.connu = true;
        } catch (Exception ignored) {
            return new Modules.Etat();
        }
        return e;
    }

    private static void rafraichirModules(Context c, String tok) throws Exception {
        String r = http("POST", "/rest/v1/rpc/mes_modules", "{}", tok, null).trim();
        if (!r.startsWith("{")) return;
        JSONObject o = new JSONObject(r);
        long serveur = o.optLong("serveur", 0);
        // Décalage limité à ±2 jours : protège contre une horloge reculée pour prolonger un essai, sans pénaliser un fuseau.
        o.put("decalage", serveur > 0 ? Math.max(-2L * 86400000, Math.min(2L * 86400000, serveur - System.currentTimeMillis())) : 0);
        prefs(c).edit().putString("mod_json", o.toString()).putLong("mod_verifie", System.currentTimeMillis()).apply();
    }

    public static void rafraichirModules(Context ctx) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            rafraichirModules(c, token(c));
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /** Active une application ou un pack (essai la première fois, sinon un mois payé). Renvoie le message à afficher. */
    public static String activerModule(Context ctx, String code) throws CloudException {
        return activerModule(ctx, code, "mois");
    }

    /** periode "an" : formule annuelle payée tout de suite (pas d'essai). */
    public static String activerModule(Context ctx, String code, String periode) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            String tok = token(c);
            JSONObject body = new JSONObject();
            body.put("p_code", code);
            body.put("p_periode", "an".equals(periode) ? "an" : "mois");
            JSONObject r = new JSONObject(http("POST", "/rest/v1/rpc/activer_module", body.toString(), tok, null));
            try { rafraichirModules(c, tok); rafraichirPortefeuille(c, tok); } catch (Exception ignored) {}
            Modules.Module m = modules(c).module(code);
            String nom = m != null ? m.nom : code;
            String fin = r.optLong("fin", 0) > 0 ? Modules.date(r.optLong("fin", 0)) : "";
            switch (r.optString("nature", "")) {
                case "essai": return nom + " installée : essai gratuit jusqu'au " + fin + ".";
                case "achat": return nom + ("an".equals(r.optString("periode", "")) ? " : formule annuelle" : " activée") + " jusqu'au " + fin + " (" + Tarif.fcfa(r.optLong("prix", 0)) + " débités).";
                default: return nom + " : renouvellement automatique réactivé.";
            }
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    public static void arreterModule(Context ctx, String code) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            String tok = token(c);
            JSONObject body = new JSONObject();
            body.put("p_code", code);
            http("POST", "/rest/v1/rpc/arreter_module", body.toString(), tok, null);
            rafraichirModules(c, tok);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /** Code promo saisi par l'utilisateur ; renvoie le message du serveur. */
    public static String utiliserCode(Context ctx, String code) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            String tok = token(c);
            JSONObject body = new JSONObject();
            body.put("p_code", code.trim().toUpperCase(java.util.Locale.ROOT));
            JSONObject r = new JSONObject(http("POST", "/rest/v1/rpc/utiliser_code", body.toString(), tok, null));
            try { rafraichirModules(c, tok); } catch (Exception ignored) {}
            return r.optString("message", "Code enregistré.");
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /** Notification (une fois par jour au plus) quand le solde ne couvre pas les renouvellements des 3 prochains jours. */
    private static void rappelModules(Context c) {
        long now = System.currentTimeMillis();
        String msg = modules(c).rappel(now);
        if (msg == null) return;
        String jour = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now));
        if (jour.equals(prefs(c).getString("rappel_modules", ""))) return;
        prefs(c).edit().putString("rappel_modules", jour).apply();
        SmsReceiver.notify(c, "Applications", msg);
    }

    // ---------- Agent IA (0.13) ----------

    private static JSONObject appelIa(Context c, String tok, JSONObject body) throws Exception {
        String r = http("POST", "/functions/v1/ia", body.toString(), tok, null).trim();
        return r.startsWith("{") ? new JSONObject(r) : new JSONObject();
    }

    /** Règles de lecture de l'IA (validées pour tous + proposées par ce compte), gardées pour la lecture hors ligne. */
    private static void rafraichirReglesIa(Context c, String tok) throws Exception {
        String r = http("POST", "/rest/v1/rpc/regles_ia", "{}", tok, null).trim();
        if (r.startsWith("[")) Store.savePref(c, "regles_ia", r);
    }

    /** Ajoute une règle reçue à la liste locale (sans attendre la prochaine synchronisation). */
    private static void ajouterRegleLocale(Context c, JSONObject regle) throws Exception {
        JSONArray a = new JSONArray(Store.pref(c, "regles_ia", "[]"));
        String id = regle.optString("id", "");
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).optString("id", ""))) return;
        a.put(regle);
        Store.savePref(c, "regles_ia", a.toString());
    }

    /**
     * Fait lire par l'IA un SMS inconnu (texte masqué) ; la règle reçue n'est gardée que si elle lit bien le vrai SMS.
     * Renvoie le message à afficher.
     */
    public static String lireSmsIa(Context ctx, Txn u) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            return lireSmsIa(c, token(c), u);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Lecture par l'IA impossible : " + e.getMessage());
        }
    }

    private static String lireSmsIa(Context c, String tok, Txn u) throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", "lire_sms");
        body.put("operateur", u.op == null ? "" : u.op);
        body.put("texte", ReglesIa.masquer(u.raw));
        JSONObject r = appelIa(c, tok, body);
        marquerEssaye(c, u);
        if (!r.optBoolean("comprend", false)) return "L'IA n'a pas trouvé de mouvement d'argent dans ce SMS : " + r.optString("explication", "");
        JSONObject regle = r.optJSONObject("regle");
        if (regle == null) return "Réponse de l'IA incomplète.";
        ReglesIa.Regle rg = ReglesIa.regle(regle.optString("id", ""), regle.isNull("operateur") ? null : regle.optString("operateur", ""),
                regle.optString("signature", ""), regle.optString("montant", ""), regle.optString("solde", ""), regle.optString("frais", ""),
                regle.optInt("sens", 0), regle.optString("libelle", ""), false);
        Txn essai = new Txn();
        java.util.List<ReglesIa.Regle> une = new java.util.ArrayList<>();
        une.add(rg);
        if (!ReglesIa.appliquer(une, u.op, SmsParser.norm(u.raw), essai)) return "La règle proposée par l'IA ne lit pas ce SMS : il reste à vérifier.";
        ajouterRegleLocale(c, regle);
        return "SMS compris par l'IA : « " + essai.typeLabel() + " » " + (essai.sens > 0 ? "+" : essai.sens < 0 ? "−" : "")
                + SmsParser.fmt(essai.montant > 0 ? essai.montant : essai.solde == null ? 0 : essai.solde) + " FCFA. Les SMS semblables seront lus seuls.";
    }

    private static void marquerEssaye(Context c, Txn u) {
        String h = SmsParser.hashOf(u.sender, u.raw);
        String deja = Store.pref(c, "sms_ia_essayes", "");
        if (!deja.contains(h)) Store.savePref(c, "sms_ia_essayes", (deja.length() > 4000 ? deja.substring(deja.length() - 3000) : deja) + h + ",");
    }

    /** À chaque synchronisation : jusqu'à 3 SMS inconnus récents (30 jours) jamais essayés sont lus par l'IA. */
    private static void lireInconnusIa(Context c, String tok, Store st) throws Exception {
        Analyzer.Result r = Analyzer.analyse(st.all(), Store.config(c), st.lecture(c));
        String deja = Store.pref(c, "sms_ia_essayes", "");
        long depuis = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        int n = 0;
        for (Txn u : r.unknown) {
            if (n >= 3) break;
            if (u.kind != Txn.UNKNOWN || u.smsDate < depuis || u.raw == null) continue;
            if (deja.contains(SmsParser.hashOf(u.sender, u.raw))) continue;
            try { lireSmsIa(c, tok, u); } catch (CloudException e) { if (e.reseau) throw e; }
            n++;
        }
    }

    /** Propositions de l'IA pour des opérations sans indice local : cle → [catégorie, confiance]. */
    public static java.util.Map<String, Classeur.Proposition> classerIa(Context ctx, java.util.List<Txn> ops) throws CloudException {
        Context c = ctx.getApplicationContext();
        java.util.Map<String, Classeur.Proposition> out = new java.util.HashMap<>();
        try {
            JSONObject body = new JSONObject();
            body.put("action", "classer");
            JSONArray a = new JSONArray();
            java.text.SimpleDateFormat jf = new java.text.SimpleDateFormat("EEEE HH'h'", java.util.Locale.FRANCE);
            for (int i = 0; i < ops.size(); i++) {
                Txn t = ops.get(i);
                JSONObject o = new JSONObject();
                o.put("i", i);
                o.put("operateur", Txn.opName(t.op));
                o.put("type", t.typeLabel());
                o.put("sens", t.sens);
                o.put("montant", t.montant / 100);
                o.put("quand", jf.format(new java.util.Date(t.date)));
                if (t.motif != null) o.put("motif", ReglesIa.masquer(t.motif));
                a.put(o);
            }
            body.put("operations", a);
            JSONArray cats = new JSONArray();
            for (int sens : new int[] {1, -1})
                for (String code : Caisse.choix(sens)) {
                    if (Caisse.AGENT.equals(code)) continue;
                    JSONObject k = new JSONObject();
                    k.put("code", code);
                    k.put("nom", Caisse.categorieNom(code));
                    k.put("sens", sens);
                    cats.put(k);
                }
            body.put("categories", cats);
            JSONObject r = appelIa(c, token(c), body);
            JSONArray p = r.optJSONArray("propositions");
            if (p != null) for (int i = 0; i < p.length(); i++) {
                JSONObject x = p.getJSONObject(i);
                int k = x.optInt("i", -1);
                if (k < 0 || k >= ops.size()) continue;
                Txn t = ops.get(k);
                String cat = x.optString("categorie", "");
                if (!Caisse.compatible(cat, t.sens)) continue;
                Classeur.Proposition pr = new Classeur.Proposition();
                pr.cle = Caisse.cle(t);
                pr.categorie = cat;
                pr.confiance = Math.max(0, Math.min(100, x.optInt("confiance", 0)));
                pr.source = "ia";
                if (pr.confiance >= 40) out.put(pr.cle, pr);
            }
            return out;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Classement par l'IA impossible : " + e.getMessage());
        }
    }

    /** Pose une question à l'assistant ; renvoie [réponse, questions utilisées, quota]. */
    public static String[] questionIa(Context ctx, String question, String contexte, JSONArray historique) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            body.put("action", "question");
            body.put("question", question);
            body.put("contexte", contexte);
            body.put("historique", historique);
            JSONObject r = appelIa(c, token(c), body);
            return new String[] {r.optString("reponse", ""), String.valueOf(r.optInt("questions", 0)), String.valueOf(r.optInt("quota", 300))};
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("L'assistant ne répond pas : " + e.getMessage());
        }
    }

    // ---------- Alertes WhatsApp (0.12) ----------

    /** Réglages, destinataires et derniers envois, lus au dernier contact (JSON de mes_alertes, ou null). */
    public static JSONObject alertesEtat(Context ctx) {
        try {
            String raw = prefs(ctx.getApplicationContext()).getString("alertes_json", "");
            return raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static Alertes.Reglages reglagesAlertes(Context ctx) {
        Alertes.Reglages g = new Alertes.Reglages();
        JSONObject o = alertesEtat(ctx);
        JSONObject p = o == null ? null : o.optJSONObject("prefs");
        if (p == null) return g;
        g.bilan = p.optBoolean("bilan", true);
        g.bilanHeure = p.optInt("bilan_heure", 20);
        g.ecart = p.optBoolean("ecart", true);
        g.chaine = p.optBoolean("chaine", true);
        g.solde = p.optBoolean("solde", true);
        g.dettes = p.optBoolean("dettes", true);
        g.seuilOm = p.optLong("seuil_om", 5000);
        g.seuilMomo = p.optLong("seuil_momo", 5000);
        return g;
    }

    private static JSONObject rpcObjet(String fonction, JSONObject body, String tok) throws Exception {
        String r = http("POST", "/rest/v1/rpc/" + fonction, body == null ? "{}" : body.toString(), tok, null).trim();
        return r.startsWith("{") ? new JSONObject(r) : new JSONObject();
    }

    public static void rafraichirAlertes(Context ctx) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject o = rpcObjet("mes_alertes", null, token(c));
            prefs(c).edit().putString("alertes_json", o.toString()).apply();
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    /**
     * Calcule les alertes de cette caisse et les dépose sur le serveur, puis demande l'envoi des alertes en attente.
     * Renvoie le nombre d'alertes déposées. forcerBilan : envoi immédiat du bilan du jour (bouton d'essai).
     */
    private static int alertes(Context c, String tok, Store st, boolean forcerBilan) throws Exception {
        if (!modules(c).actif(Modules.ALERTES, System.currentTimeMillis())) return 0;
        String caisse = caisseId(c);
        if (caisse.isEmpty()) return 0;
        JSONObject etat = rpcObjet("mes_alertes", null, tok);
        prefs(c).edit().putString("alertes_json", etat.toString()).apply();
        JSONArray dest = etat.optJSONArray("destinataires");
        boolean unConfirme = false;
        if (dest != null) for (int i = 0; i < dest.length(); i++) if (dest.getJSONObject(i).optBoolean("confirme", false)) unConfirme = true;
        if (!unConfirme) return 0;

        long now = System.currentTimeMillis();
        Caisse.definirPerso(st.categoriesPerso());
        Analyzer.Result r = Analyzer.analyse(st.all(), Store.config(c), st.lecture(c));
        long debut = Caisse.debutJour(Caisse.jourDe(now));
        boolean agOm = Store.prefBool(c, "agent" + Caisse.OM, false), agMomo = Store.prefBool(c, "agent" + Caisse.MOMO, false);
        Caisse.Bilan b = Caisse.bilan(r, st.classements(), st.regles(), st.saisies(), st.jours(), debut, Alertes.finJour(now), agOm, agMomo);
        Dettes.Bilan d = modules(c).actif(Modules.DETTES, now) ? Dettes.calculer(st.engagements(), now) : null;
        String nom = caisseNom(c).isEmpty() ? "Caisse" : caisseNom(c);
        List<Alertes.Evenement> ev = Alertes.calculer(caisse, nom, r, b, d, reglagesAlertes(c), now, forcerBilan);

        // Déjà déposées depuis ce téléphone (évite des appels inutiles ; le serveur ignore de toute façon les doublons).
        JSONObject deja = new JSONObject(prefs(c).getString("alertes_deposees", "{}"));
        int n = 0;
        for (Alertes.Evenement e : ev) {
            if (deja.has(e.cle)) continue;
            JSONObject body = new JSONObject();
            body.put("p_type", e.type);
            body.put("p_cle", e.cle);
            JSONArray p = new JSONArray();
            for (String x : e.params) p.put(x);
            body.put("p_params", p);
            JSONObject res = rpcObjet("signaler_alerte", body, tok);
            n += res.optInt("deposees", 0);
            deja.put(e.cle, now);
        }
        // On oublie les clés de plus de 3 jours.
        JSONObject garde = new JSONObject();
        JSONArray names = deja.names();
        if (names != null) for (int i = 0; i < names.length(); i++) {
            String k = names.getString(i);
            if (now - deja.optLong(k, 0) < 3L * 24 * 3600 * 1000) garde.put(k, deja.optLong(k, 0));
        }
        prefs(c).edit().putString("alertes_deposees", garde.toString()).apply();
        envoyerWhatsapp(c, tok);
        return n;
    }

    /** Demande à la fonction « whatsapp » d'envoyer les alertes en attente ; garde la réponse (compte Meta prêt ?). */
    private static void envoyerWhatsapp(Context c, String tok) {
        try {
            String r = http("POST", "/functions/v1/whatsapp", "{\"action\":\"envoyer\"}", tok, null).trim();
            if (r.startsWith("{")) prefs(c).edit().putBoolean("whatsapp_configure", new JSONObject(r).optBoolean("configure", false)).apply();
        } catch (Exception ignored) {}
    }

    public static boolean whatsappConfigure(Context ctx) {
        return prefs(ctx.getApplicationContext()).getBoolean("whatsapp_configure", false);
    }

    /** Envoie tout de suite le bilan du jour (essai) ; renvoie le message à afficher. */
    public static String envoyerBilanMaintenant(Context ctx) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            int n = alertes(c, token(c), Store.get(c), true);
            if (n == 0) return "Aucun message déposé : vérifiez qu'au moins un numéro est confirmé et que l'application est active.";
            return whatsappConfigure(c) ? n + " message(s) envoyé(s) sur WhatsApp."
                    : n + " message(s) en attente : l'envoi WhatsApp sera actif dès que le service client aura terminé sa configuration.";
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Envoi impossible : " + e.getMessage());
        }
    }

    /** Demande un code de confirmation par WhatsApp pour un nouveau destinataire. */
    public static void demanderCode(Context ctx, String numero, String nom) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            body.put("action", "code");
            body.put("numero", numero);
            body.put("nom", nom);
            http("POST", "/functions/v1/whatsapp", body.toString(), token(c), null);
            rafraichirAlertes(c);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    public static void confirmerDestinataire(Context ctx, String numero, String code) throws CloudException {
        appelAlertes(ctx, "confirmer_destinataire", "p_numero", numero, "p_code", code);
    }

    public static void retirerDestinataire(Context ctx, String numero) throws CloudException {
        appelAlertes(ctx, "retirer_destinataire", "p_numero", numero);
    }

    public static void reglerAlertes(Context ctx, JSONObject p) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            body.put("p", p);
            http("POST", "/rest/v1/rpc/regler_alertes", body.toString(), token(c), null);
            rafraichirAlertes(c);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    private static void appelAlertes(Context ctx, String fonction, String... kv) throws CloudException {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject body = new JSONObject();
            for (int i = 0; i + 1 < kv.length; i += 2) body.put(kv[i], kv[i + 1]);
            http("POST", "/rest/v1/rpc/" + fonction, body.toString(), token(c), null);
            rafraichirAlertes(c);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("Réponse inattendue du serveur : " + e.getMessage());
        }
    }

    // ---------- Recharge automatique (0.10) ----------

    /** Recharge payée en attente de son SMS (null si aucune). */
    public static Recharges.Attente rechargeAttente(Context ctx) {
        return Recharges.Attente.decode(Store.pref(ctx.getApplicationContext(), "recharge_attente", ""));
    }

    public static void setRechargeAttente(Context ctx, Recharges.Attente a) {
        Store.savePref(ctx.getApplicationContext(), "recharge_attente", a == null ? "" : a.encode());
    }

    private static java.util.Set<String> declarees(Context c) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String x : Store.pref(c, "recharges_declarees", "").split(",")) if (!x.isEmpty()) s.add(x);
        return s;
    }

    public static void marquerDeclaree(Context ctx, String ref) {
        Context c = ctx.getApplicationContext();
        String deja = Store.pref(c, "recharges_declarees", "");
        Store.savePref(c, "recharges_declarees", (deja.isEmpty() ? "" : deja + ",") + ref.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Déclare seule la recharge en attente dès que le SMS de l'opérateur est lu ; sur le téléphone marchand,
     * transmet aussi les transferts reçus. Renvoie le message pour l'utilisateur, ou null s'il ne s'est rien passé.
     */
    public static String recharges(Context ctx, Analyzer.Result r) {
        Context c = ctx.getApplicationContext();
        if (!configured() || !loggedIn(c)) return null;
        try {
            return recharges(c, token(c), Store.get(c), r);
        } catch (Exception e) {
            return null;
        }
    }

    private static String recharges(Context c, String tok, Store st, Analyzer.Result r) throws Exception {
        Recharges.Attente a = rechargeAttente(c);
        Portefeuille w = portefeuille(c);
        if (a != null && a.expiree(System.currentTimeMillis())) { setRechargeAttente(c, null); a = null; }
        if (a == null && !w.admin) return null;
        if (r == null) r = Analyzer.analyse(st.all(), Store.config(c), st.lecture(c));
        String msg = null;
        if (a != null) {
            Txn t = Recharges.trouverEnvoi(r.tx, a, declarees(c));
            if (t != null) {
                try {
                    msg = declarerRecharge(c, a.op, t.txnId, a.montant);
                    marquerDeclaree(c, t.txnId);
                    setRechargeAttente(c, null);
                } catch (CloudException e) {
                    if (e.reseau) throw e;
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if (m.contains("déjà été déclaré")) { marquerDeclaree(c, t.txnId); setRechargeAttente(c, null); }
                    msg = "Recharge non déclarée : " + m;
                }
                Store.savePref(c, "recharge_message", msg);
                SmsReceiver.notify(c, "Porte-monnaie", msg);
            }
        }
        if (w.admin) {
            long depuis = prefs(c).getLong("recus_depuis", System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
            List<Recharges.Recu> recus = Recharges.recusMarchand(r.tx, depuis, Math.max(1, w.rechargeMin));
            if (!recus.isEmpty()) {
                JSONArray arr = new JSONArray();
                long max = depuis;
                for (Recharges.Recu x : recus) {
                    JSONObject o = new JSONObject();
                    o.put("operateur", x.op);
                    o.put("reference", x.reference);
                    o.put("montant", x.montant);
                    o.put("numero", x.numero == null ? "" : x.numero);
                    o.put("quand", x.date);
                    arr.put(o);
                    max = Math.max(max, x.date);
                }
                JSONObject body = new JSONObject();
                body.put("p_recus", arr);
                http("POST", "/rest/v1/rpc/admin_signaler_recus", body.toString(), tok, null);
                // Une minute de recouvrement : un SMS arrivé dans la même minute n'est jamais oublié (doublons ignorés).
                prefs(c).edit().putLong("recus_depuis", max - 60_000L).apply();
            }
        }
        return msg;
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
            // Fonctions Edge de l'application : message déjà rédigé pour l'utilisateur.
            if (o.has("erreur") && !o.isNull("erreur")) return o.getString("erreur");
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
