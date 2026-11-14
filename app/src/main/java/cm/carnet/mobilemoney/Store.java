package cm.carnet.mobilemoney;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copie locale des SMS OM et MoMo. Elle garde l'historique même si l'utilisateur
 * supprime ensuite les SMS de sa messagerie. Les autres SMS ne sont jamais enregistrés.
 */
public final class Store extends SQLiteOpenHelper {
    private static Store instance;

    public static synchronized Store get(Context ctx) {
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    private Store(Context ctx) {
        super(ctx, "carnet.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sms (hash TEXT PRIMARY KEY, address TEXT, body TEXT, date INTEGER, sub TEXT)");
        createCaisse(db);
        createClassement(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) createCaisse(db);
        else if (oldV == 2) db.execSQL("ALTER TABLE saisie ADD COLUMN categorie TEXT");
        if (oldV < 3) createClassement(db);
    }

    /** Version 3 : catégorie et motif déclarés pour chaque opération SMS, règles par contact. */
    private static void createClassement(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS classement (cle TEXT PRIMARY KEY, categorie TEXT, motif TEXT, maj INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS regle (numero TEXT PRIMARY KEY, categorie TEXT, maj INTEGER)");
    }

    /** Version 2 : saisies du caissier et journées de caisse espèces. */
    private static void createCaisse(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS saisie (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, mode TEXT, sens INTEGER,"
                + " montant INTEGER, libelle TEXT, ref TEXT, annule INTEGER DEFAULT 0, depuis_sms INTEGER DEFAULT 0, cree INTEGER, categorie TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS jour (jour TEXT PRIMARY KEY, fond INTEGER, compte INTEGER, note TEXT, cloture_at INTEGER)");
    }

    // ---------- Saisies ----------

    public long addSaisie(Caisse.Saisie s) {
        ContentValues v = new ContentValues();
        v.put("date", s.date);
        v.put("mode", s.mode);
        v.put("sens", (long) s.sens);
        v.put("montant", s.montant);
        v.put("libelle", s.libelle == null ? "" : s.libelle);
        v.put("ref", s.ref == null ? "" : s.ref);
        v.put("annule", 0L);
        v.put("depuis_sms", s.depuisSms ? 1L : 0L);
        v.put("cree", System.currentTimeMillis());
        v.put("categorie", s.categorie);
        return getWritableDatabase().insertWithOnConflict("saisie", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Une saisie n'est jamais modifiée ni supprimée : elle est marquée annulée. */
    public void annulerSaisie(long id) {
        getWritableDatabase().execSQL("UPDATE saisie SET annule = 1 WHERE id = " + id);
    }

    public List<Caisse.Saisie> saisies() {
        List<Caisse.Saisie> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("saisie", new String[] {"id", "date", "mode", "sens", "montant", "libelle", "ref", "annule", "depuis_sms", "categorie"},
                null, null, null, null, "date ASC");
        try {
            while (c.moveToNext()) {
                Caisse.Saisie s = new Caisse.Saisie();
                s.id = c.getLong(0);
                s.date = c.getLong(1);
                s.mode = c.getString(2);
                s.sens = c.getInt(3);
                s.montant = c.getLong(4);
                s.libelle = c.getString(5) == null ? "" : c.getString(5);
                s.ref = c.getString(6) == null ? "" : c.getString(6);
                s.annule = c.getInt(7) == 1;
                s.depuisSms = c.getInt(8) == 1;
                s.categorie = c.isNull(9) ? null : c.getString(9);
                out.add(s);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ---------- Classement des opérations SMS ----------

    /** Enregistre la catégorie et le motif déclarés pour une opération. Le montant, lui, reste celui du SMS. */
    public void classer(String cle, String categorie, String motif) {
        ContentValues v = new ContentValues();
        v.put("cle", cle);
        v.put("categorie", categorie);
        v.put("motif", motif == null ? "" : motif);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("classement", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Map<String, Caisse.Classement> classements() {
        Map<String, Caisse.Classement> out = new HashMap<>();
        Cursor c = getReadableDatabase().query("classement", new String[] {"cle", "categorie", "motif"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                Caisse.Classement k = new Caisse.Classement();
                k.cle = c.getString(0);
                k.categorie = c.isNull(1) ? null : c.getString(1);
                k.motif = c.isNull(2) ? null : c.getString(2);
                out.put(k.cle, k);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** « Toujours classer ce contact ainsi » ; categorie null supprime la règle. */
    public void regle(String numero, String categorie) {
        if (categorie == null) {
            getWritableDatabase().delete("regle", "numero = ?", new String[] {numero});
            return;
        }
        ContentValues v = new ContentValues();
        v.put("numero", numero);
        v.put("categorie", categorie);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("regle", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Map<String, String> regles() {
        Map<String, String> out = new HashMap<>();
        Cursor c = getReadableDatabase().query("regle", new String[] {"numero", "categorie"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getString(1));
        } finally {
            c.close();
        }
        return out;
    }

    // ---------- Journées de caisse espèces ----------

    public List<Caisse.Jour> jours() {
        List<Caisse.Jour> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("jour", new String[] {"jour", "fond", "compte", "note", "cloture_at"}, null, null, null, null, "jour ASC");
        try {
            while (c.moveToNext()) {
                Caisse.Jour j = new Caisse.Jour();
                j.jour = c.getString(0);
                j.fond = c.isNull(1) ? null : c.getLong(1);
                j.compte = c.isNull(2) ? null : c.getLong(2);
                j.note = c.getString(3) == null ? "" : c.getString(3);
                j.clotureAt = c.isNull(4) ? 0 : c.getLong(4);
                out.add(j);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Caisse.Jour jour(String jour) {
        for (Caisse.Jour j : jours()) if (j.jour.equals(jour)) return j;
        return null;
    }

    private void ensureJour(String jour) {
        ContentValues v = new ContentValues();
        v.put("jour", jour);
        getWritableDatabase().insertWithOnConflict("jour", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void setFond(String jour, long fond) {
        ensureJour(jour);
        getWritableDatabase().execSQL("UPDATE jour SET fond = " + fond + " WHERE jour = '" + jour.replace("'", "") + "'");
    }

    public void cloturer(String jour, long compte, String note, long at) {
        ensureJour(jour);
        ContentValues v = new ContentValues();
        v.put("compte", compte);
        v.put("note", note == null ? "" : note);
        v.put("cloture_at", at);
        getWritableDatabase().update("jour", v, "jour = ?", new String[] {jour});
    }

    /** Enregistre un SMS ; renvoie true s'il est nouveau. */
    public boolean insert(SmsParser.Sms s) {
        ContentValues v = new ContentValues();
        v.put("hash", hash(s.address, s.body));
        v.put("address", s.address);
        v.put("body", s.body);
        v.put("date", s.date);
        v.put("sub", s.sub);
        return getWritableDatabase().insertWithOnConflict("sms", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<SmsParser.Sms> all() {
        List<SmsParser.Sms> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sms", new String[] {"address", "body", "date", "sub"}, null, null, null, null, "date ASC");
        try {
            while (c.moveToNext()) out.add(new SmsParser.Sms(c.getString(0), c.getString(1), c.getLong(2), c.getString(3)));
        } finally {
            c.close();
        }
        return out;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sms", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Efface la copie des SMS uniquement ; les saisies et les clôtures sont conservées. */
    public void clear() {
        getWritableDatabase().delete("sms", null, null);
    }

    public static void saveFlag(Context ctx, String key, boolean value) {
        ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    public static long prefLong(Context ctx, String key, long def) {
        try {
            return Long.parseLong(pref(ctx, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    static String hash(String address, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SmsParser.senderKey(address) + "|" + (body == null ? "" : body.trim())).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf((address + "|" + body).hashCode());
        }
    }

    // ---------- Réglages ----------

    public static SmsParser.Config config(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE);
        SmsParser.Config c = SmsParser.Config.of(p.getString("om", SmsParser.DEFAULT_OM), p.getString("momo", SmsParser.DEFAULT_MOMO), p.getBoolean("auto", false));
        String manual = p.getString("ownerOm", "").replaceAll("\\D", "");
        if (manual.length() > 9) manual = manual.substring(manual.length() - 9);
        c.ownerOm = manual.matches("6\\d{8}") ? manual : p.getString("ownerOmAuto", "");
        return c;
    }

    public static void savePref(Context ctx, String key, String value) {
        ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).edit().putString(key, value).apply();
    }

    public static String pref(Context ctx, String key, String def) {
        return ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).getString(key, def);
    }

    public static boolean prefBool(Context ctx, String key, boolean def) {
        return ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).getBoolean(key, def);
    }

    public static void saveConfig(Context ctx, String om, String momo, boolean auto, String ownerOm) {
        ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).edit()
                .putString("om", om).putString("momo", momo).putBoolean("auto", auto).putString("ownerOm", ownerOm).apply();
    }
}
