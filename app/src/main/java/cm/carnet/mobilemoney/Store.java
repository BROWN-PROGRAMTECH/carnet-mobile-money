package cm.carnet.mobilemoney;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Données locales : copie des SMS OM et MoMo (les autres SMS ne sont jamais enregistrés),
 * classement des opérations, mouvements et journées d'espèces.
 * Chaque ligne porte une date de modification (maj) pour la sauvegarde en ligne.
 */
public final class Store extends SQLiteOpenHelper {
    private static Store instance;

    public static synchronized Store get(Context ctx) {
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    private Store(Context ctx) {
        super(ctx, "carnet.db", null, 5);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sms (hash TEXT PRIMARY KEY, address TEXT, body TEXT, date INTEGER, sub TEXT)");
        createCaisse(db);
        createClassement(db);
        upgradeTo4(db);
        createLecture(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) createCaisse(db);
        else if (oldV == 2) db.execSQL("ALTER TABLE saisie ADD COLUMN categorie TEXT");
        if (oldV < 3) createClassement(db);
        if (oldV < 4) upgradeTo4(db);
        if (oldV < 5) createLecture(db);
    }

    /** Version 5 : mémoire de lecture, pour ne plus relire à chaque ouverture les SMS déjà compris. */
    private static void createLecture(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS lecture (hash TEXT PRIMARY KEY, data TEXT)");
    }

    /** Version 2 : mouvements d'espèces et journées de caisse. */
    private static void createCaisse(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS saisie (id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, mode TEXT, sens INTEGER,"
                + " montant INTEGER, libelle TEXT, ref TEXT, annule INTEGER DEFAULT 0, depuis_sms INTEGER DEFAULT 0, cree INTEGER, categorie TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS jour (jour TEXT PRIMARY KEY, fond INTEGER, compte INTEGER, note TEXT, cloture_at INTEGER)");
    }

    /** Version 3 : catégorie et motif déclarés pour chaque opération SMS, règles par contact. */
    private static void createClassement(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS classement (cle TEXT PRIMARY KEY, categorie TEXT, motif TEXT, maj INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS regle (numero TEXT PRIMARY KEY, categorie TEXT, maj INTEGER)");
    }

    /** Version 4 : date de modification partout et identifiant universel des saisies, pour la sauvegarde en ligne. */
    private static void upgradeTo4(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        db.execSQL("ALTER TABLE sms ADD COLUMN maj INTEGER");
        db.execSQL("ALTER TABLE saisie ADD COLUMN uid TEXT");
        db.execSQL("ALTER TABLE saisie ADD COLUMN maj INTEGER");
        db.execSQL("ALTER TABLE jour ADD COLUMN maj INTEGER");
        db.execSQL("UPDATE sms SET maj = " + now);
        db.execSQL("UPDATE saisie SET uid = lower(hex(randomblob(16))) WHERE uid IS NULL");
        db.execSQL("UPDATE saisie SET maj = " + now);
        db.execSQL("UPDATE jour SET maj = " + now);
        db.execSQL("UPDATE classement SET maj = " + now + " WHERE maj IS NULL");
        db.execSQL("UPDATE regle SET maj = " + now + " WHERE maj IS NULL");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS saisie_uid ON saisie(uid)");
        db.execSQL("CREATE INDEX IF NOT EXISTS sms_maj ON sms(maj)");
    }

    // ---------- SMS ----------

    /** Enregistre un SMS ; renvoie true s'il est nouveau. */
    public boolean insert(SmsParser.Sms s) {
        ContentValues v = new ContentValues();
        v.put("hash", hash(s.address, s.body));
        v.put("address", s.address);
        v.put("body", s.body);
        v.put("date", s.date);
        v.put("sub", s.sub);
        v.put("maj", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("sms", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<SmsParser.Sms> all() {
        List<SmsParser.Sms> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sms", new String[] {"address", "body", "date", "sub", "hash"}, null, null, null, null, "date ASC");
        try {
            while (c.moveToNext()) {
                SmsParser.Sms s = new SmsParser.Sms(c.getString(0), c.getString(1), c.getLong(2), c.getString(3));
                s.hash = c.getString(4);
                out.add(s);
            }
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

    /** Efface la copie des SMS uniquement ; classements, espèces et clôtures sont conservés. */
    public void clear() {
        getWritableDatabase().delete("sms", null, null);
    }

    static String hash(String address, String body) {
        return SmsParser.hashOf(address, body);
    }

    // ---------- Mémoire de lecture ----------

    /** Charge la mémoire de lecture (empreinte du SMS -> résultat de lecture). */
    public Analyzer.Cache lecture(Context ctx) {
        Analyzer.Cache cache = new Analyzer.Cache();
        cache.version = pref(ctx, "lectureVersion", "");
        Cursor c = getReadableDatabase().query("lecture", new String[] {"hash", "data"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) cache.data.put(c.getString(0), c.getString(1));
        } finally {
            c.close();
        }
        return cache;
    }

    /** Enregistre les nouvelles lectures (ou toute la mémoire si elle a été vidée). */
    public void saveLecture(Context ctx, Analyzer.Cache cache) {
        if (!cache.reset && cache.added.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (cache.reset) db.delete("lecture", null, null);
            Map<String, String> rows = cache.reset ? cache.data : cache.added;
            for (Map.Entry<String, String> e : rows.entrySet()) {
                ContentValues v = new ContentValues();
                v.put("hash", e.getKey());
                v.put("data", e.getValue());
                db.insertWithOnConflict("lecture", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        savePref(ctx, "lectureVersion", cache.version);
        cache.added.clear();
        cache.reset = false;
    }

    // ---------- Mouvements d'espèces ----------

    public long addSaisie(Caisse.Saisie s) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("uid", UUID.randomUUID().toString().replace("-", ""));
        v.put("date", s.date);
        v.put("mode", s.mode);
        v.put("sens", (long) s.sens);
        v.put("montant", s.montant);
        v.put("libelle", s.libelle == null ? "" : s.libelle);
        v.put("ref", s.ref == null ? "" : s.ref);
        v.put("annule", 0L);
        v.put("depuis_sms", s.depuisSms ? 1L : 0L);
        v.put("cree", now);
        v.put("categorie", s.categorie);
        v.put("maj", now);
        return getWritableDatabase().insertWithOnConflict("saisie", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Un mouvement n'est jamais modifié ni supprimé : il est marqué annulé. */
    public void annulerSaisie(long id) {
        getWritableDatabase().execSQL("UPDATE saisie SET annule = 1, maj = " + System.currentTimeMillis() + " WHERE id = " + id);
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

    /** « Toujours classer ce contact ainsi » ; categorie null désactive la règle (conservée vide pour la synchronisation). */
    public void regle(String numero, String categorie) {
        ContentValues v = new ContentValues();
        v.put("numero", numero);
        v.put("categorie", categorie);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("regle", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Map<String, String> regles() {
        Map<String, String> out = new HashMap<>();
        Cursor c = getReadableDatabase().query("regle", new String[] {"numero", "categorie"}, "categorie IS NOT NULL", null, null, null, null);
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

    private void ensureJour(String jour) {
        ContentValues v = new ContentValues();
        v.put("jour", jour);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("jour", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void setFond(String jour, long fond) {
        ensureJour(jour);
        ContentValues v = new ContentValues();
        v.put("fond", fond);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().update("jour", v, "jour = ?", new String[] {jour});
    }

    public void cloturer(String jour, long compte, String note, long at) {
        ensureJour(jour);
        ContentValues v = new ContentValues();
        v.put("compte", compte);
        v.put("note", note == null ? "" : note);
        v.put("cloture_at", at);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().update("jour", v, "jour = ?", new String[] {jour});
    }

    // ---------- Synchronisation ----------

    /** Description d'une table sauvegardée en ligne : nom, clé, colonnes (la dernière est maj) et colonnes numériques. */
    public static final class Spec {
        public final String table, key;
        public final String[] cols;
        public final boolean[] numeric;
        /** true : une ligne ne change jamais après création (SMS). */
        public final boolean immutable;

        Spec(String table, String key, boolean immutable, String... colsWithType) {
            this.table = table;
            this.key = key;
            this.immutable = immutable;
            cols = new String[colsWithType.length];
            numeric = new boolean[colsWithType.length];
            for (int i = 0; i < colsWithType.length; i++) {
                String[] p = colsWithType[i].split(":");
                cols[i] = p[0];
                numeric[i] = p.length > 1 && "n".equals(p[1]);
            }
        }
    }

    public static final Spec[] SYNC = {
            new Spec("sms", "hash", true, "hash", "address", "body", "date:n", "sub", "maj:n"),
            new Spec("classement", "cle", false, "cle", "categorie", "motif", "maj:n"),
            new Spec("regle", "numero", false, "numero", "categorie", "maj:n"),
            new Spec("saisie", "uid", false, "uid", "date:n", "mode", "sens:n", "montant:n", "libelle", "ref", "annule:n", "depuis_sms:n", "cree:n", "categorie", "maj:n"),
            new Spec("jour", "jour", false, "jour", "fond:n", "compte:n", "note", "cloture_at:n", "maj:n"),
    };

    /** Lignes modifiées après "since", par ordre de modification. Valeurs : String, Long ou null. */
    public List<Object[]> changedSince(Spec s, long since, int limit) {
        List<Object[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(s.table, s.cols, "maj > ?", new String[] {String.valueOf(since)}, null, null, "maj ASC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                Object[] row = new Object[s.cols.length];
                for (int i = 0; i < s.cols.length; i++) row[i] = c.isNull(i) ? null : (s.numeric[i] ? (Object) c.getLong(i) : c.getString(i));
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Fusionne une ligne reçue du serveur : ajoutée si absente, remplacée seulement si elle est plus récente. */
    public void merge(Spec s, Object[] row) {
        ContentValues v = new ContentValues();
        String key = null;
        long maj = 0;
        for (int i = 0; i < s.cols.length; i++) {
            Object o = row[i];
            if (s.cols[i].equals(s.key)) key = o == null ? null : o.toString();
            if ("maj".equals(s.cols[i]) && o instanceof Long) maj = (Long) o;
            if (o == null) v.putNull(s.cols[i]);
            else if (o instanceof Long) v.put(s.cols[i], (Long) o);
            else v.put(s.cols[i], o.toString());
        }
        if (key == null) return;
        SQLiteDatabase db = getWritableDatabase();
        if (s.immutable) {
            db.insertWithOnConflict(s.table, null, v, SQLiteDatabase.CONFLICT_IGNORE);
            return;
        }
        Cursor c = db.query(s.table, new String[] {"maj"}, s.key + " = ?", new String[] {key}, null, null, null);
        boolean exists;
        long localMaj = 0;
        try {
            exists = c.moveToFirst();
            if (exists && !c.isNull(0)) localMaj = c.getLong(0);
        } finally {
            c.close();
        }
        if (!exists) db.insertWithOnConflict(s.table, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        else if (maj > localMaj) db.update(s.table, v, s.key + " = ?", new String[] {key});
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

    public static void saveConfig(Context ctx, String om, String momo, boolean auto, String ownerOm) {
        ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).edit()
                .putString("om", om).putString("momo", momo).putBoolean("auto", auto).putString("ownerOm", ownerOm).apply();
    }
}
