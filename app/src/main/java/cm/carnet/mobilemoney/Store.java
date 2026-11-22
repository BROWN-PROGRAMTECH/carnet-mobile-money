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
        super(ctx, "carnet.db", null, 9);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sms (hash TEXT PRIMARY KEY, address TEXT, body TEXT, date INTEGER, sub TEXT)");
        createCaisse(db);
        createClassement(db);
        upgradeTo4(db);
        createLecture(db);
        upgradeTo6(db);
        createFinances(db);
        createCategoriesPerso(db);
        upgradeTo9(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) createCaisse(db);
        else if (oldV == 2) db.execSQL("ALTER TABLE saisie ADD COLUMN categorie TEXT");
        if (oldV < 3) createClassement(db);
        if (oldV < 4) upgradeTo4(db);
        if (oldV < 5) createLecture(db);
        if (oldV < 6) upgradeTo6(db);
        if (oldV < 7) createFinances(db);
        if (oldV < 8) createCategoriesPerso(db);
        if (oldV < 9) upgradeTo9(db);
    }

    /**
     * Version 9 (0.10) : suivi de l'argent et dettes / créances.
     *  - saisie.source : argent qui a payé la dépense (mode SUIVI) ;
     *  - suivi : opération rattachée à l'argent qui l'a payée (cle = source : source suivie) ;
     *  - engagement : mouvements de dettes et de créances par personne, communs au compte.
     */
    private static void upgradeTo9(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE saisie ADD COLUMN source TEXT");
        db.execSQL("CREATE TABLE IF NOT EXISTS suivi (cle TEXT PRIMARY KEY, source TEXT, maj INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS engagement (id TEXT PRIMARY KEY, tiers TEXT, numero TEXT, type TEXT, sens INTEGER,"
                + " montant INTEGER, date INTEGER, echeance INTEGER, motif TEXT, cle TEXT, annule INTEGER DEFAULT 0, maj INTEGER)");
    }

    // ---------- Suivi de l'argent ----------

    /** Rattachements actifs : cle opération → cle source (cle = source : source suivie). */
    public Map<String, String> liens() {
        Map<String, String> m = new HashMap<>();
        Cursor c = getReadableDatabase().query("suivi", new String[] {"cle", "source"}, "source IS NOT NULL", null, null, null, null);
        try {
            while (c.moveToNext()) m.put(c.getString(0), c.getString(1));
        } finally {
            c.close();
        }
        return m;
    }

    /** Rattache (ou détache, source null) une opération ; cle = source marque une source suivie. */
    public void setLien(String cle, String source) {
        ContentValues v = new ContentValues();
        v.put("cle", cle);
        if (source == null) v.putNull("source"); else v.put("source", source);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("suivi", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------- Dettes et créances ----------

    public List<Dettes.Engagement> engagements() {
        List<Dettes.Engagement> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("engagement",
                new String[] {"id", "tiers", "numero", "type", "sens", "montant", "date", "echeance", "motif", "cle", "annule", "maj"},
                null, null, null, null, "date");
        try {
            while (c.moveToNext()) {
                Dettes.Engagement e = new Dettes.Engagement();
                e.id = c.getString(0);
                e.tiers = c.isNull(1) ? null : c.getString(1);
                e.numero = c.isNull(2) ? null : c.getString(2);
                e.type = c.isNull(3) ? Dettes.CREANCE : c.getString(3);
                e.sens = c.isNull(4) ? 1 : c.getInt(4);
                e.montant = c.isNull(5) ? 0 : c.getLong(5);
                e.date = c.isNull(6) ? 0 : c.getLong(6);
                e.echeance = c.isNull(7) ? null : c.getLong(7);
                e.motif = c.isNull(8) ? "" : c.getString(8);
                e.cle = c.isNull(9) ? null : c.getString(9);
                e.annule = !c.isNull(10) && c.getInt(10) != 0;
                e.maj = c.isNull(11) ? 0 : c.getLong(11);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Dettes.Engagement engagement(String id) {
        for (Dettes.Engagement e : engagements()) if (e.id.equals(id)) return e;
        return null;
    }

    public void setEngagement(Dettes.Engagement e) {
        ContentValues v = new ContentValues();
        v.put("id", e.id);
        if (e.tiers == null) v.putNull("tiers"); else v.put("tiers", e.tiers);
        if (e.numero == null) v.putNull("numero"); else v.put("numero", e.numero);
        v.put("type", e.type);
        v.put("sens", e.sens);
        v.put("montant", e.montant);
        v.put("date", e.date);
        if (e.echeance == null) v.putNull("echeance"); else v.put("echeance", e.echeance);
        v.put("motif", e.motif == null ? "" : e.motif);
        if (e.cle == null) v.putNull("cle"); else v.put("cle", e.cle);
        v.put("annule", e.annule ? 1 : 0);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("engagement", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Version 8 : catégories créées par l'utilisateur et catégories fournies masquées (communes au compte). */
    private static void createCategoriesPerso(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categorie_perso (code TEXT PRIMARY KEY, nom TEXT, sens INTEGER, masquee INTEGER, maj INTEGER)");
    }

    public List<Caisse.Perso> categoriesPerso() {
        List<Caisse.Perso> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("categorie_perso", new String[] {"code", "nom", "sens", "masquee", "maj"}, null, null, null, null, "maj");
        try {
            while (c.moveToNext()) {
                Caisse.Perso p = new Caisse.Perso();
                p.code = c.getString(0);
                p.nom = c.isNull(1) ? null : c.getString(1);
                p.sens = c.isNull(2) ? 0 : c.getInt(2);
                p.masquee = !c.isNull(3) && c.getInt(3) != 0;
                p.maj = c.isNull(4) ? 0 : c.getLong(4);
                out.add(p);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void setCategoriePerso(Caisse.Perso p) {
        ContentValues v = new ContentValues();
        v.put("code", p.code);
        if (p.nom == null) v.putNull("nom"); else v.put("nom", p.nom);
        v.put("sens", p.sens);
        v.put("masquee", p.masquee ? 1 : 0);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("categorie_perso", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Version 7 (finances) : budget mensuel par poste de dépense et autres avoirs (banque sans SMS, épargne, tontine…).
     * Montants en centimes ; un montant NULL vaut suppression (gardée pour la synchronisation).
     */
    private static void createFinances(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS budget (poste TEXT PRIMARY KEY, montant INTEGER, maj INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS avoir (id TEXT PRIMARY KEY, nom TEXT, montant INTEGER, maj INTEGER)");
    }

    /** Budgets mensuels par poste (centimes). */
    public Map<String, Long> budgets() {
        Map<String, Long> out = new HashMap<>();
        Cursor c = getReadableDatabase().query("budget", new String[] {"poste", "montant"}, "montant IS NOT NULL AND montant > 0", null, null, null, null);
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        } finally {
            c.close();
        }
        return out;
    }

    /** Fixe (ou retire, montant null ou 0) le budget mensuel d'un poste. */
    public void setBudget(String poste, Long montant) {
        ContentValues v = new ContentValues();
        v.put("poste", poste);
        if (montant == null || montant <= 0) v.putNull("montant"); else v.put("montant", montant);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("budget", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static final class Avoir {
        public String id, nom;
        public long montant, maj;
    }

    /** Autres avoirs saisis à la main (hors OM, MoMo, banques lues par SMS et espèces). */
    public List<Avoir> avoirs() {
        List<Avoir> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("avoir", new String[] {"id", "nom", "montant", "maj"}, "montant IS NOT NULL", null, null, null, "nom");
        try {
            while (c.moveToNext()) {
                Avoir a = new Avoir();
                a.id = c.getString(0);
                a.nom = c.isNull(1) ? "" : c.getString(1);
                a.montant = c.getLong(2);
                a.maj = c.isNull(3) ? 0 : c.getLong(3);
                out.add(a);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Ajoute ou modifie un avoir ; montant null = suppression. */
    public void setAvoir(String id, String nom, Long montant) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("nom", nom);
        if (montant == null) v.putNull("montant"); else v.put("montant", montant);
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("avoir", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Version 6 (caisses) : origine de chaque SMS. « ici » = lu dans la messagerie de ce téléphone (ou de sa caisse),
     * « autre » = reçu d'un autre téléphone du même compte avant la version 0.8, mis de côté (jamais effacé).
     * NULL = pas encore vérifié : compté normalement tant que la vérification n'a pas eu lieu.
     */
    private static void upgradeTo6(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE sms ADD COLUMN origine TEXT");
    }

    /** Filtre SQL des SMS de ce téléphone. */
    private static final String ICI = "(origine IS NULL OR origine <> 'autre')";

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
        v.put("origine", "ici");
        SQLiteDatabase db = getWritableDatabase();
        if (db.insertWithOnConflict("sms", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) return true;
        // Déjà présent : s'il avait été mis de côté, il est bien dans la messagerie de ce téléphone.
        ContentValues o = new ContentValues();
        o.put("origine", "ici");
        o.put("maj", System.currentTimeMillis());
        return db.update("sms", o, "hash = ? AND origine = 'autre'", new String[] {hash(s.address, s.body)}) > 0;
    }

    /**
     * Vérification unique (version 0.8) : les SMS présents dans la messagerie de ce téléphone sont à lui ;
     * les autres SMS pas encore vérifiés venaient d'un autre téléphone du compte et sont mis de côté.
     * Renvoie le nombre de SMS mis de côté.
     */
    public int marquerOrigine(java.util.Set<String> boite) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<String> inconnus = new ArrayList<>();
            Cursor c = db.query("sms", new String[] {"hash"}, "origine IS NULL", null, null, null, null);
            try {
                while (c.moveToNext()) inconnus.add(c.getString(0));
            } finally {
                c.close();
            }
            int autres = 0;
            for (String h : inconnus) {
                ContentValues v = new ContentValues();
                boolean ici = boite.contains(h);
                v.put("origine", ici ? "ici" : "autre");
                if (!ici) autres++;
                db.update("sms", v, "hash = ?", new String[] {h});
            }
            db.setTransactionSuccessful();
            return autres;
        } finally {
            db.endTransaction();
        }
    }

    /** Un seul téléphone sur le compte : tous les SMS pas encore vérifiés sont à lui. */
    public void toutIci() {
        ContentValues v = new ContentValues();
        v.put("origine", "ici");
        getWritableDatabase().update("sms", v, "origine IS NULL", null);
    }

    /** SMS mis de côté (venus d'un autre téléphone). */
    public int misDeCote() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sms WHERE origine = 'autre'", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Remet dans cette caisse les SMS mis de côté (s'ils étaient en fait de ce téléphone). */
    public void recupererMisDeCote() {
        ContentValues v = new ContentValues();
        v.put("origine", "ici");
        v.put("maj", System.currentTimeMillis());
        getWritableDatabase().update("sms", v, "origine = 'autre'", null);
    }

    /** Signature des données (nombre et dernière modification) : sert à savoir si les résumés sont à recalculer. */
    public String signature() {
        StringBuilder b = new StringBuilder();
        for (String t : new String[] {"sms", "classement", "regle", "saisie", "jour", "budget", "avoir", "categorie_perso", "suivi", "engagement"}) {
            Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), MAX(maj) FROM " + t, null);
            try {
                if (c.moveToFirst()) b.append(c.getLong(0)).append(':').append(c.isNull(1) ? 0 : c.getLong(1)).append('|');
            } finally {
                c.close();
            }
        }
        return b.toString();
    }

    public List<SmsParser.Sms> all() {
        List<SmsParser.Sms> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sms", new String[] {"address", "body", "date", "sub", "hash"}, ICI, null, null, null, "date ASC");
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
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sms WHERE " + ICI, null);
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

    /** Enregistre un mouvement ; renseigne s.uid. */
    public long addSaisie(Caisse.Saisie s) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        if (s.uid == null || s.uid.isEmpty()) s.uid = UUID.randomUUID().toString().replace("-", "");
        v.put("uid", s.uid);
        if (s.source == null) v.putNull("source"); else v.put("source", s.source);
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
        Cursor c = getReadableDatabase().query("saisie", new String[] {"id", "date", "mode", "sens", "montant", "libelle", "ref", "annule", "depuis_sms", "categorie", "uid", "source"},
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
                s.uid = c.isNull(10) ? null : c.getString(10);
                s.source = c.isNull(11) ? null : c.getString(11);
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
            new Spec("saisie", "uid", false, "uid", "date:n", "mode", "sens:n", "montant:n", "libelle", "ref", "annule:n", "depuis_sms:n", "cree:n", "categorie", "source", "maj:n"),
            new Spec("jour", "jour", false, "jour", "fond:n", "compte:n", "note", "cloture_at:n", "maj:n"),
            new Spec("budget", "poste", false, "poste", "montant:n", "maj:n"),
            new Spec("avoir", "id", false, "id", "nom", "montant:n", "maj:n"),
            new Spec("categorie_perso", "code", false, "code", "nom", "sens:n", "masquee:n", "maj:n"),
            new Spec("suivi", "cle", false, "cle", "source", "maj:n"),
            new Spec("engagement", "id", false, "id", "tiers", "numero", "type", "sens:n", "montant:n", "date:n", "echeance:n", "motif", "cle",
                    "annule:n", "maj:n"),
    };

    /** Lignes modifiées après "since", par ordre de modification. Valeurs : String, Long ou null. */
    /**
     * Lignes modifiées après la position (since, sinceKey), triées par (maj, clé).
     * La clé départage les nombreuses lignes enregistrées à la même milliseconde (lecture de la messagerie) :
     * sans elle, la sauvegarde s'arrêtait après le premier lot de 500.
     */
    public List<Object[]> changedSince(Spec s, long since, String sinceKey, int limit) {
        List<Object[]> out = new ArrayList<>();
        String t = String.valueOf(since);
        String where = "(maj > ? OR (maj = ? AND " + s.key + " > ?))";
        // Les SMS mis de côté (venus d'un autre téléphone) ne sont jamais renvoyés dans la caisse de ce téléphone.
        if ("sms".equals(s.table)) where += " AND origine = 'ici'";
        Cursor c = getReadableDatabase().query(s.table, s.cols, where,
                new String[] {t, t, sinceKey == null ? "" : sinceKey}, null, null, "maj ASC, " + s.key + " ASC", String.valueOf(limit));
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
        // Depuis la version 0.8, le téléphone ne reçoit que les SMS de sa propre caisse.
        if ("sms".equals(s.table)) v.put("origine", "ici");
        if (s.immutable) {
            if (db.insertWithOnConflict(s.table, null, v, SQLiteDatabase.CONFLICT_IGNORE) == -1 && "sms".equals(s.table)) {
                ContentValues o = new ContentValues();
                o.put("origine", "ici");
                db.update("sms", o, "hash = ? AND origine = 'autre'", new String[] {key});
            }
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
        SmsParser.Config c = SmsParser.Config.of(p.getString("om", SmsParser.DEFAULT_OM), p.getString("momo", SmsParser.DEFAULT_MOMO),
                p.getString("banques", SmsParser.DEFAULT_BANQUES), p.getBoolean("auto", false));
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
