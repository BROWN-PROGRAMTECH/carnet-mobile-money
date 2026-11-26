package cm.carnet.mobilemoney;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caisse automatique : chaque opération lue dans un SMS devient une ligne de caisse au montant non modifiable.
 * L'utilisateur la classe (Vente, Paiement, Don, Personnel, Autre) et ajuste le motif. Les espèces sont saisies à la main.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERM = 1, REQ_XLSX = 2;
    private static final int MAX_ROWS = 400;
    private static final String[] PERIODS = {"Aujourd'hui", "7 jours", "Ce mois", "Tout"};
    private static final int CUSTOM = 4;
    private static final int PAGE_CAISSE = 0, PAGE_CLASSER = 1, PAGE_FINANCES = 2, PAGE_EXPORT = 3, PAGE_COMPTE = 4, NB_PAGES = 5;
    private static final int LISTE_JOURNAL = 0, LISTE_ESPECES = 1, LISTE_CHECK = 2;
    /** Boutons rapides de la page À classer, selon le sens ; les autres catégories sont dans « Autres ▸ ». */
    private static final String[] RAPIDES_ENTREE = {Caisse.VENTE, Caisse.APPORT, Caisse.TRANSFERT};
    private static final String[] RAPIDES_SORTIE = {Caisse.ACHAT, Caisse.CHARGE_TRANSPORT, Caisse.RETRAIT_PERSO};

    private static final int BG = Color.parseColor("#F3F5F2"), SURFACE = Color.WHITE, INK = Color.parseColor("#17201D"),
            MUTED = Color.parseColor("#5B6662"), LINE = Color.parseColor("#D8DED9"), ACCENT = Color.parseColor("#0E6A5B"),
            OM = Color.parseColor("#D9650F"), MOMO = Color.parseColor("#B88A00"), CASH = Color.parseColor("#3B5BA5"),
            GOOD = Color.parseColor("#1E7546"), WARN = Color.parseColor("#9A5600"), BAD = Color.parseColor("#B0261D"),
            GOOD_BG = Color.parseColor("#E3F1E8"), BAD_BG = Color.parseColor("#F8E1DF"), WARN_BG = Color.parseColor("#F8EBD8"),
            PURPLE = Color.parseColor("#6B4FA0"), ON_ACCENT_SOFT = Color.parseColor("#CFE6DF"), BANK = Color.parseColor("#2F5D8A");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout permBox, page, periodBar;
    private HorizontalScrollView periodScroll;
    private ScrollView scroll;
    private TextView status, noticeText, classerBadge;
    private final TextView[] periodChips = new TextView[PERIODS.length + 1];
    private final LinearLayout[] navItems = new LinearLayout[NB_PAGES];
    private final FrameLayout[] navIconBoxes = new FrameLayout[NB_PAGES];
    private final ImageView[] navIcons = new ImageView[NB_PAGES];
    private final TextView[] navLabels = new TextView[NB_PAGES];
    // Finances : mois affiché (0 = mois en cours), budgets en cours de saisie.
    private int moisDecalage;
    private boolean budgetsOuverts, fourniesOuvert;
    private final Map<String, EditText> budgetFields = new HashMap<>();
    private int period = 0, pageIdx = PAGE_CAISSE, liste = LISTE_JOURNAL;
    private long customFrom, customTo;
    private boolean loading, rechargeOuverte, avanceOuvert, uiPrete, rechargeManuel;
    private String rechargeOp = "OM";
    private final java.util.Set<String> comptesOuverts = new java.util.HashSet<>();
    private final Runnable hideNotice = () -> noticeText.setVisibility(View.GONE);

    // Champs de saisie conservés d'un affichage à l'autre (sinon un nouveau SMS effacerait la saisie en cours).
    private EditText codePromoField;
    private TextView rechargeBonusInfo;
    private EditText rechargeMontant, rechargeMontantManuel, rechargeRef, phoneField, secretField, ownerField, omField, momoField, banquesField;
    private CheckBox agentOmBox, agentMomoBox, autoBox;

    // Export en cours : figé au moment de la confirmation.
    private int exportNb;
    private String exportLabel = "";
    private long[] exportRange = {0, Long.MAX_VALUE};
    private boolean exportPayant, exportToutes;
    private org.json.JSONObject exportVue;

    // Plusieurs téléphones sur un compte : une caisse par téléphone, vue « Toutes mes caisses » protégée par le code patron.
    private boolean vueToutes, vueChargement, renommerOuvert, codePatronOuvert;
    /** Code patron saisi pendant cette session (jamais enregistré sur le téléphone). */
    private String codePatron;
    private org.json.JSONObject vueData;
    private String vueKey = "", vueKeyTentee = "", vueErreur;
    private EditText nomCaisseField, renommerField, codeVueField, codeRepriseField, codeAncienField, codeNouveauField;

    private Analyzer.Result result;
    private Map<String, Caisse.Classement> classements = new HashMap<>();
    private Map<String, String> regles = new HashMap<>();
    private List<Caisse.Saisie> saisies = new ArrayList<>();
    private List<Caisse.Jour> jours = new ArrayList<>();
    private Caisse.Bilan bilan;
    /** Soldes de la période affichée (version 0.15). */
    private Soldes.Releve releve;
    /** Suivi de l'argent (cle opération → cle source) et dettes / créances. */
    private Map<String, String> liens = new HashMap<>();
    private List<Dettes.Engagement> engagements = new ArrayList<>();
    /** Écrans de détail de l'onglet Finances (null = fermé) ; personneOuverte "" = liste de toutes les personnes. */
    private String suiviOuvert, personneOuverte, categorieOuverte;
    /** Applications (0.11) : état en mémoire, écran du catalogue, choix à l'inscription. */
    private Modules.Etat etatModules;
    private boolean applicationsOuvert, choixModules, alertesChargees;
    /** Agent IA (0.13) : propositions de l'IA en ligne, conversation avec l'assistant. */
    private final Map<String, Classeur.Proposition> propositionsIa = new HashMap<>();
    private Map<String, Classeur.Proposition> propsCourantes = new HashMap<>();
    private boolean assistantOuvert, assistantAttente, analyseOuvert;
    private final List<String[]> conversation = new ArrayList<>();
    private org.json.JSONArray historiqueIa = new org.json.JSONArray();
    private EditText questionChamp;
    private String iaCompteur;
    private int profilChoisi = -1;
    private final java.util.Set<String> cochees = new java.util.LinkedHashSet<>();
    private int categorieSens = 1;


    private final BroadcastReceiver onNewSms = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { reload(false); }
    };

    // ---------- Cycle de vie ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        period = (int) Store.prefLong(this, "periode", 0);
        customFrom = Store.prefLong(this, "debut", 0);
        customTo = Store.prefLong(this, "fin", 0);
        if (period < 0 || period > CUSTOM || (period == CUSTOM && customTo <= 0)) period = 0;
        Crash.install(this);
        // Version 0.9 : les SMS des banques n'étaient pas lus avant ; relecture complète de la messagerie, une seule fois.
        if (!Store.prefBool(this, "banques_lues", false)) {
            resetInbox();
            Store.saveFlag(this, "banques_lues", true);
        }
        String trace = Crash.aAfficher(this);
        if (trace != null) {
            showCrashScreen(trace);
            return;
        }
        try {
            buildUi();
            uiPrete = true;
        } catch (RuntimeException e) {
            Crash.enregistrer(this, Thread.currentThread(), e, true);
            showCrashScreen(Crash.aAfficher(this));
        }
    }

    /** Écran affiché après un plantage : le détail est envoyé au support, puis l'application est rouverte. */
    private void showCrashScreen(String trace) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout box = column(12);
        box.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        box.addView(text("L'application s'est fermée à cause d'une erreur", 20, INK, true));
        box.addView(text("Vos données ne sont pas touchées. Le détail technique ci-dessous est envoyé automatiquement au support"
                + " dès qu'il y a du réseau (si vous êtes connecté à votre compte). Vous pouvez aussi en faire une capture d'écran.", 14, MUTED, false));
        box.addView(button("Rouvrir l'application", true, v -> { Crash.marquerAffiche(this); recreate(); }), fill());
        TextView t = text(trace == null ? "" : trace, 11, INK, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setBackground(box(SURFACE, LINE, 10));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(t, fill());
        io.execute(() -> Cloud.envoyerPlantages(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!uiPrete) return;
        IntentFilter f = new IntentFilter(SmsReceiver.ACTION_NEW);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(onNewSms, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(onNewSms, f);
        refreshPermissionState();
        autoSync();
    }

    @Override
    public void onBackPressed() {
        if (uiPrete && fermerDetail()) { render(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(onNewSms); } catch (Exception ignored) {}
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissionState() {
        boolean ok = hasSmsPermission();
        if (!ok) setStatus("En attente de l'autorisation de lire les SMS.");
        reload(ok);
    }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.READ_SMS);
        p.add(Manifest.permission.RECEIVE_SMS);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(p.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM) refreshPermissionState();
    }

    private boolean agent(String op) { return Store.prefBool(this, "agent" + op, false); }

    // ---------- Période ----------

    private long[] range() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        switch (period) {
            case 0: break;
            case 1: c.add(Calendar.DAY_OF_MONTH, -6); break;
            case 2: c.set(Calendar.DAY_OF_MONTH, 1); break;
            case CUSTOM: return new long[] {customFrom, customTo};
            default: return new long[] {0, Long.MAX_VALUE};
        }
        return new long[] {c.getTimeInMillis(), Long.MAX_VALUE};
    }

    private String periodLabel() {
        long[] r = range();
        SimpleDateFormat d = new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE);
        if (period == CUSTOM) return "Du " + d.format(new Date(r[0])) + " au " + d.format(new Date(r[1]));
        if (r[0] == 0) return "Tout l'historique";
        return PERIODS[period] + " (depuis le " + d.format(new Date(r[0])) + ")";
    }

    /** Nom court de la période pour les titres : « Aujourd'hui », « Ce mois », « 03/11 – 20/11 »… */
    private String periodeTexte() {
        if (period == CUSTOM) {
            SimpleDateFormat f = new SimpleDateFormat("dd/MM", Locale.FRANCE);
            return f.format(new Date(customFrom)) + " – " + f.format(new Date(customTo));
        }
        return PERIODS[period];
    }

    private void setPeriod(int p) {
        period = p;
        Store.savePref(this, "periode", String.valueOf(p));
        render();
    }

    // ---------- Chargement ----------

    /** Mémoire de lecture des SMS, chargée une fois puis complétée au fil de l'eau. */
    private Analyzer.Cache lecture;
    /** Ce qui a servi à la dernière analyse : si rien n'a changé, on ne réanalyse pas. */
    private String analysedKey = "";
    private boolean pendingImport;

    /**
     * Ouverture rapide : affiche d'abord ce qui est déjà en mémoire, puis cherche seulement les SMS
     * arrivés depuis la dernière lecture.
     */
    private void reload(boolean importInbox) {
        if (loading) {
            pendingImport |= importInbox;
            return;
        }
        loading = true;
        if (result == null) setStatus("Ouverture de votre caisse…");
        io.execute(() -> {
            String msg;
            try {
                Store store = Store.get(this);
                SmsParser.Config cfg = Store.config(this);

                // 1. Afficher tout de suite ce qui est déjà enregistré.
                Analyzer.Result r = analyseStored(store, cfg);
                loadCaisse(store);
                final boolean first = Store.prefLong(this, "inboxDernier", 0) == 0;
                final String early = importInbox && hasSmsPermission()
                        ? (first ? "Première lecture de votre messagerie, une seule fois…" : "Recherche de nouveaux SMS…")
                        : r.tx.size() + " opérations";
                runOnUiThread(() -> { setStatus(early); render(); });

                // 2. Lire seulement les nouveaux SMS (abonnement actif uniquement ; sinon ils seront lus au réabonnement).
                int added = 0;
                if (importInbox && hasSmsPermission()) {
                    added = importInbox(cfg);
                    if (added > 0) r = analyseStored(store, Store.config(this));
                }
                Store.savePref(this, "nonReconnus", String.valueOf(r.unknown.size()));
                // Recharge payée : l'ID du SMS de l'opérateur est déclaré tout seul.
                if (Cloud.rechargeAttente(this) != null) {
                    final String rm = Cloud.recharges(this, r);
                    if (rm != null) runOnUiThread(() -> notice(rm, rm.startsWith("Recharge non") ? 2 : 1));
                }
                msg = r.tx.size() + " opérations" + (added > 0 ? " · " + added + " nouveau(x) SMS" : " · à jour");
                if (r.scanned == 0 && !hasSmsPermission()) msg = "Autorisez la lecture des SMS pour commencer.";
            } catch (Exception e) {
                msg = "Erreur de lecture : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                loading = false;
                setStatus(m);
                render();
                if (pendingImport) {
                    pendingImport = false;
                    reload(true);
                }
            });
        });
    }

    /** Analyse les SMS enregistrés en réutilisant la mémoire de lecture ; ne refait rien si rien n'a changé. */
    private Analyzer.Result analyseStored(Store store, SmsParser.Config cfg) {
        String key = cfg.om + "|" + cfg.momo + "|" + cfg.banques + "|" + cfg.auto + "|" + cfg.ownerOm + "|" + store.count();
        Analyzer.Result current = result;
        if (current != null && key.equals(analysedKey)) return current;
        if (lecture == null) lecture = store.lecture(this);
        Analyzer.Result r = Analyzer.analyse(store.all(), cfg, lecture);
        store.saveLecture(this, lecture);
        if (!r.ownerOm.isEmpty()) Store.savePref(this, "ownerOmAuto", r.ownerOm);
        result = r;
        analysedKey = key;
        recalerChaine(r);
        return r;
    }

    /**
     * Version 0.17 : recale le dernier solde connu de chaque compte (contrôle des paiements à la réception) sur le relevé
     * complet, et calcule le seuil automatique du float agent (une journée moyenne de dépôts).
     */
    private void recalerChaine(Analyzer.Result r) {
        long now = System.currentTimeMillis();
        java.util.Set<String> douteux = douteux();
        for (String op : new String[] {Caisse.OM, Caisse.MOMO}) {
            // Dernier solde d'un SMS non douteux (un faux SMS non écarté ne doit pas fausser le contrôle suivant).
            Txn dernier = null;
            for (Txn t : r.tx) {
                if (!op.equals(t.op) || t.apres == null || douteux.contains(Caisse.cle(t))) continue;
                if (dernier == null || t.date > dernier.date) dernier = t;
            }
            if (dernier != null) Store.savePref(this, "chaine_" + op, String.valueOf(dernier.apres));
            if (agent(op)) Store.savePref(this, "float_auto_" + op, String.valueOf(Flotte.electronique(r, op, 0, now).parJour));
        }
    }

    /** Clés des paiements reçus non confirmés (solde qui ne suit pas, ID déjà vu). */
    private java.util.Set<String> douteux() {
        java.util.Set<String> s = new java.util.HashSet<>(java.util.Arrays.asList(Store.pref(this, "douteux", "").split("\n")));
        s.remove("");
        return s;
    }

    private void retirerDouteux(String cle) {
        java.util.Set<String> s = douteux();
        s.remove(cle);
        Store.savePref(this, "douteux", String.join("\n", s));
    }

    private void loadCaisse(Store store) {
        Caisse.definirPerso(store.categoriesPerso());
        Map<String, Caisse.Classement> cl = store.classements();
        Map<String, String> rg = store.regles();
        List<Caisse.Saisie> s = store.saisies();
        List<Caisse.Jour> j = store.jours();
        Modules.Etat em = Cloud.modules(this);
        Map<String, String> li = store.liens();
        List<Dettes.Engagement> en = store.engagements();
        classements = cl;
        regles = rg;
        saisies = s;
        jours = j;
        liens = li;
        engagements = en;
        etatModules = em;
        if (Cloud.configured() && Cloud.loggedIn(this) && em.aChoisir() && Store.pref(this, "choix_modules_fait", "").isEmpty()) choixModules = true;
    }

    /** Oublie la position de lecture : la prochaine actualisation relira toute la messagerie (une fois). */
    private void resetInbox() {
        Store.savePref(this, "inboxDernier", "0");
        analysedKey = "";
    }

    /**
     * Copie les SMS Orange Money et MoMo de la messagerie vers l'application.
     * Seuls les SMS reçus depuis la dernière lecture sont parcourus, et seulement ceux des expéditeurs connus.
     */
    private int importInbox(SmsParser.Config cfg) {
        long since = Store.prefLong(this, "inboxDernier", 0);
        List<String> where = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (since > 0) {
            where.add("date > ?");
            args.add(String.valueOf(since - 60000L));
        }
        String dateOnly = where.isEmpty() ? null : where.get(0);
        String[] dateArgs = args.isEmpty() ? null : args.toArray(new String[0]);
        if (!cfg.auto) {
            List<String> keys = new ArrayList<>();
            for (String x : cfg.expediteurs()) keys.add(SmsParser.senderKey(x));
            if (!keys.isEmpty()) {
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < keys.size(); i++) in.append(i == 0 ? "?" : ",?");
                where.add("LOWER(REPLACE(REPLACE(address, ' ', ''), '-', '')) IN (" + in + ")");
                args.addAll(keys);
            }
        }
        String sel = where.isEmpty() ? null : String.join(" AND ", where);
        String[] selArgs = args.isEmpty() ? null : args.toArray(new String[0]);

        Cursor c = null;
        boolean withSub = true;
        String[] withSubCols = {"address", "body", "date", "sub_id"}, noSubCols = {"address", "body", "date"};
        try {
            c = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, withSubCols, sel, selArgs, "date DESC");
        } catch (Exception e) {
            withSub = false;
        }
        if (c == null) {
            // Certains téléphones refusent la colonne sub_id ou le filtre par expéditeur : on simplifie.
            try {
                c = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, noSubCols, sel, selArgs, "date DESC");
            } catch (Exception e) {
                c = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, noSubCols, dateOnly, dateArgs, "date DESC");
            }
            withSub = false;
        }
        if (c == null) return 0;
        int added = 0, n = 0;
        long newest = since;
        Store store = Store.get(this);
        try {
            while (c.moveToNext() && n++ < 50000) {
                long date = c.getLong(2);
                if (date > newest) newest = date;
                String address = c.getString(0), body = c.getString(1);
                if (body == null) continue;
                if (SmsParser.detectOp(address, body, cfg) == null) continue;
                String sub = withSub && !c.isNull(3) ? c.getString(3) : "";
                if ("-1".equals(sub)) sub = "";
                if (store.insert(new SmsParser.Sms(address, body, date, sub))) added++;
            }
        } finally {
            c.close();
        }
        if (newest > since) Store.savePref(this, "inboxDernier", String.valueOf(newest));
        return added;
    }

    private Soldes.Releve computeSoldes(long from, long to) {
        return Soldes.calculer(result, saisies, jours, from, to, agent(Caisse.OM), agent(Caisse.MOMO));
    }

    private Caisse.Bilan computeBilan(long from, long to) {
        Analyzer.Result r = result != null ? result : new Analyzer.Result();
        return Caisse.bilan(r, classements, regles, saisies, jours, from, to, agent(Caisse.OM), agent(Caisse.MOMO));
    }

    // ---------- Briques d'interface ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout column(int gapDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        GradientDrawable spacer = new GradientDrawable();
        spacer.setSize(1, dp(gapDp));
        spacer.setColor(Color.TRANSPARENT);
        l.setDividerDrawable(spacer);
        return l;
    }

    private LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight(boolean last) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (!last) p.setMarginEnd(dp(6));
        return p;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackground(box(primary ? ACCENT : SURFACE, primary ? ACCENT : LINE, 10));
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setMinHeight(dp(44));
        b.setOnClickListener(l);
        return b;
    }

    private Button small(String label, int color, View.OnClickListener l) {
        Button b = button(label, false, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(color);
        b.setMinHeight(dp(36));
        return b;
    }

    private void select(Button b, boolean on) {
        b.setBackground(box(on ? INK : SURFACE, on ? INK : LINE, 10));
        b.setTextColor(on ? Color.WHITE : INK);
    }

    private TextView chip(String s, int fg, int bg) {
        TextView t = text(s, 12, fg, true);
        t.setBackground(box(bg, 0, 20));
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        return t;
    }

    private View kv(String k, String v, int color, boolean bold) {
        LinearLayout row = new LinearLayout(this);
        row.addView(text(k, 14, bold ? INK : MUTED, bold), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView val = text(v, 14, color, bold);
        val.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : 0);
        row.addView(val);
        return row;
    }

    private View kv(String k, String v) { return kv(k, v, INK, false); }

    private TextView tag(String label, int color) {
        TextView t = text(label, 11, Color.WHITE, true);
        t.setBackground(box(color, 0, 5));
        t.setPadding(dp(6), dp(1), dp(6), dp(1));
        return t;
    }

    private static int opColor(String mode) {
        if (SmsParser.estBanque(mode)) return BANK;
        return Caisse.OM.equals(mode) ? OM : Caisse.MOMO.equals(mode) ? MOMO : CASH;
    }

    private static String opShort(String mode) {
        if (SmsParser.estBanque(mode)) return Txn.opName(mode);
        return Caisse.OM.equals(mode) ? "OM" : Caisse.MOMO.equals(mode) ? "MoMo" : "Espèces";
    }

    private static int catColor(String c) {
        if (c == null) return BAD;
        switch (c) {
            case Caisse.VENTE: return GOOD;
            case Caisse.ACHAT: return INK;
            case Caisse.APPORT: case Caisse.RETRAIT_PERSO: return WARN;
            case Caisse.PRET_RECU: case Caisse.REMB_PRET: case Caisse.PRET_ACCORDE: case Caisse.REMB_RECU: return OM;
            case Caisse.REPARTI: return PURPLE;
            case Caisse.DON: case Caisse.EPARGNE: return PURPLE;
            case Caisse.TRANSFERT: case Caisse.AGENT: return CASH;
            default: return Caisse.estCharge(c) ? INK : MUTED;
        }
    }

    private TextView catChip(String c, boolean auto) {
        int fg = catColor(c);
        TextView t = chip(Caisse.categorieNom(c) + (auto ? " · auto" : ""), fg, c == null ? BAD_BG : BG);
        return t;
    }

    /**
     * Choix de la catégorie : champ « Rechercher ou créer », catégories fréquentes en haut, puis la liste par sections.
     * Si le mot cherché n'existe pas, « + Créer » ouvre la création directe. Met à jour choix[0] et le texte d'aide.
     */
    private void categorieGrid(LinearLayout grille, TextView aide, int sens, String[] choix) {
        categorieGrid(grille, aide, sens, choix, null);
    }

    /** Même sélecteur ; surChange est appelé à chaque changement de catégorie (champ « Personne » d'un prêt…). */
    private void categorieGrid(LinearLayout grille, TextView aide, int sens, String[] choix, Runnable surChange) {
        grille.removeAllViews();
        EditText recherche = new EditText(this);
        recherche.setSingleLine(true);
        recherche.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        recherche.setHint("Rechercher ou créer une catégorie…");
        LinearLayout liste = column(3);
        LinearLayout creation = column(6);
        creation.setVisibility(View.GONE);
        grille.addView(recherche, fill());
        grille.addView(creation, fill());
        grille.addView(liste, fill());
        final Runnable[] remplir = new Runnable[1];
        remplir[0] = () -> {
            liste.removeAllViews();
            aide.setText(Caisse.aide(choix[0]));
            if (surChange != null) surChange.run();
            String q = normNom(recherche.getText().toString());
            List<String> dispo = Caisse.choix(sens);
            if (choix[0] != null && !dispo.contains(choix[0]) && !Caisse.AGENT.equals(choix[0])) dispo.add(0, choix[0]);
            if (!q.isEmpty()) {
                boolean exact = false;
                for (String c : dispo) {
                    String n = normNom(Caisse.categorieNom(c));
                    if (n.equals(q)) exact = true;
                    if (n.contains(q)) liste.addView(itemCategorie(c, choix, remplir[0]), fill());
                }
                // Une catégorie masquée du même nom : on propose de la réafficher plutôt que d'en créer une seconde.
                for (String c : Caisse.CATEGORIES) if (Caisse.masquee(c) && normNom(Caisse.categorieNom(c)).equals(q)) exact = true;
                if (!exact) {
                    String nom = recherche.getText().toString().trim();
                    TextView plus = text("+  Créer la catégorie « " + nom + " »", 14, ACCENT, true);
                    plus.setPadding(dp(12), dp(10), dp(12), dp(10));
                    plus.setBackground(box(GOOD_BG, 0, 10));
                    plus.setOnClickListener(v -> formulaireCreation(creation, nom, sens, code -> {
                        choix[0] = code;
                        recherche.setText("");
                        creation.setVisibility(View.GONE);
                        remplir[0].run();
                    }));
                    liste.addView(plus, fill());
                } else if (liste.getChildCount() == 0) {
                    liste.addView(text("Cette catégorie est masquée : réaffichez-la dans Compte → Catégories.", 12, MUTED, false));
                }
                return;
            }
            List<String> freq = new ArrayList<>();
            for (String c : frequentes(sens)) if (dispo.contains(c)) freq.add(c);
            if (!freq.isEmpty()) {
                liste.addView(label("Fréquentes"));
                for (String c : freq) liste.addView(itemCategorie(c, choix, remplir[0]), fill());
            }
            List<String[]> sections = new ArrayList<>();
            if (sens > 0) {
                sections.add(new String[] {"Entrées", Caisse.VENTE, Caisse.APPORT});
                sections.add(new String[] {"Dettes et créances", Caisse.REMB_RECU, Caisse.PRET_RECU});
            } else {
                sections.add(new String[] {"Sorties", Caisse.ACHAT, Caisse.RETRAIT_PERSO});
                sections.add(new String[] {"Dettes et créances", Caisse.PRET_ACCORDE, Caisse.REMB_PRET});
                List<String> ch = new ArrayList<>();
                ch.add("Charges");
                for (String c : dispo) if (Caisse.estCharge(c)) ch.add(c);
                sections.add(ch.toArray(new String[0]));
            }
            List<String> mes = new ArrayList<>();
            mes.add("Mes catégories");
            for (String c : dispo) if (Caisse.estCree(c) && !Caisse.estCharge(c)) mes.add(c);
            sections.add(mes.toArray(new String[0]));
            sections.add(new String[] {"Entrée ou sortie", Caisse.EPARGNE, Caisse.DON, Caisse.TRANSFERT, Caisse.AUTRE});
            for (String[] sec : sections) {
                List<String> items = new ArrayList<>();
                for (int i = 1; i < sec.length; i++) if (dispo.contains(sec[i])) items.add(sec[i]);
                if (items.isEmpty()) continue;
                liste.addView(label(sec[0]));
                for (String c : items) liste.addView(itemCategorie(c, choix, remplir[0]), fill());
            }
        };
        recherche.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { remplir[0].run(); }
        });
        remplir[0].run();
    }

    private static String normNom(String s) {
        return SmsParser.deaccent(s == null ? "" : s).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private View itemCategorie(String c, String[] choix, Runnable apres) {
        boolean on = c.equals(choix[0]);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(box(on ? GOOD_BG : SURFACE, on ? ACCENT : LINE, 10));
        View dot = new View(this);
        dot.setBackground(box(catColor(c), 0, 5));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.setMarginEnd(dp(10));
        row.addView(dot, dl);
        row.addView(text(Caisse.categorieNom(c), 14, on ? ACCENT : INK, on), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int effet = Caisse.effetResultat(c);
        row.addView(text(on ? "✓" : effet > 0 ? "+" : effet < 0 ? "−" : "", 14, on ? ACCENT : MUTED, true));
        row.setOnClickListener(v -> { choix[0] = c; apres.run(); });
        return row;
    }

    /** Catégories les plus utilisées pour ce sens (classements faits à la main sur la période affichée). */
    private List<String> frequentes(int sens) {
        Map<String, Integer> n = new HashMap<>();
        if (bilan != null) for (Caisse.Ligne l : bilan.lignes) {
            if (l.auto || l.categorie == null || l.sms == null || Caisse.AGENT.equals(l.categorie) || Caisse.REPARTI.equals(l.categorie)) continue;
            if ((l.sms.sens > 0) != (sens > 0)) continue;
            n.put(l.categorie, n.getOrDefault(l.categorie, 0) + 1);
        }
        List<String> out = new ArrayList<>(n.keySet());
        java.util.Collections.sort(out, (a, b) -> n.get(b) - n.get(a));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    /** Boutons rapides de la page À classer : les 3 catégories les plus utilisées, sinon les plus courantes. */
    private List<String> rapides(int sens) {
        List<String> out = new ArrayList<>();
        for (String c : frequentes(sens)) if (Caisse.compatible(c, sens) && !Caisse.masquee(c)) out.add(c);
        for (String c : sens > 0 ? RAPIDES_ENTREE : RAPIDES_SORTIE) {
            if (out.size() >= 3) break;
            if (!out.contains(c) && !Caisse.masquee(c)) out.add(c);
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    /** Formulaire de création d'une catégorie, affiché dans le sélecteur. */
    private void formulaireCreation(LinearLayout host, String nom, int sens, java.util.function.Consumer<String> apres) {
        host.removeAllViews();
        host.setVisibility(View.VISIBLE);
        host.setBackground(box(BG, 0, 12));
        host.setPadding(dp(12), dp(10), dp(12), dp(10));
        host.addView(text("Nouvelle catégorie « " + nom + " »", 14, INK, true));
        final int[] type = {sens > 0 ? 1 : -1};
        final boolean[] lesDeux = {false};
        Button compte = button(sens > 0 ? "Recette (+)" : "Charge (−)", false, null);
        Button hors = button("Hors résultat", false, null);
        LinearLayout r = new LinearLayout(this);
        r.addView(compte, weight(false));
        r.addView(hors, weight(true));
        host.addView(r, fill());
        TextView explic = text("", 12, MUTED, false);
        host.addView(explic);
        CheckBox deux = new CheckBox(this);
        deux.setText("Utilisable aussi pour les " + (sens > 0 ? "sorties" : "entrées"));
        host.addView(deux, fill());
        Runnable paint = () -> {
            select(compte, type[0] != 0);
            select(hors, type[0] == 0);
            deux.setVisibility(type[0] == 0 ? View.VISIBLE : View.GONE);
            explic.setText(type[0] > 0 ? "Compte dans le résultat comme une vente (ex. prestation, commission)."
                    : type[0] < 0 ? "Rangée sous Charges : compte dans le résultat (−) et peut recevoir un budget."
                    : "Ne compte pas dans le résultat (ex. avance, remboursement, cotisation).");
        };
        compte.setOnClickListener(v -> { type[0] = sens > 0 ? 1 : -1; paint.run(); });
        hors.setOnClickListener(v -> { type[0] = 0; paint.run(); });
        deux.setOnCheckedChangeListener((b, on) -> lesDeux[0] = on);
        paint.run();
        host.addView(button("Créer et choisir", true, v -> {
            Caisse.Perso p = new Caisse.Perso();
            p.code = Caisse.nouveauCode(type[0]);
            p.nom = nom.length() > 40 ? nom.substring(0, 40) : nom;
            p.sens = type[0] == 0 ? (lesDeux[0] ? 0 : (sens > 0 ? 1 : -1)) : (sens > 0 ? 1 : -1);
            p.maj = System.currentTimeMillis();
            Caisse.ajouterPerso(p);
            io.execute(() -> { Store.get(this).setCategoriePerso(p); SyncJob.soon(this); });
            apres.accept(p.code);
        }), fill());
    }

    // ----- Onglet Compte : catégories -----

    private View categoriesCard() {
        LinearLayout c = card();
        c.addView(text("Catégories", 16, INK, true));
        c.addView(text("Créez vos propres catégories ou masquez celles que vous n'utilisez pas. Elles sont communes à tous les téléphones du compte.", 12, MUTED, false));
        List<Caisse.Perso> mes = Caisse.creees();
        if (mes.isEmpty()) c.addView(text("Aucune catégorie créée pour l'instant. Astuce : tapez un nouveau nom dans la recherche lors du classement.", 13, MUTED, false));
        for (Caisse.Perso p : mes) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout info = column(1);
            info.addView(text(p.nom == null ? "Catégorie" : p.nom, 14, p.masquee ? MUTED : INK, true));
            info.addView(text(typeCategorie(p.code) + (p.masquee ? " · masquée" : ""), 12, MUTED, false));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(small("Modifier", ACCENT, v -> modifierCategorie(p)));
            c.addView(row, fill());
        }
        c.addView(button("+ Nouvelle catégorie", false, v -> nouvelleCategorie()), fill());
        TextView fournies = text((fourniesOuvert ? "▾" : "▸") + "  Masquer des catégories fournies", 14, ACCENT, true);
        fournies.setPadding(0, dp(8), 0, dp(4));
        fournies.setOnClickListener(v -> { fourniesOuvert = !fourniesOuvert; render(); });
        c.addView(fournies, fill());
        if (fourniesOuvert) {
            c.addView(text("Décochez celles que vous n'utilisez jamais : elles disparaissent des choix, l'historique reste intact. Autre et Opération client restent toujours disponibles.", 12, MUTED, false));
            for (String code : Caisse.CATEGORIES) {
                if (!Caisse.masquable(code)) continue;
                CheckBox cb = new CheckBox(this);
                cb.setText(Caisse.categorieNom(code) + " · " + Caisse.typeNom(code).toLowerCase(Locale.FRANCE));
                cb.setChecked(!Caisse.masquee(code));
                cb.setOnCheckedChangeListener((b, visible) -> {
                    Caisse.Perso p = new Caisse.Perso();
                    p.code = code;
                    p.masquee = !visible;
                    p.maj = System.currentTimeMillis();
                    Caisse.ajouterPerso(p);
                    io.execute(() -> { Store.get(this).setCategoriePerso(p); SyncJob.soon(this); });
                });
                c.addView(cb, fill());
            }
        }
        return c;
    }

    private static String typeCategorie(String code) {
        if (code.startsWith(Caisse.PREFIXE_RECETTE)) return "Entrée · recette (+)";
        if (code.startsWith(Caisse.PREFIXE_CHARGE)) return "Sortie · charge (−), budget possible";
        int s = Caisse.sensDe(code);
        return (s > 0 ? "Entrée" : s < 0 ? "Sortie" : "Entrée ou sortie") + " · hors résultat";
    }

    private void nouvelleCategorie() {
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText nom = new EditText(this);
        nom.setSingleLine(true);
        nom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nom.setHint("Nom, ex. Commission, Publicité, Cotisation");
        form.addView(nom, fill());
        String[] types = {"Recette (+)", "Charge (−)", "Hors résultat"};
        String[] sensNoms = {"Entrées", "Sorties", "Les deux"};
        final int[] type = {0}, sens = {2};
        Button[] tb = new Button[3], sb = new Button[3];
        LinearLayout tr = new LinearLayout(this), sr = new LinearLayout(this);
        TextView explic = text("", 12, MUTED, false);
        TextView sensTitre = text("Pour quelles opérations ?", 13, MUTED, false);
        Runnable[] paint = new Runnable[1];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            tb[i] = button(types[i], false, v -> { type[0] = k; paint[0].run(); });
            tb[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tr.addView(tb[i], weight(i == 2));
            sb[i] = button(sensNoms[i], false, v -> { sens[0] = k; paint[0].run(); });
            sb[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sr.addView(sb[i], weight(i == 2));
        }
        paint[0] = () -> {
            for (int i = 0; i < 3; i++) { select(tb[i], i == type[0]); select(sb[i], i == sens[0]); }
            sensTitre.setVisibility(type[0] == 2 ? View.VISIBLE : View.GONE);
            sr.setVisibility(type[0] == 2 ? View.VISIBLE : View.GONE);
            explic.setText(type[0] == 0 ? "Pour les entrées : compte dans le résultat comme une vente."
                    : type[0] == 1 ? "Pour les sorties : rangée sous Charges, compte dans le résultat (−), budget possible."
                    : "Ne compte pas dans le résultat.");
        };
        form.addView(tr, fill());
        form.addView(explic);
        form.addView(sensTitre);
        form.addView(sr, fill());
        paint[0].run();
        new AlertDialog.Builder(this)
                .setTitle("Nouvelle catégorie")
                .setView(form)
                .setPositiveButton("Créer", (d, w) -> {
                    String n = nom.getText().toString().trim();
                    if (n.isEmpty()) { notice("Donnez un nom à la catégorie.", 2); return; }
                    for (String c : Caisse.CATEGORIES) if (normNom(Caisse.categorieNom(c)).equals(normNom(n))) { notice("« " + n + " » existe déjà.", 2); return; }
                    for (Caisse.Perso x : Caisse.creees()) if (x.nom != null && normNom(x.nom).equals(normNom(n))) { notice("« " + n + " » existe déjà.", 2); return; }
                    Caisse.Perso p = new Caisse.Perso();
                    p.code = Caisse.nouveauCode(type[0] == 0 ? 1 : type[0] == 1 ? -1 : 0);
                    p.nom = n.length() > 40 ? n.substring(0, 40) : n;
                    p.sens = type[0] == 0 ? 1 : type[0] == 1 ? -1 : (sens[0] == 0 ? 1 : sens[0] == 1 ? -1 : 0);
                    p.maj = System.currentTimeMillis();
                    Caisse.ajouterPerso(p);
                    io.execute(() -> {
                        Store.get(this).setCategoriePerso(p);
                        SyncJob.soon(this);
                        runOnUiThread(() -> { notice("Catégorie « " + p.nom + " » créée.", 1); render(); });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void modifierCategorie(Caisse.Perso p) {
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText nom = new EditText(this);
        nom.setSingleLine(true);
        nom.setText(p.nom);
        form.addView(nom, fill());
        form.addView(text(typeCategorie(p.code) + ". Le type ne change pas : créez une autre catégorie si besoin.", 12, MUTED, false));
        CheckBox masquer = new CheckBox(this);
        masquer.setText("Masquer (n'apparaît plus dans les choix ; l'historique reste)");
        masquer.setChecked(p.masquee);
        form.addView(masquer, fill());
        new AlertDialog.Builder(this)
                .setTitle("Modifier la catégorie")
                .setView(form)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String n = nom.getText().toString().trim();
                    if (n.isEmpty()) { notice("Le nom ne peut pas être vide.", 2); return; }
                    Caisse.Perso q = new Caisse.Perso();
                    q.code = p.code;
                    q.nom = n.length() > 40 ? n.substring(0, 40) : n;
                    q.sens = p.sens;
                    q.masquee = masquer.isChecked();
                    q.maj = System.currentTimeMillis();
                    Caisse.ajouterPerso(q);
                    io.execute(() -> {
                        Store.get(this).setCategoriePerso(q);
                        SyncJob.soon(this);
                        runOnUiThread(() -> { notice("Catégorie enregistrée.", 1); render(); });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private TextView empty(String s) {
        TextView t = text(s, 14, MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(20), 0, dp(20));
        return t;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return line;
    }

    private static String when(long ts) {
        if (ts <= 0) return "date inconnue";
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date(ts));
    }

    private static String ecartText(Long e) {
        return e == null ? "Écart non calculable" : Math.abs(e) <= Analyzer.TOL ? "Écart nul" : "Écart " + (e > 0 ? "+" : "") + SmsParser.fmt(e) + " FCFA";
    }

    private TextView ecartChip(Long e) {
        if (e == null) return chip(ecartText(null), MUTED, BG);
        return Math.abs(e) <= Analyzer.TOL ? chip(ecartText(e), GOOD, GOOD_BG) : chip(ecartText(e), BAD, BAD_BG);
    }

    private TextView big(String s) {
        TextView t = text(s, 26, INK, true);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return t;
    }

    private EditText amountField(String hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint(hint);
        return e;
    }


    // ---------- Construction ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        // En-tête : nom, état de lecture, actualiser, période.
        LinearLayout header = column(10);
        header.setBackgroundColor(SURFACE);
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = column(1);
        titles.addView(text("Carnet Mobile Money", 19, INK, true));
        status = text("", 12, MUTED, false);
        titles.addView(status);
        titleRow.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView refresh = text("↻", 22, ACCENT, true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(box(GOOD_BG, 0, 22));
        refresh.setContentDescription("Actualiser");
        refresh.setOnClickListener(v -> reload(true));
        titleRow.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(titleRow, fill());

        periodScroll = new HorizontalScrollView(this);
        periodScroll.setHorizontalScrollBarEnabled(false);
        periodBar = new LinearLayout(this);
        for (int i = 0; i <= PERIODS.length; i++) {
            final int p = i;
            periodChips[i] = text(i < PERIODS.length ? PERIODS[i] : "Dates…", 14, INK, false);
            periodChips[i].setGravity(Gravity.CENTER);
            periodChips[i].setPadding(dp(14), dp(8), dp(14), dp(8));
            periodChips[i].setOnClickListener(v -> { if (p == CUSTOM) pickRange(); else setPeriod(p); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            periodBar.addView(periodChips[i], lp);
        }
        periodScroll.addView(periodBar);
        header.addView(periodScroll, fill());
        root.addView(header, fill());
        root.addView(divider());

        // Bandeau de message intégré à l'écran (remplace les messages flottants).
        noticeText = text("", 14, INK, false);
        noticeText.setPadding(dp(14), dp(12), dp(14), dp(12));
        noticeText.setVisibility(View.GONE);
        noticeText.setOnClickListener(v -> noticeText.setVisibility(View.GONE));
        LinearLayout.LayoutParams np = fill();
        np.setMargins(dp(16), dp(10), dp(16), 0);
        root.addView(noticeText, np);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = column(12);
        page.setPadding(dp(16), dp(14), dp(16), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Barre de navigation fixe.
        root.addView(divider());
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(SURFACE);
        nav.setPadding(0, dp(6), 0, dp(8));
        String[] labels = {"Caisse", "À classer", "Finances", "Exporter", "Compte"};
        int[] icons = {R.drawable.ic_nav_caisse, R.drawable.ic_nav_classer, R.drawable.ic_nav_finances, R.drawable.ic_nav_export, R.drawable.ic_nav_compte};
        for (int i = 0; i < NB_PAGES; i++) {
            final int p = i;
            LinearLayout item = column(2);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout iconBox = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(getDrawable(icons[i]).mutate());
            iconBox.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            if (i == PAGE_CLASSER) {
                classerBadge = text("", 10, Color.WHITE, true);
                classerBadge.setGravity(Gravity.CENTER);
                classerBadge.setMinWidth(dp(18));
                classerBadge.setPadding(dp(4), 0, dp(4), 0);
                classerBadge.setBackground(box(BAD, 0, 9));
                iconBox.addView(classerBadge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP | Gravity.END));
            }
            item.addView(iconBox, new LinearLayout.LayoutParams(dp(60), dp(30)));
            TextView label = text(labels[i], 12, MUTED, false);
            label.setGravity(Gravity.CENTER);
            item.addView(label, fill());
            item.setOnClickListener(v -> showPage(p));
            navItems[i] = item;
            navIconBoxes[i] = iconBox;
            navIcons[i] = icon;
            navLabels[i] = label;
            nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(nav, fill());

        permBox = column(10);
        permBox.setBackground(box(WARN_BG, 0, 14));
        permBox.setPadding(dp(16), dp(16), dp(16), dp(16));
        permBox.addView(text("Autorisation nécessaire", 16, INK, true));
        permBox.addView(text("L'application lit uniquement les SMS des expéditeurs Orange Money et MTN MoMo. Les autres SMS ne sont ni lus ni enregistrés.", 14, INK, false));
        permBox.addView(button("Autoriser la lecture des SMS", true, v -> askPermissions()), fill());
        permBox.addView(text("Si l'autorisation est grisée ou refusée : ouvrez les paramètres de l'application, touchez le menu ⋮ en haut à droite, choisissez « Autoriser les paramètres restreints », puis activez SMS dans Autorisations.", 13, MUTED, false));
        permBox.addView(button("Ouvrir les paramètres de l'application", false, v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))), fill());
    }

    private void showPage(int p) {
        fermerDetail();
        pageIdx = p;
        render();
        scroll.scrollTo(0, 0);
    }

    /** Message dans le bandeau : 0 information, 1 réussite, 2 problème. Touchez-le pour le fermer. */
    private void notice(String msg, int niveau) {
        noticeText.setText(msg);
        noticeText.setTextColor(niveau == 2 ? BAD : niveau == 1 ? GOOD : INK);
        noticeText.setBackground(box(niveau == 2 ? BAD_BG : niveau == 1 ? GOOD_BG : SURFACE, niveau == 0 ? LINE : 0, 12));
        noticeText.setVisibility(View.VISIBLE);
        noticeText.removeCallbacks(hideNotice);
        noticeText.postDelayed(hideNotice, niveau == 2 ? 12000 : 6000);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    /** Retire une vue de son ancien parent pour pouvoir la replacer (champs de saisie conservés). */
    private <T extends View> T detach(T v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        return v;
    }

    // ---------- Affichage ----------

    /** Affiche la page ; une erreur d'affichage est montrée et signalée au lieu de fermer l'application. */
    private void render() {
        if (!uiPrete) return;
        try {
            renderPage();
        } catch (RuntimeException e) {
            Crash.enregistrer(this, Thread.currentThread(), e, false);
            io.execute(() -> Cloud.envoyerPlantages(this));
            page.removeAllViews();
            LinearLayout c = column(8);
            c.setBackground(box(BAD_BG, 0, 14));
            c.setPadding(dp(16), dp(14), dp(16), dp(14));
            c.addView(text("Cette page n'a pas pu s'afficher", 16, BAD, true));
            c.addView(text("Le détail a été envoyé au support. Les autres onglets restent utilisables.", 13, INK, false));
            StringBuilder sb = new StringBuilder(e.toString());
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) sb.append("\n  at ").append(st[i]);
            TextView t = text(sb.toString(), 11, INK, false);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextIsSelectable(true);
            c.addView(t, fill());
            page.addView(c, fill());
        }
    }

    private void renderPage() {
        periodScroll.setVisibility(pageIdx == PAGE_COMPTE || pageIdx == PAGE_FINANCES ? View.GONE : View.VISIBLE);
        for (int i = 0; i < periodChips.length; i++) {
            boolean on = i == period;
            periodChips[i].setBackground(box(on ? INK : SURFACE, on ? INK : LINE, 18));
            periodChips[i].setTextColor(on ? Color.WHITE : INK);
        }
        if (period == CUSTOM) {
            SimpleDateFormat f = new SimpleDateFormat("dd/MM", Locale.FRANCE);
            periodChips[CUSTOM].setText(f.format(new Date(customFrom)) + " – " + f.format(new Date(customTo)));
        } else {
            periodChips[CUSTOM].setText("Dates…");
        }
        for (int i = 0; i < NB_PAGES; i++) {
            boolean on = i == pageIdx;
            navIcons[i].getDrawable().setTint(on ? ACCENT : MUTED);
            navIconBoxes[i].setBackground(on ? box(GOOD_BG, 0, 15) : null);
            navLabels[i].setTextColor(on ? ACCENT : MUTED);
            navLabels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }

        page.removeAllViews();
        long[] range = range();
        bilan = result == null ? null : computeBilan(range[0], range[1]);
        releve = result == null ? null : computeSoldes(range[0], range[1]);
        int ac = bilan == null ? 0 : bilan.aClasser.size();
        classerBadge.setText(ac > 99 ? "99+" : String.valueOf(ac));
        classerBadge.setVisibility(ac > 0 ? View.VISIBLE : View.GONE);

        if (choixModules) { choixModulesPage(); return; }
        switch (pageIdx) {
            case PAGE_CLASSER: classerPage(); break;
            case PAGE_EXPORT: exportPage(); break;
            case PAGE_COMPTE: if (applicationsOuvert) applicationsPage(); else comptePage(); break;
            case PAGE_FINANCES:
                if (assistantOuvert) assistantPage();
                else if (analyseOuvert) analysePage();
                else if (projetOuvert != null) projetPage();
                else if (suiviOuvert != null) suiviPage();
                else if (personneOuverte != null) personnePage();
                else if (categorieOuverte != null) categoriePage();
                else financesPage();
                break;
            default: caissePage(range);
        }
    }

    private TextView pageTitle(String title, String subtitle) {
        TextView t = text(title, 22, INK, true);
        page.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) page.addView(text(subtitle, 13, MUTED, false));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = column(8);
        c.setBackground(box(SURFACE, LINE, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private LinearLayout listCard() {
        LinearLayout l = column(0);
        l.setBackground(box(SURFACE, LINE, 16));
        l.setPadding(dp(14), dp(4), dp(14), dp(4));
        return l;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(Locale.FRANCE), 11, MUTED, true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    /** Choix exclusif en pastilles (Journal / Espèces / À vérifier, OM / MoMo…). */
    private LinearLayout segmented(String[] options, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(box(SURFACE, LINE, 14));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < options.length; i++) {
            final int k = i;
            TextView o = text(options[i], 13, i == selected ? Color.WHITE : INK, i == selected);
            o.setGravity(Gravity.CENTER);
            o.setPadding(dp(6), dp(9), dp(6), dp(9));
            if (i == selected) o.setBackground(box(INK, 0, 11));
            o.setOnClickListener(v -> onPick.accept(k));
            seg.addView(o, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return seg;
    }

    // ----- Page Caisse -----

    private void caissePage(long[] range) {
        if (!hasSmsPermission()) page.addView(detach(permBox), fill());
        if (Cloud.caisseAChoisir(this)) {
            LinearLayout w = column(6);
            w.setBackground(box(WARN_BG, 0, 14));
            w.setPadding(dp(16), dp(14), dp(16), dp(14));
            w.addView(text("Choisissez la caisse de ce téléphone", 15, WARN, true));
            w.addView(text("Ce compte est déjà utilisé sur un autre téléphone. Tant que ce choix n'est pas fait, rien n'est sauvegardé depuis ce téléphone.", 13, INK, false));
            w.addView(button("Choisir maintenant", true, v -> showPage(PAGE_COMPTE)), fill());
            page.addView(w, fill());
        }
        if (plusieursCaisses()) {
            String nom = Cloud.caisseNom(this);
            if (nom.length() > 18) nom = nom.substring(0, 17) + "…";
            page.addView(segmented(new String[] {nom.isEmpty() ? "Ce téléphone" : nom, "Toutes mes caisses"}, vueToutes ? 1 : 0,
                    k -> { vueToutes = k == 1; render(); }), fill());
            if (vueToutes) {
                toutesView();
                return;
            }
        }
        if (bilan == null) {
            page.addView(empty("Ouverture de votre caisse…"));
            return;
        }
        View fx = bandeauFauxSms();
        if (fx != null) page.addView(fx, fill());
        View bm = bandeauModules();
        if (bm != null) page.addView(bm, fill());
        page.addView(heroCard(bilan), fill());

        if (!bilan.comptes.isEmpty()) {
            if (bilan.comptes.size() <= 2) {
                LinearLayout row = new LinearLayout(this);
                for (int i = 0; i < bilan.comptes.size(); i++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                    if (i < bilan.comptes.size() - 1) lp.setMarginEnd(dp(10));
                    row.addView(compteCard(bilan.comptes.get(i)), lp);
                }
                page.addView(row, fill());
            } else {
                for (Caisse.Compte c : bilan.comptes) page.addView(compteCard(c), fill());
            }
        }
        page.addView(especesCard(bilan.especes), fill());
        if ((agent(Caisse.OM) || agent(Caisse.MOMO)) && result != null) page.addView(floatCard(), fill());
        if (releve != null && !releve.comptes.isEmpty()) page.addView(soldesPeriodeCard(releve, periodeTexte()), fill());

        List<Txn> unknown = new ArrayList<>();
        for (Txn u : result.unknown) if (u.smsDate >= range[0] && u.smsDate <= range[1]) unknown.add(u);
        page.addView(segmented(new String[] {"Journal · " + bilan.lignes.size(), "Espèces · " + bilan.mouvementsEspeces.size(),
                "À vérifier · " + unknown.size()}, liste, k -> { liste = k; render(); }), fill());

        LinearLayout list = listCard();
        switch (liste) {
            case LISTE_ESPECES:
                if (bilan.mouvementsEspeces.isEmpty()) list.addView(empty("Aucun mouvement d'espèces sur la période. Touchez « + / − » dans la carte Espèces pour en ajouter un."));
                for (Caisse.Saisie s : bilan.mouvementsEspeces) list.addView(especeRow(s), fill());
                break;
            case LISTE_CHECK:
                if (unknown.isEmpty()) list.addView(empty("Rien à vérifier sur la période."));
                else list.addView(text("SMS mobile money que l'application n'a pas su lire. Ils ne comptent pas dans la caisse.", 12, MUTED, false));
                for (Txn u : unknown) list.addView(unknownRow(u), fill());
                break;
            default: {
                if (bilan.lignes.isEmpty()) list.addView(empty("Aucune opération sur la période."));
                int n = 0;
                for (Caisse.Ligne l : bilan.lignes) {
                    if (n++ >= MAX_ROWS) { list.addView(empty("Seules les " + MAX_ROWS + " dernières opérations sont affichées. L'export Excel contient tout.")); break; }
                    list.addView(ligneRow(l, false), fill());
                }
                if (!bilan.depensesSuivies.isEmpty()) {
                    list.addView(label("Dépenses payées avec un argent suivi"));
                    for (Caisse.Saisie s : bilan.depensesSuivies) if (!s.annule) list.addView(depenseSuivieRow(s), fill());
                }
            }
        }
        page.addView(list, fill());
        page.addView(text("Les montants viennent des SMS de l'opérateur et ne se modifient pas. Touchez une opération pour changer sa catégorie ou son motif.", 12, MUTED, false));
    }

    private View heroCard(Caisse.Bilan b) {
        LinearLayout card = column(6);
        card.setBackground(box(ACCENT, 0, 18));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView lab = text(("Résultat · " + periodLabel()).toUpperCase(Locale.FRANCE), 11, ON_ACCENT_SOFT, true);
        lab.setLetterSpacing(0.05f);
        card.addView(lab);
        TextView res = text((b.resultat >= 0 ? "" : "−") + SmsParser.fmt(Math.abs(b.resultat)) + " FCFA", 30, Color.WHITE, true);
        res.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(res);
        card.addView(text("ventes − achats − charges − frais opérateur", 12, ON_ACCENT_SOFT, false));

        long ventes = b.recettes();
        long depenses = b.total(Caisse.ACHAT).sorties + b.charges();
        long apports = b.total(Caisse.APPORT).entrees, retraits = b.total(Caisse.RETRAIT_PERSO).sorties;
        LinearLayout stats = new LinearLayout(this);
        stats.setPadding(0, dp(8), 0, dp(2));
        String[][] cols = {{"Ventes", "+" + SmsParser.fmt(ventes)}, {"Achats + charges", "−" + SmsParser.fmt(depenses)}, {"Frais", "−" + SmsParser.fmt(b.frais)}};
        for (int i = 0; i < cols.length; i++) {
            LinearLayout c = column(1);
            c.addView(text(cols[i][0], 12, ON_ACCENT_SOFT, false));
            TextView val = text(cols[i][1], 15, Color.WHITE, true);
            val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            c.addView(val);
            stats.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        card.addView(stats, fill());
        if (apports > 0 || retraits > 0) {
            card.addView(text("Hors résultat : apports perso +" + SmsParser.fmt(apports) + " · retraits perso −" + SmsParser.fmt(retraits),
                    12, ON_ACCENT_SOFT, false));
        }
        TextView state;
        if (!b.aClasser.isEmpty()) {
            state = text("⚠  " + b.aClasser.size() + " opération(s) à classer : résultat provisoire  ›", 13, BAD, true);
            state.setBackground(box(Color.WHITE, 0, 10));
            state.setOnClickListener(x -> showPage(PAGE_CLASSER));
        } else if (!b.lignes.isEmpty()) {
            state = text("✓  Tout est classé", 13, Color.WHITE, true);
            state.setBackground(box(GOOD, 0, 10));
        } else {
            return card;
        }
        state.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams sp = fill();
        sp.setMargins(0, dp(6), 0, 0);
        card.addView(state, sp);
        return card;
    }

    /** Compte OM ou MoMo, compact ; touchez pour voir le détail. */
    private View compteCard(Caisse.Compte c) {
        boolean ouvert = comptesOuverts.contains(c.op);
        LinearLayout card = column(4);
        card.setBackground(box(SURFACE, LINE, 16));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(box(opColor(c.op), 0, 5));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.setMarginEnd(dp(6));
        head.addView(dot, dl);
        head.addView(text(opShort(c.op) + (c.agent ? " · agent" : ""), 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(ouvert ? "▴" : "▾", 14, MUTED, false));
        card.addView(head, fill());
        card.addView(label("Solde réel"));
        TextView reel = text(SmsParser.fmt(c.reel), 20, INK, true);
        reel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(reel);
        card.addView(text("Théorique " + SmsParser.fmt(c.theo), 12, MUTED, false));
        LinearLayout foot = new LinearLayout(this);
        foot.addView(ecartChip(c.ecart));
        card.addView(foot);
        if (c.alertesSms > 0) card.addView(text(c.alertesSms + " SMS manquant(s)", 12, BAD, true));
        if (ouvert) {
            card.addView(divider());
            card.addView(kvSmall("Ouverture", SmsParser.fmt(c.ouverture)));
            card.addView(kvSmall("Entrées", "+" + SmsParser.fmt(c.entrees)));
            card.addView(kvSmall("Sorties", "−" + SmsParser.fmt(c.sorties)));
            card.addView(kvSmall("Frais", "−" + SmsParser.fmt(c.frais)));
            if (c.sms != null) card.addView(kvSmall("Opérations", String.valueOf(c.sms.count)));
        }
        card.setOnClickListener(v -> { if (!comptesOuverts.remove(c.op)) comptesOuverts.add(c.op); render(); });
        return card;
    }

    private View kvSmall(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.addView(text(k, 12, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView val = text(v, 12, INK, false);
        val.setTypeface(Typeface.MONOSPACE);
        row.addView(val);
        return row;
    }

    /** Soldes de la période : chaque compte au début et à la fin, et le total (version 0.15). */
    private View soldesPeriodeCard(Soldes.Releve rv, String titre) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Soldes · " + titre, 15, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(head, fill());
        LinearLayout th = new LinearLayout(this);
        th.addView(label(""), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        TextView d = label("Début");
        d.setGravity(Gravity.END);
        th.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView f = label("Fin");
        f.setGravity(Gravity.END);
        th.addView(f, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(th, fill());
        for (String k : rv.comptes) card.addView(ligneSolde(Soldes.court(k), rv.ouverture.get(k), rv.cloture.get(k), false), fill());
        card.addView(divider());
        card.addView(ligneSolde("Total", rv.totalOuverture, rv.totalCloture, true), fill());
        Long v = rv.variationTotale();
        if (v != null) card.addView(text((v >= 0 ? "Hausse de " : "Baisse de ") + SmsParser.fmt(Math.abs(v)) + " FCFA sur la période", 12, v >= 0 ? GOOD : BAD, true));
        card.addView(text("Le solde de chaque compte et le total après chaque opération sont dans le journal ci-dessous et dans l'export Excel.", 11, MUTED, false));
        return card;
    }

    private View ligneSolde(String nom, Long debut, Long fin, boolean gras) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(text(nom, 14, INK, gras), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        for (Long v : new Long[] {debut, fin}) {
            TextView t = text(v == null ? "—" : SmsParser.fmt(v), 14, v != null && v < 0 ? BAD : INK, gras);
            t.setTypeface(Typeface.MONOSPACE, gras ? Typeface.BOLD : Typeface.NORMAL);
            t.setGravity(Gravity.END);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    private View especesCard(Caisse.Especes e) {
        if (acces(Modules.CAISSE) == Modules.Acces.ABSENT)
            return promoModule(Modules.CAISSE, "Comptez vos espèces : fond de caisse, entrées et sorties en espèces, clôture avec écart réel / théorique, mode agent OM et MoMo.");
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(box(CASH, 0, 5));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.setMarginEnd(dp(6));
        head.addView(dot, dl);
        head.addView(text("Espèces", 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (e.ouverture == null) head.addView(chip("Fond non saisi", WARN, WARN_BG));
        else if (e.compte == null) head.addView(chip("En cours", WARN, WARN_BG));
        else head.addView(ecartChip(e.ecart));
        card.addView(head, fill());
        card.addView(label(e.compte != null ? "Montant compté" : "Montant théorique"));
        TextView amt = text(SmsParser.fmt(e.compte != null ? e.compte : e.theo) + " FCFA", 22, INK, true);
        amt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(amt);
        card.addView(kvSmall("Ouverture", SmsParser.fmt(e.ouverture)));
        if (!e.origineOuverture.isEmpty()) card.addView(text(e.origineOuverture, 11, MUTED, false));
        card.addView(kvSmall("Entrées / sorties saisies", "+" + SmsParser.fmt(e.entrees) + " / −" + SmsParser.fmt(e.sorties)));
        if (agent(Caisse.OM) || agent(Caisse.MOMO)) {
            card.addView(kvSmall("Dépôts / retraits clients", "+" + SmsParser.fmt(e.depotsClients) + " / −" + SmsParser.fmt(e.retraitsClients)));
        }
        if (e.compte != null) card.addView(kvSmall("Théorique", SmsParser.fmt(e.theo)));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(4), 0, 0);
        actions.addView(small("Fond de caisse", INK, v -> openFond()), weight(false));
        actions.addView(small("+ / − espèces", INK, v -> openEspeces()), weight(false));
        actions.addView(small("Clôturer", ACCENT, v -> openCloture()), weight(true));
        if (actif(Modules.CAISSE)) card.addView(actions, fill()); else card.addView(bandeauLecture(Modules.CAISSE), fill());
        return card;
    }

    // ----- Plusieurs téléphones : Toutes mes caisses -----

    private boolean plusieursCaisses() {
        return Cloud.configured() && Cloud.loggedIn(this) && !Cloud.caisseId(this).isEmpty() && Cloud.caisses(this).liste.size() >= 2;
    }

    /** Période choisie en haut, en jours AAAA-MM-JJ (bornes incluses). */
    private String[] joursPeriode() {
        long[] r = range();
        String debut = r[0] <= 0 ? "0000-01-01" : Caisse.jourDe(r[0]);
        String fin = r[1] >= Long.MAX_VALUE / 2 ? "9999-12-31" : Caisse.jourDe(r[1]);
        return new String[] {debut, fin};
    }

    private String cleVue() {
        String[] p = joursPeriode();
        return p[0] + "|" + p[1];
    }

    /** Vrai si la vue de toutes les caisses est chargée pour la période affichée. */
    private boolean vuePrete() {
        return vueData != null && vueKey.equals(cleVue());
    }

    private long sommeVue(String champ) {
        long t = 0;
        org.json.JSONArray a = vueData == null ? null : vueData.optJSONArray("caisses");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject c = a.optJSONObject(i);
            if (c != null) t += c.optLong(champ, 0);
        }
        return t;
    }

    private int nbToutes() {
        return (int) sommeVue("nb_operations");
    }

    private static String ilYa(long ms) {
        if (ms <= 0) return "jamais";
        long min = Math.max(1, (System.currentTimeMillis() - ms) / 60000);
        if (min < 60) return "il y a " + min + " min";
        if (min < 48 * 60) return "il y a " + (min / 60) + " h";
        return "il y a " + (min / 1440) + " j";
    }

    private void chargerVue(String code, boolean saisi) {
        String[] p = joursPeriode();
        String key = p[0] + "|" + p[1];
        vueKeyTentee = key;
        vueChargement = true;
        vueErreur = null;
        io.execute(() -> {
            org.json.JSONObject r = null;
            String err = null;
            try {
                r = Cloud.vueCaisses(this, code, p[0], p[1]);
            } catch (Cloud.CloudException e) {
                err = e.getMessage();
            }
            final org.json.JSONObject res = r;
            final String e2 = err;
            runOnUiThread(() -> {
                vueChargement = false;
                if (res != null) {
                    vueData = res;
                    vueKey = key;
                    codePatron = code;
                    if (saisi && codeVueField != null) codeVueField.setText("");
                } else {
                    vueErreur = e2;
                }
                render();
            });
        });
    }

    private void toutesView() {
        Cloud.Caisses cs = Cloud.caisses(this);
        boolean libre = !cs.codeDefini;
        if (codePatron == null && !libre) {
            LinearLayout c = card();
            c.addView(text("Toutes mes caisses", 16, INK, true));
            c.addView(text("Saisissez le code patron pour voir les chiffres de tous les téléphones du compte.", 13, MUTED, false));
            if (codeVueField == null) {
                codeVueField = new EditText(this);
                codeVueField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                codeVueField.setHint("Code patron (4 à 6 chiffres)");
            }
            c.addView(detach(codeVueField), fill());
            if (vueErreur != null) c.addView(text(vueErreur, 13, BAD, true));
            c.addView(button(vueChargement ? "Vérification…" : "Afficher toutes mes caisses", true, v -> {
                String code = codeVueField.getText().toString().trim();
                if (!code.matches("\\d{4,6}")) { notice("Le code patron fait 4 à 6 chiffres.", 2); return; }
                chargerVue(code, true);
                render();
            }), fill());
            page.addView(c, fill());
            return;
        }
        String code = codePatron != null ? codePatron : "";
        String key = cleVue();
        if (!vueChargement && !key.equals(vueKeyTentee)) {
            // Chargement automatique quand la période change (après l'affichage, pas pendant).
            page.post(() -> { chargerVue(code, false); render(); });
        }
        if (vueErreur != null) {
            LinearLayout e = card();
            e.addView(text(vueErreur, 13, BAD, true));
            e.addView(button("Réessayer", false, v -> { vueKeyTentee = ""; vueErreur = null; render(); }), fill());
            page.addView(e, fill());
        }
        if (!vuePrete()) {
            if (vueErreur == null) page.addView(empty("Chargement de toutes vos caisses…"));
            return;
        }
        org.json.JSONArray a = vueData.optJSONArray("caisses");
        int n = a == null ? 0 : a.length();

        // Total de toutes les caisses.
        LinearLayout hero = column(6);
        hero.setBackground(box(ACCENT, 0, 18));
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView lab = text(("Toutes mes caisses · " + periodLabel()).toUpperCase(Locale.FRANCE), 11, ON_ACCENT_SOFT, true);
        lab.setLetterSpacing(0.05f);
        hero.addView(lab);
        long res = sommeVue("resultat");
        TextView rt = text((res >= 0 ? "" : "−") + SmsParser.fmt(Math.abs(res)) + " FCFA", 30, Color.WHITE, true);
        rt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        hero.addView(rt);
        hero.addView(text(n + " caisses · " + nbToutes() + " opérations · ventes − achats − charges − frais", 12, ON_ACCENT_SOFT, false));
        long tresoTotale = sommeVue("tresorerie");
        if (tresoTotale != 0) hero.addView(text("Trésorerie totale connue : " + SmsParser.fmt(tresoTotale) + " FCFA", 13, Color.WHITE, true));
        LinearLayout stats = new LinearLayout(this);
        stats.setPadding(0, dp(8), 0, dp(2));
        String[][] cols = {{"Ventes", "+" + SmsParser.fmt(sommeVue("ventes"))},
                {"Achats + charges", "−" + SmsParser.fmt(sommeVue("achats") + sommeVue("charges"))}, {"Frais", "−" + SmsParser.fmt(sommeVue("frais"))}};
        for (String[] col : cols) {
            LinearLayout c = column(1);
            c.addView(text(col[0], 12, ON_ACCENT_SOFT, false));
            TextView val = text(col[1], 15, Color.WHITE, true);
            val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            c.addView(val);
            stats.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        hero.addView(stats, fill());
        long ac = sommeVue("a_classer");
        if (ac > 0) {
            TextView st = text("⚠  " + ac + " opération(s) à classer sur l'ensemble des téléphones : résultat provisoire", 13, BAD, true);
            st.setBackground(box(Color.WHITE, 0, 10));
            st.setPadding(dp(12), dp(9), dp(12), dp(9));
            hero.addView(st, fill());
        }
        page.addView(hero, fill());

        // Une carte par caisse.
        String moi = Cloud.caisseId(this);
        for (int i = 0; i < n; i++) {
            org.json.JSONObject c = a.optJSONObject(i);
            if (c == null) continue;
            LinearLayout k = card();
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(c.optString("nom", "Caisse"), 15, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (moi.equals(c.optString("id", ""))) head.addView(chip("ce téléphone", ACCENT, GOOD_BG));
            k.addView(head, fill());
            long r = c.optLong("resultat", 0);
            TextView rv = text((r >= 0 ? "" : "−") + SmsParser.fmt(Math.abs(r)) + " FCFA", 20, r >= 0 ? INK : BAD, true);
            rv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            k.addView(rv);
            k.addView(kvSmall("Ventes / achats + charges", "+" + SmsParser.fmt(c.optLong("ventes", 0)) + " / −"
                    + SmsParser.fmt(c.optLong("achats", 0) + c.optLong("charges", 0))));
            k.addView(kvSmall("Frais opérateur", "−" + SmsParser.fmt(c.optLong("frais", 0))));
            k.addView(kvSmall("Opérations", String.valueOf(c.optLong("nb_operations", 0))));
            String soldes = (c.isNull("solde_om") ? "" : "OM " + SmsParser.fmt(c.optLong("solde_om", 0)))
                    + (c.isNull("solde_momo") ? "" : (c.isNull("solde_om") ? "" : " · ") + "MoMo " + SmsParser.fmt(c.optLong("solde_momo", 0)));
            if (!soldes.isEmpty()) k.addView(kvSmall("Derniers soldes", soldes));
            if (!c.isNull("tresorerie")) k.addView(kvSmall("Trésorerie (SMS + espèces)", SmsParser.fmt(c.optLong("tresorerie", 0))));
            LinearLayout foot = new LinearLayout(this);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            long act = c.isNull("activite") ? 0 : c.optLong("activite", 0);
            foot.addView(text("Sauvegardée " + ilYa(act), 12, act == 0 || System.currentTimeMillis() - act > 24 * 3600000L ? WARN : MUTED, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            long acl = c.optLong("a_classer", 0);
            if (acl > 0) foot.addView(chip(acl + " à classer", BAD, BAD_BG));
            k.addView(foot, fill());
            page.addView(k, fill());
        }
        if (libre) page.addView(text("Aucun code patron : tout téléphone connecté au compte peut voir cette vue. Définissez-en un dans l'onglet Compte.", 12, WARN, false));
        page.addView(text("Chaque téléphone envoie ses chiffres à chaque sauvegarde : un téléphone resté sans réseau peut être en retard. Le classement se fait sur chaque téléphone.", 12, MUTED, false));
    }

    // ----- Plusieurs téléphones : onglet Compte -----

    private View caissesCard() {
        LinearLayout c = card();
        Cloud.Caisses cs = Cloud.caisses(this);
        String moi = Cloud.caisseId(this);
        if (moi.isEmpty() && !Cloud.caisseAChoisir(this)) {
            c.addView(text("Caisse de ce téléphone", 16, INK, true));
            c.addView(text("La caisse de ce téléphone sera préparée à la prochaine sauvegarde.", 13, MUTED, false));
            c.addView(button("Sauvegarder maintenant", false, v -> syncNow()), fill());
            return c;
        }
        if (moi.isEmpty()) {
            c.addView(text("Caisse de ce téléphone", 16, INK, true));
            c.addView(text("Ce compte est déjà utilisé sur un autre téléphone. Chaque téléphone a sa propre caisse : ses SMS, ses soldes OM et MoMo et ses espèces ne se mélangent pas.", 13, MUTED, false));
            c.addView(label("Nouveau téléphone (cas habituel)"));
            if (nomCaisseField == null) {
                nomCaisseField = new EditText(this);
                nomCaisseField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                nomCaisseField.setSingleLine(true);
                nomCaisseField.setHint("Nom de cette caisse, ex. Boutique Akwa");
            }
            c.addView(detach(nomCaisseField), fill());
            c.addView(button("Créer la caisse de ce téléphone", true, v -> creerCaisse()), fill());
            if (!cs.liste.isEmpty()) {
                c.addView(divider());
                c.addView(label("Ce téléphone remplace un ancien"));
                c.addView(text("Reprenez la caisse de l'ancien téléphone pour continuer exactement où elle s'était arrêtée. Ne le faites pas si l'autre téléphone est toujours utilisé.", 13, MUTED, false));
                if (cs.codeDefini) {
                    if (codeRepriseField == null) {
                        codeRepriseField = new EditText(this);
                        codeRepriseField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                        codeRepriseField.setHint("Code patron");
                    }
                    c.addView(detach(codeRepriseField), fill());
                }
                for (Cloud.Caisse1 k : cs.liste) {
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(6), 0, dp(6));
                    LinearLayout info = column(1);
                    info.addView(text(k.nom, 14, INK, true));
                    info.addView(text((k.modele.isEmpty() ? "" : k.modele + " · ") + "sauvegardée " + ilYa(k.activite), 12, MUTED, false));
                    row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    row.addView(small("Reprendre", ACCENT, v -> confirmerReprise(k)));
                    c.addView(row, fill());
                }
            }
            return c;
        }

        c.addView(label("Caisse de ce téléphone"));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(Cloud.caisseNom(this), 18, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(small(renommerOuvert ? "Fermer" : "Renommer", ACCENT, v -> { renommerOuvert = !renommerOuvert; render(); }));
        c.addView(head, fill());
        if (renommerOuvert) {
            if (renommerField == null) {
                renommerField = new EditText(this);
                renommerField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                renommerField.setSingleLine(true);
                renommerField.setText(Cloud.caisseNom(this));
            }
            c.addView(detach(renommerField), fill());
            c.addView(button("Enregistrer le nom", true, v -> renommerCaisse()), fill());
        }
        List<Cloud.Caisse1> autres = new ArrayList<>();
        for (Cloud.Caisse1 k : cs.liste) if (!k.id.equals(moi)) autres.add(k);
        if (!autres.isEmpty()) {
            c.addView(label("Autres caisses du compte"));
            for (Cloud.Caisse1 k : autres) {
                c.addView(text(k.nom + (k.modele.isEmpty() ? "" : " · " + k.modele) + " · sauvegardée " + ilYa(k.activite), 13, INK, false));
            }
        }
        c.addView(text("Pour ajouter un téléphone : installez l'application et connectez-vous avec le même numéro et le même code secret ; il créera sa propre caisse.", 12, MUTED, false));

        c.addView(divider());
        LinearLayout cp = new LinearLayout(this);
        cp.setGravity(Gravity.CENTER_VERTICAL);
        cp.addView(text("Code patron", 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cp.addView(cs.codeDefini ? chip("✓ défini", GOOD, GOOD_BG) : chip("non défini", WARN, WARN_BG));
        c.addView(cp, fill());
        c.addView(text("Il protège la vue « Toutes mes caisses » et la reprise d'une caisse sur un autre téléphone. Il ne remplace pas le code secret du compte.", 12, MUTED, false));
        if (codePatronOuvert) {
            if (codeNouveauField == null) {
                codeAncienField = new EditText(this);
                codeAncienField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                codeAncienField.setHint("Code patron actuel");
                codeNouveauField = new EditText(this);
                codeNouveauField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                codeNouveauField.setHint("Nouveau code (4 à 6 chiffres)");
            }
            if (cs.codeDefini) c.addView(detach(codeAncienField), fill());
            c.addView(detach(codeNouveauField), fill());
            c.addView(button("Enregistrer le code patron", true, v -> definirCodePatron(cs.codeDefini)), fill());
        }
        c.addView(small(codePatronOuvert ? "Fermer" : (cs.codeDefini ? "Changer le code patron" : "Définir le code patron"), ACCENT,
                v -> { codePatronOuvert = !codePatronOuvert; render(); }), fill());

        int cote = Store.get(this).misDeCote();
        if (cote > 0) {
            c.addView(divider());
            c.addView(text(cote + " SMS venus d'un autre téléphone du compte ont été mis de côté lors de la séparation des caisses. Ils ne comptent pas dans cette caisse et ne sont pas effacés.", 12, MUTED, false));
            c.addView(small("Ils sont bien à ce téléphone : les remettre dans cette caisse", ACCENT, v -> confirmerRecuperation(cote)), fill());
        }
        c.addView(small("Actualiser la liste des caisses", MUTED, v -> actualiserCaisses()), fill());
        return c;
    }

    private void creerCaisse() {
        String nom = nomCaisseField.getText().toString().trim();
        if (nom.isEmpty()) { notice("Donnez un nom à la caisse de ce téléphone, par exemple le nom de la boutique.", 2); return; }
        notice("Création de la caisse « " + nom + " »…", 0);
        io.execute(() -> {
            String msg;
            int niveau;
            try {
                Cloud.creerCaisse(this, nom);
                msg = "Caisse « " + nom + " » créée : les données de ce téléphone y sont sauvegardées.";
                niveau = 1;
            } catch (Cloud.CloudException e) {
                msg = e.getMessage();
                niveau = 2;
            }
            final String m = msg;
            final int n = niveau;
            runOnUiThread(() -> { notice(m, n); reload(false); });
        });
    }

    private void confirmerReprise(Cloud.Caisse1 k) {
        Cloud.Caisses cs = Cloud.caisses(this);
        String code = codeRepriseField == null ? "" : codeRepriseField.getText().toString().trim();
        if (cs.codeDefini && !code.matches("\\d{4,6}")) { notice("Saisissez d'abord le code patron.", 2); return; }
        new AlertDialog.Builder(this)
                .setTitle("Reprendre « " + k.nom + " » ?")
                .setMessage("Ce téléphone continuera la caisse « " + k.nom + " » : ses données seront récupérées ici. "
                        + "À faire seulement si l'ancien téléphone n'est plus utilisé, sinon les deux téléphones écriront dans la même caisse.")
                .setPositiveButton("Reprendre", (d, w) -> {
                    notice("Reprise de la caisse « " + k.nom + " »…", 0);
                    io.execute(() -> {
                        String msg;
                        int niveau;
                        try {
                            Cloud.reprendreCaisse(this, k.id, code);
                            msg = "Caisse « " + k.nom + " » reprise : ses données sont récupérées sur ce téléphone.";
                            niveau = 1;
                        } catch (Cloud.CloudException e) {
                            msg = e.getMessage();
                            niveau = 2;
                        }
                        final String m = msg;
                        final int n = niveau;
                        runOnUiThread(() -> {
                            if (n == 1 && codeRepriseField != null) codeRepriseField.setText("");
                            notice(m, n);
                            reload(false);
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void renommerCaisse() {
        String nom = renommerField.getText().toString().trim();
        if (nom.isEmpty()) { notice("Le nom ne peut pas être vide.", 2); return; }
        io.execute(() -> {
            String err = null;
            try { Cloud.renommerCaisse(this, nom); } catch (Cloud.CloudException e) { err = e.getMessage(); }
            final String e2 = err;
            runOnUiThread(() -> {
                if (e2 == null) { renommerOuvert = false; notice("Caisse renommée « " + nom + " ».", 1); } else notice(e2, 2);
                render();
            });
        });
    }

    private void definirCodePatron(boolean existe) {
        String ancien = existe ? codeAncienField.getText().toString().trim() : "";
        String nouveau = codeNouveauField.getText().toString().trim();
        if (!nouveau.matches("\\d{4,6}")) { notice("Le code patron doit faire 4 à 6 chiffres.", 2); return; }
        io.execute(() -> {
            String err;
            try { err = Cloud.definirCodePatron(this, ancien, nouveau); } catch (Cloud.CloudException e) { err = e.getMessage(); }
            final String e2 = err;
            runOnUiThread(() -> {
                if (e2 == null) {
                    codePatronOuvert = false;
                    codeAncienField.setText("");
                    codeNouveauField.setText("");
                    codePatron = null; // la vue Toutes mes caisses redemandera le nouveau code
                    notice("Code patron enregistré. Notez-le : il n'est pas récupérable, seulement modifiable avec l'ancien.", 1);
                } else {
                    notice(e2, 2);
                }
                render();
            });
        });
    }

    private void confirmerRecuperation(int nb) {
        new AlertDialog.Builder(this)
                .setTitle("Remettre " + nb + " SMS dans cette caisse ?")
                .setMessage("À faire seulement si ces SMS viennent bien de ce téléphone (par exemple des SMS supprimés de la messagerie). "
                        + "S'ils viennent d'un autre téléphone, les soldes de cette caisse seront faux.")
                .setPositiveButton("Remettre", (d, w) -> io.execute(() -> {
                    Cloud.recupererMisDeCote(this);
                    SyncJob.soon(this);
                    runOnUiThread(() -> { notice(nb + " SMS remis dans cette caisse.", 1); reload(false); });
                }))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void actualiserCaisses() {
        io.execute(() -> {
            String err = null;
            try { Cloud.rafraichirCaisses(this); } catch (Cloud.CloudException e) { err = e.getMessage(); }
            final String e2 = err;
            runOnUiThread(() -> { if (e2 != null) notice(e2, 2); render(); });
        });
    }

    // ----- Page Finances -----

    private void financesPage() {
        long now = System.currentTimeMillis();
        long debut = Finances.debutMois(now, moisDecalage);
        long finMois = Finances.debutMois(now, moisDecalage + 1) - 1;
        boolean enCours = moisDecalage == 0;
        long fin = enCours ? now : finMois;
        String nomMois = new SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(new Date(debut));
        nomMois = nomMois.substring(0, 1).toUpperCase(Locale.FRANCE) + nomMois.substring(1);

        pageTitle("Finances", null);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(small("◂", INK, v -> { moisDecalage--; render(); }));
        TextView mois = text(nomMois, 16, INK, true);
        mois.setGravity(Gravity.CENTER);
        nav.addView(mois, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button suivant = small("▸", INK, v -> { if (moisDecalage < 0) { moisDecalage++; render(); } });
        suivant.setEnabled(moisDecalage < 0);
        suivant.setAlpha(moisDecalage < 0 ? 1f : 0.3f);
        nav.addView(suivant);
        page.addView(nav, fill());
        if (result == null) { page.addView(empty("Ouverture de vos finances…")); return; }

        if (Cloud.configured() && Cloud.loggedIn(this) && visible(Modules.ASSISTANT)) page.addView(assistantCard(), fill());
        if (Cloud.configured() && Cloud.loggedIn(this)) page.addView(analyseCard(), fill());
        page.addView(epargneCard(), fill());
        Caisse.Bilan b = computeBilan(debut, fin);
        if (acces(Modules.FINANCES) == Modules.Acces.ABSENT) {
            // Base gratuite : entrées et dépenses par catégorie ; le reste est proposé.
            page.addView(entreesSortiesCard(b), fill());
            ajouterSuiviEtDettes(null);
            page.addView(promoModule(Modules.FINANCES, "Trésorerie de tous vos comptes, résultat du mois comparé au mois précédent, budgets par poste avec alertes, "
                    + "ventes par moyen de paiement, meilleurs clients. Export Excel inclus."), fill());
            return;
        }
        if (!actif(Modules.FINANCES)) page.addView(bandeauLecture(Modules.FINANCES), fill());
        Finances.Mois m = Finances.mois(b);
        long debutPrec = Finances.debutMois(now, moisDecalage - 1);
        long finPrec = enCours ? Finances.memeMomentMoisPrecedent(debut, now) : debut - 1;
        Finances.Mois p = Finances.mois(computeBilan(debutPrec, finPrec));
        Map<String, Long> budgets = Store.get(this).budgets();
        List<Finances.Budget> bud = Finances.budgets(m, budgets);

        // Alertes de budget.
        for (Finances.Budget x : bud) {
            if (x.budget <= 0 || x.pourcent < 80) continue;
            boolean depasse = x.pourcent >= 100;
            TextView a = text((depasse ? "⚠  Budget dépassé : " : "⚠  Budget presque atteint : ") + Caisse.categorieNom(x.poste) + " · "
                    + x.pourcent + " % (" + SmsParser.fmt(x.depense) + " / " + SmsParser.fmt(x.budget) + ")", 13, depasse ? BAD : WARN, true);
            a.setBackground(box(depasse ? BAD_BG : WARN_BG, 0, 10));
            a.setPadding(dp(12), dp(9), dp(12), dp(9));
            page.addView(a, fill());
        }

        // Trésorerie à la fin du mois choisi (aujourd'hui pour le mois en cours) et soldes du mois.
        Soldes.Releve rvMois = computeSoldes(debut, fin);
        Finances.Tresorerie tr = Finances.tresorerie(result, rvMois.comptes.contains(Soldes.ESPECES) ? rvMois.cloture.get(Soldes.ESPECES) : null,
                Store.get(this).avoirs(), fin);
        page.addView(tresorerieCard(tr, rvMois, enCours ? "AUJOURD'HUI" : "FIN " + nomMois.toUpperCase(Locale.FRANCE),
                enCours ? "ce mois" : nomMois.toLowerCase(Locale.FRANCE)), fill());
        if (!rvMois.comptes.isEmpty()) page.addView(soldesPeriodeCard(rvMois, enCours ? "ce mois" : nomMois), fill());

        // Le mois.
        LinearLayout mc = card();
        mc.addView(label(enCours ? "Ce mois · du 1er à aujourd'hui" : nomMois));
        TextView res = text((m.resultat >= 0 ? "" : "−") + SmsParser.fmt(Math.abs(m.resultat)) + " FCFA", 24, m.resultat >= 0 ? INK : BAD, true);
        res.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mc.addView(res);
        mc.addView(text("Résultat · comparé " + (enCours ? "à la même période du mois dernier" : "au mois précédent"), 12, MUTED, false));
        mc.addView(ligneMois("Ventes", m.ventes, p.ventes, true), fill());
        mc.addView(ligneMois("Achats", m.achats, p.achats, false), fill());
        mc.addView(ligneMois("Charges", m.charges, p.charges, false), fill());
        mc.addView(ligneMois("Frais opérateur", m.frais, p.frais, false), fill());
        mc.addView(ligneMois("Résultat", m.resultat, p.resultat, true), fill());
        if (m.apports > 0 || m.retraits > 0) {
            mc.addView(divider());
            mc.addView(kvSmall("Apports personnels (hors résultat)", "+" + SmsParser.fmt(m.apports)));
            mc.addView(kvSmall("Retraits personnels (hors résultat)", "−" + SmsParser.fmt(m.retraits)));
            long reste = m.resultat - m.retraits;
            mc.addView(text((reste >= 0 ? "Après vos retraits, l'activité garde " : "Vos retraits dépassent le résultat de ")
                    + SmsParser.fmt(Math.abs(reste)) + " FCFA ce mois-ci.", 12, reste >= 0 ? GOOD : BAD, true));
        }
        if (m.aClasser > 0) {
            TextView w = text("⚠  " + m.aClasser + " opération(s) à classer : chiffres provisoires  ›", 13, WARN, true);
            w.setOnClickListener(v -> showPage(PAGE_CLASSER));
            mc.addView(w, fill());
        }
        page.addView(mc, fill());

        // Entrées et sorties par catégorie, suivi de l'argent, dettes et créances (0.10).
        page.addView(entreesSortiesCard(b), fill());
        ajouterSuiviEtDettes(tr);

        // Dépenses par poste et budgets.
        LinearLayout dc = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Dépenses par poste", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(small(budgetsOuverts ? "Fermer" : "Budgets", ACCENT, v -> { budgetsOuverts = !budgetsOuverts; render(); }));
        dc.addView(head, fill());
        if (budgetsOuverts) {
            dc.addView(text("Budget mensuel par poste, en FCFA (laisser vide = pas de budget). Une alerte s'affiche à 80 % et à 100 %.", 12, MUTED, false));
            for (String poste : Caisse.POSTES_DEPENSE) {
                EditText f = budgetFields.get(poste);
                if (f == null) {
                    f = amountField("Pas de budget");
                    f.setInputType(InputType.TYPE_CLASS_NUMBER);
                    Long v = budgets.get(poste);
                    if (v != null) f.setText(String.valueOf(v / 100));
                    budgetFields.put(poste, f);
                }
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(text(Caisse.categorieNom(poste), 14, INK, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(detach(f), new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
                dc.addView(row, fill());
            }
            dc.addView(button("Enregistrer les budgets", true, v -> enregistrerBudgets()), fill());
        } else if (bud.isEmpty()) {
            dc.addView(text("Aucune dépense classée ce mois-ci. Classez vos sorties en Achat ou dans un poste de charge (loyer, transport…) pour les suivre ici.", 13, MUTED, false));
        } else {
            long max = 1;
            for (Finances.Budget x : bud) max = Math.max(max, Math.max(x.depense, x.budget));
            for (Finances.Budget x : bud) dc.addView(barrePoste(x, max), fill());
        }
        page.addView(dc, fill());

        // Encaissements par moyen de paiement.
        if (!m.ventesParMoyen.isEmpty()) {
            LinearLayout vc = card();
            vc.addView(text("Ventes par moyen de paiement", 16, INK, true));
            long tot = 0;
            for (long v : m.ventesParMoyen.values()) tot += v;
            for (Map.Entry<String, Long> e : m.ventesParMoyen.entrySet()) {
                vc.addView(kvSmall(e.getKey(), SmsParser.fmt(e.getValue()) + "  (" + Math.round(e.getValue() * 100.0 / Math.max(1, tot)) + " %)"));
            }
            page.addView(vc, fill());
        }

        // Meilleurs clients.
        if (!m.meilleursClients.isEmpty()) {
            LinearLayout cc = card();
            cc.addView(text("Meilleurs clients du mois", 16, INK, true));
            int i = 1;
            for (Finances.Client c : m.meilleursClients) {
                String nom = c.nom != null && !c.nom.trim().isEmpty() ? c.nom.trim() : (c.numero != null ? formatNumero(c.numero) : "Client");
                cc.addView(kvSmall(i++ + ". " + nom + " · " + c.operations + " achat(s)", SmsParser.fmt(c.montant)));
            }
            page.addView(cc, fill());
        }
        page.addView(text("Les chiffres viennent des opérations classées de cette caisse. Classez les opérations « À classer » pour des chiffres complets.", 12, MUTED, false));
    }

    private View tresorerieCard(Finances.Tresorerie tr, Soldes.Releve rv, String moment, String periode) {
        LinearLayout c = column(6);
        c.setBackground(box(ACCENT, 0, 18));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView lab = text("TRÉSORERIE · " + moment, 11, ON_ACCENT_SOFT, true);
        lab.setLetterSpacing(0.05f);
        c.addView(lab);
        TextView tot = text(SmsParser.fmt(tr.total) + " FCFA", 30, Color.WHITE, true);
        tot.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        c.addView(tot);
        c.addView(text("Ce que vous avez : comptes mobile money et bancaires, espèces et autres avoirs", 12, ON_ACCENT_SOFT, false));
        for (Finances.Poste p : tr.postes) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            LinearLayout info = column(0);
            info.addView(text(p.nom + (p.manuel ? "  ✎" : ""), 14, Color.WHITE, true));
            String quand = p.manuel ? "saisi le " + when(p.date).substring(0, 10)
                    : p.date > 0 ? "dernier SMS le " + when(p.date) : "espèces du jour";
            info.addView(text(quand, 11, ON_ACCENT_SOFT, false));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView v = text(SmsParser.fmt(p.montant), 15, Color.WHITE, true);
            v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(v);
            if (p.manuel) row.setOnClickListener(x -> ouvrirAvoir(p));
            c.addView(row, fill());
        }
        // Évolution du total des comptes (SMS et espèces), jour par jour sur la période.
        Long[] ev = rv.parJour(31);
        long mn = Long.MAX_VALUE, mx = Long.MIN_VALUE;
        for (Long v : ev) if (v != null) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        if (mx != Long.MIN_VALUE && mx > 0) {
            c.addView(text("Total OM, MoMo, banques" + (rv.comptes.contains(Soldes.ESPECES) ? " et espèces" : "") + " · fin de chaque jour, " + periode, 11, ON_ACCENT_SOFT, false));
            LinearLayout bars = new LinearLayout(this);
            bars.setGravity(Gravity.BOTTOM);
            int h = dp(56);
            for (Long v : ev) {
                View bar = new View(this);
                int bh = v == null ? dp(2) : Math.max(dp(2), (int) (h * (double) Math.max(0, v) / mx));
                bar.setBackground(box(v == null ? ON_ACCENT_SOFT : Color.WHITE, 0, 2));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, bh, 1);
                lp.setMarginEnd(dp(2));
                bars.addView(bar, lp);
            }
            c.addView(bars, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            c.addView(text("min " + SmsParser.fmt(mn) + " · max " + SmsParser.fmt(mx), 11, ON_ACCENT_SOFT, false));
        }
        TextView add = text("+  Ajouter un avoir (banque sans SMS, épargne, tontine…)", 13, ACCENT, true);
        add.setBackground(box(Color.WHITE, 0, 10));
        add.setPadding(dp(12), dp(9), dp(12), dp(9));
        add.setOnClickListener(x -> ouvrirAvoir(null));
        LinearLayout.LayoutParams ap = fill();
        ap.setMargins(0, dp(6), 0, 0);
        c.addView(add, ap);
        return c;
    }

    /** Ligne du mois : montant et variation par rapport à la période de comparaison. */
    private View ligneMois(String nom, long actuel, long reference, boolean hausseBonne) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(text(nom, 14, INK, "Résultat".equals(nom)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = text(SmsParser.fmt(actuel), 14, INK, "Résultat".equals(nom));
        v.setTypeface(Typeface.MONOSPACE, "Résultat".equals(nom) ? Typeface.BOLD : Typeface.NORMAL);
        row.addView(v);
        Integer var = Finances.variation(actuel, reference);
        TextView t;
        if (var == null) {
            t = text("  —", 12, MUTED, false);
        } else {
            boolean bon = var == 0 || (var > 0) == hausseBonne;
            t = text("  " + (var > 0 ? "▲ " : var < 0 ? "▼ " : "= ") + Math.abs(var) + " %", 12, var == 0 ? MUTED : bon ? GOOD : BAD, true);
        }
        t.setMinWidth(dp(70));
        t.setGravity(Gravity.END);
        row.addView(t);
        return row;
    }

    /** Barre d'un poste de dépense, avec son budget. */
    private View barrePoste(Finances.Budget x, long max) {
        LinearLayout r = column(3);
        r.setPadding(0, dp(4), 0, dp(4));
        LinearLayout top = new LinearLayout(this);
        top.addView(text(Caisse.categorieNom(x.poste), 13, INK, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(text(SmsParser.fmt(x.depense) + (x.budget > 0 ? " / " + SmsParser.fmt(x.budget) : ""), 13, INK, true));
        r.addView(top, fill());
        int couleur = x.budget <= 0 ? CASH : x.pourcent >= 100 ? BAD : x.pourcent >= 80 ? WARN : GOOD;
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(box(BG, 0, 4));
        long ref = x.budget > 0 ? Math.max(x.budget, x.depense) : max;
        int plein = (int) Math.round(Math.min(1.0, x.depense / (double) Math.max(1, ref)) * 1000);
        View f = new View(this);
        f.setBackground(box(couleur, 0, 4));
        bar.addView(f, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, plein)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, 1000 - plein)));
        r.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        if (x.budget > 0) r.addView(text(x.pourcent + " % du budget", 11, couleur == GOOD ? MUTED : couleur, couleur != GOOD));
        return r;
    }

    private void enregistrerBudgets() {
        if (!exiger(Modules.FINANCES)) return;
        final Map<String, Long> valeurs = new HashMap<>();
        for (String poste : Caisse.POSTES_DEPENSE) {
            EditText f = budgetFields.get(poste);
            String d = f == null ? "" : f.getText().toString().replaceAll("\\D", "");
            valeurs.put(poste, d.isEmpty() || d.length() > 9 ? null : Long.parseLong(d) * 100);
        }
        io.execute(() -> {
            Store st = Store.get(this);
            Map<String, Long> avant = st.budgets();
            for (Map.Entry<String, Long> e : valeurs.entrySet()) {
                Long old = avant.get(e.getKey());
                Long nv = e.getValue() == null || e.getValue() <= 0 ? null : e.getValue();
                if ((old == null && nv == null) || (old != null && old.equals(nv))) continue;
                st.setBudget(e.getKey(), nv);
            }
            SyncJob.soon(this);
            runOnUiThread(() -> { budgetsOuverts = false; notice("Budgets enregistrés.", 1); render(); });
        });
    }

    /** Ajoute (p == null) ou modifie un avoir saisi à la main. */
    private void ouvrirAvoir(Finances.Poste p) {
        if (!exiger(Modules.FINANCES)) return;
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText nom = new EditText(this);
        nom.setSingleLine(true);
        nom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nom.setHint("Nom (ex. Compte Ecobank, Tontine, Épargne)");
        EditText montant = amountField("Montant en FCFA");
        montant.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (p != null) {
            nom.setText(p.nom);
            montant.setText(String.valueOf(p.montant / 100));
        }
        form.addView(nom, fill());
        form.addView(montant, fill());
        form.addView(text("Pour l'argent que l'application ne voit pas dans les SMS. Mettez le montant à jour de temps en temps.", 12, MUTED, false));
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(p == null ? "Ajouter un avoir" : "Modifier « " + p.nom + " »")
                .setView(form)
                .setPositiveButton("Enregistrer", (x, y) -> {
                    String n = nom.getText().toString().trim();
                    String digits = montant.getText().toString().replaceAll("\\D", "");
                    if (n.isEmpty() || digits.isEmpty() || digits.length() > 10) { notice("Indiquez un nom et un montant.", 2); return; }
                    final long cents = Long.parseLong(digits) * 100;
                    final String id = p != null ? p.id : java.util.UUID.randomUUID().toString();
                    io.execute(() -> {
                        Store.get(this).setAvoir(id, n, cents);
                        SyncJob.soon(this);
                        runOnUiThread(() -> { notice("« " + n + " » enregistré.", 1); render(); });
                    });
                })
                .setNegativeButton("Annuler", null);
        if (p != null) {
            d.setNeutralButton("Supprimer", (x, y) -> io.execute(() -> {
                Store.get(this).setAvoir(p.id, p.nom, null);
                SyncJob.soon(this);
                runOnUiThread(() -> { notice("« " + p.nom + " » supprimé.", 0); render(); });
            }));
        }
        d.show();
    }

    // ----- Page À classer -----

    private void classerPage() {
        pageTitle("À classer", periodLabel());
        if (bilan == null) { page.addView(empty("Ouverture de votre caisse…")); return; }
        if (bilan.aClasser.isEmpty()) {
            LinearLayout done = card();
            done.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView ok = text("✓", 40, GOOD, true);
            ok.setGravity(Gravity.CENTER);
            done.addView(ok, fill());
            TextView t = text("Tout est classé", 18, INK, true);
            t.setGravity(Gravity.CENTER);
            done.addView(t, fill());
            TextView s = text(bilan.lignes.isEmpty() ? "Aucune opération sur la période." : "Les nouvelles opérations apparaîtront ici dès leur arrivée.", 13, MUTED, false);
            s.setGravity(Gravity.CENTER);
            done.addView(s, fill());
            page.addView(done, fill());
            return;
        }
        Caisse.Total x = bilan.total(null);
        page.addView(text(bilan.aClasser.size() + " opération(s) · +" + SmsParser.fmt(x.entrees) + " / −" + SmsParser.fmt(x.sorties)
                + " FCFA. Un toucher sur la catégorie suffit ; touchez l'opération pour ajouter un motif.", 13, MUTED, false));
        propsCourantes = propositions();
        enteteClassementIa(propsCourantes);
        LinearLayout list = listCard();
        int n = 0;
        for (Caisse.Ligne l : bilan.aClasser) {
            if (n++ >= MAX_ROWS) break;
            list.addView(ligneRow(l, true), fill());
        }
        page.addView(list, fill());
    }

    // ----- Page Exporter -----

    private static int nbOperations(Caisse.Bilan b) {
        int n = b.lignes.size();
        for (Caisse.Saisie s : b.mouvementsEspeces) if (!s.annule) n++;
        for (Caisse.Saisie s : b.depensesSuivies) if (!s.annule) n++;
        return n;
    }

    private void exportPage() {
        pageTitle("Exporter", "Rapport Excel de la période choisie en haut");
        boolean toutes = vueToutes && plusieursCaisses();
        if (plusieursCaisses()) {
            page.addView(segmented(new String[] {"Cette caisse", "Toutes les caisses"}, toutes ? 1 : 0,
                    k -> { vueToutes = k == 1; render(); }), fill());
        }
        if (toutes && !vuePrete()) {
            LinearLayout c = card();
            c.addView(text("Rapport de toutes les caisses", 16, INK, true));
            c.addView(text("Ouvrez d'abord « Toutes mes caisses » dans l'onglet Caisse (code patron) : le rapport reprend les chiffres envoyés par chaque téléphone pour la période choisie.", 13, MUTED, false));
            c.addView(button("Ouvrir Toutes mes caisses", true, v -> showPage(PAGE_CAISSE)), fill());
            page.addView(c, fill());
            return;
        }
        if (!toutes && bilan == null) { page.addView(empty("Ouverture de votre caisse…")); return; }
        int nb = toutes ? nbToutes() : nbOperations(bilan);
        int especes = toutes ? 0 : nb - bilan.lignes.size();
        long resultat = toutes ? sommeVue("resultat") : bilan.resultat;
        int aClasser = toutes ? (int) sommeVue("a_classer") : bilan.aClasser.size();

        LinearLayout rep = card();
        rep.addView(label((toutes ? "Toutes mes caisses · " : "Rapport Excel · ") + periodLabel()));
        TextView total = text(nb + " opération" + (nb > 1 ? "s" : ""), 24, INK, true);
        rep.addView(total);
        rep.addView(text((toutes ? vueData.optJSONArray("caisses").length() + " caisses" : bilan.lignes.size() + " mobile money · " + especes + " espèces")
                + " · résultat " + (resultat >= 0 ? "" : "−") + SmsParser.fmt(Math.abs(resultat)) + " FCFA", 13, MUTED, false));
        if (aClasser > 0) {
            TextView w = text("⚠  " + aClasser + " opération(s) non classée(s) : classez-les d'abord pour un rapport complet  ›", 13, WARN, true);
            w.setBackground(box(WARN_BG, 0, 10));
            w.setPadding(dp(12), dp(9), dp(12), dp(9));
            w.setOnClickListener(v -> showPage(PAGE_CLASSER));
            rep.addView(w, fill());
        }

        Cloud.Portefeuille w = Cloud.configured() && Cloud.loggedIn(this) ? Cloud.portefeuille(this) : null;
        int petit = w != null ? w.prixPetit : Tarif.PETIT, grand = w != null ? w.prixGrand : Tarif.GRAND, seuil = w != null ? w.seuil : Tarif.SEUIL;
        LinearLayout priceBox = column(2);
        priceBox.setBackground(box(BG, 0, 12));
        priceBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        Button go;
        if (!Cloud.configured()) {
            priceBox.addView(text("Export gratuit dans cette version.", 15, INK, true));
            go = button("Exporter en Excel", true, v -> lancerExport());
        } else if (w == null) {
            priceBox.addView(text("Créez votre compte pour exporter", 15, INK, true));
            priceBox.addView(text("Votre premier export est offert.", 13, MUTED, false));
            go = button("Créer mon compte", true, v -> showPage(PAGE_COMPTE));
        } else {
            Tarif.Decision d = w.decider(nb);
            priceBox.addView(label("Prix de cet export"));
            TextView price = text(d.inclus ? "Inclus" : d.offert ? "Offert" : d.prix == 0 ? "Gratuit" : Tarif.fcfa(d.prix), 22, d.offert || d.inclus ? GOOD : INK, true);
            priceBox.addView(price);
            if (d.inclus) priceBox.addView(text("Export inclus avec votre application Finances & budgets.", 13, MUTED, false));
            else if (d.offert) priceBox.addView(text("Votre premier export est offert (au lieu de " + Tarif.fcfa(d.prixNormal) + ").", 13, MUTED, false));
            else if (d.prix > 0 && Cloud.loggedIn(this)) priceBox.addView(small("Exports illimités avec Finances & budgets  ›", ACCENT, v -> proposerModule(Modules.FINANCES)));
            else if (d.prix > 0 && d.possible) priceBox.addView(text("Solde après l'export : " + Tarif.fcfa(d.soldeApres), 13, MUTED, false));
            else if (!d.possible) priceBox.addView(text("Il manque " + Tarif.fcfa(d.manque) + " sur votre porte-monnaie.", 13, BAD, true));
            go = d.possible
                    ? button("Exporter en Excel" + (d.prix > 0 ? " · " + Tarif.fcfa(d.prix) : ""), true, v -> lancerExport())
                    : button("Recharger mon porte-monnaie", true, v -> { rechargeOuverte = true; render(); });
        }
        rep.addView(priceBox, fill());
        go.setMinHeight(dp(52));
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        rep.addView(go, fill());
        if (Cloud.configured()) {
            rep.addView(text("Tarif par fichier : 1 à " + seuil + " opérations " + Tarif.fcfa(petit) + " · plus de " + seuil + " : " + Tarif.fcfa(grand)
                    + (toutes ? ". Le classeur contient une ligne par caisse et le détail par jour."
                    : ". Le classeur contient la synthèse, le journal de caisse, les opérations à classer, les espèces et les SMS à vérifier."), 12, MUTED, false));
        }
        page.addView(rep, fill());

        if (w != null) {
            page.addView(portefeuilleCard(w), fill());
            if (w.historique.length() > 0) page.addView(historiqueCard(w), fill());
        }
    }

    private View portefeuilleCard(Cloud.Portefeuille w) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Porte-monnaie", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(w.verifie > 0 ? "à jour " + when(w.verifie).substring(0, 10) : "jamais vérifié", 12, MUTED, false));
        card.addView(head, fill());
        TextView solde = text(Tarif.fcfa(w.solde), 26, w.solde < 0 ? BAD : INK, true);
        solde.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(solde);
        if (w.offertsRestants > 0) card.addView(chipRow("🎁  " + w.offertsRestants + " export offert", GOOD, GOOD_BG));
        if (w.enAttente > 0) card.addView(chipRow(Tarif.fcfa(w.enAttente) + " en attente de validation", WARN, WARN_BG));
        if (w.exportsHorsLigne > 0) card.addView(chipRow(w.exportsHorsLigne + " export(s) fait(s) hors ligne, à transmettre", WARN, WARN_BG));
        if (w.solde < 0) card.addView(text("Solde négatif : un export fait hors ligne a dépassé votre solde. Rechargez pour exporter à nouveau.", 12, BAD, false));
        if (w.suspendu) card.addView(text("Compte suspendu : contactez le service client.", 13, BAD, true));

        LinearLayout actions = new LinearLayout(this);
        actions.addView(button(rechargeOuverte ? "Fermer" : "Recharger", !rechargeOuverte, v -> { rechargeOuverte = !rechargeOuverte; render(); }), weight(false));
        actions.addView(button("Actualiser", false, v -> actualiserPortefeuille()), weight(true));
        card.addView(actions, fill());
        if (rechargeOuverte || Cloud.rechargeAttente(this) != null) rechargeForm(card, w);
        return card;
    }

    private View chipRow(String s, int fg, int bg) {
        LinearLayout row = new LinearLayout(this);
        row.addView(chip(s, fg, bg));
        return row;
    }

    /** « Bonus : +500 FCFA → 5 500 FCFA » sous le montant, si une promotion de recharge s'applique. */
    private void majBonusRecharge() {
        if (rechargeBonusInfo == null || rechargeMontant == null) return;
        long saisi = 0;
        try { saisi = Long.parseLong(rechargeMontant.getText().toString().trim()); } catch (Exception ignored) {}
        long b = mods().bonusRecharge(saisi);
        rechargeBonusInfo.setText(b > 0 ? "Bonus : +" + Tarif.fcfa(b) + " → " + Tarif.fcfa(saisi + b) + " sur votre porte-monnaie." : "");
        rechargeBonusInfo.setVisibility(b > 0 ? View.VISIBLE : View.GONE);
    }

    private void rechargeForm(LinearLayout card, Cloud.Portefeuille w) {
        card.addView(divider());
        if (w.numeroOm.isEmpty() && w.numeroMomo.isEmpty()) {
            card.addView(text("Les numéros de paiement ne sont pas encore disponibles. Touchez « Actualiser » plus tard ou contactez le service client.", 13, WARN, false));
            return;
        }
        if (w.numeroOm.isEmpty()) rechargeOp = "MOMO";
        if (w.numeroMomo.isEmpty()) rechargeOp = "OM";
        boolean om = "OM".equals(rechargeOp);
        String numero = om ? w.numeroOm : w.numeroMomo;

        Recharges.Attente a = Cloud.rechargeAttente(this);
        if (a != null) {
            rechargeEnCours(card, w, a);
        } else {
            // 1. Opérateur  2. Montant  3. Payer : le menu de l'opérateur s'ouvre, l'ID sera relevé tout seul.
            card.addView(label("Recharger en un geste"));
            Modules.Promo bonus = mods().meilleurBonus();
            if (bonus != null) {
                TextView bt = text("Promotion : " + Modules.promoTexte(mods(), bonus) + ".", 13, GOOD, true);
                bt.setBackground(box(GOOD_BG, 0, 10));
                bt.setPadding(dp(12), dp(8), dp(12), dp(8));
                card.addView(bt, fill());
            }
            if (!w.numeroOm.isEmpty() && !w.numeroMomo.isEmpty()) {
                card.addView(segmented(new String[] {"Orange Money", "MTN MoMo"}, om ? 0 : 1, k -> { rechargeOp = k == 0 ? "OM" : "MOMO"; render(); }), fill());
            }
            if (rechargeMontant == null) {
                rechargeMontant = amountField("Montant, en FCFA");
                rechargeMontant.setInputType(InputType.TYPE_CLASS_NUMBER);
                rechargeMontant.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence x, int a1, int a2, int a3) {}
                    @Override public void onTextChanged(CharSequence x, int a1, int a2, int a3) {}
                    @Override public void afterTextChanged(android.text.Editable x) { majBonusRecharge(); }
                });
            }
            card.addView(detach(rechargeMontant), fill());
            rechargeBonusInfo = text("", 12, GOOD, true);
            card.addView(rechargeBonusInfo, fill());
            majBonusRecharge();
            LinearLayout vite = new LinearLayout(this);
            for (long m : new long[] {1000, 2000, 5000, 10000}) {
                if (m < w.rechargeMin) continue;
                vite.addView(small(SmsParser.fmt(m * 100), INK, v -> rechargeMontant.setText(String.valueOf(m))), weight(m == 10000));
            }
            card.addView(vite, fill());
            card.addView(text("Le paiement va au " + formatNumero(numero) + (w.marchand.isEmpty() ? "" : " (" + w.marchand + ")")
                    + ". Minimum " + Tarif.fcfa(w.rechargeMin) + " · exemple : 5 000 FCFA = 10 exports de 200 opérations au plus.", 12, MUTED, false));
            Button payer = button("Payer avec " + (om ? "Orange Money" : "MTN MoMo"), true, v -> payerRecharge(w));
            payer.setMinHeight(dp(52));
            card.addView(payer, fill());
            card.addView(text(Recharges.codeComplet(om ? w.ussdOm : w.ussdMomo)
                    ? "Le menu s'ouvre avec le numéro et le montant : confirmez avec votre code secret."
                    : "Le menu " + (om ? "Orange Money" : "MoMo") + " s'ouvre : faites un transfert vers ce numéro, de ce montant, et validez avec votre code secret. "
                      + "L'application ne voit jamais votre code.", 12, MUTED, false));
        }

        // Secours : paiement fait depuis un autre téléphone, ou SMS non reconnu.
        TextView manuel = text((rechargeManuel ? "▾" : "▸") + "  J'ai payé autrement : déclarer l'ID à la main", 13, ACCENT, true);
        manuel.setPadding(0, dp(8), 0, dp(4));
        manuel.setOnClickListener(v -> { rechargeManuel = !rechargeManuel; render(); });
        card.addView(manuel, fill());
        if (rechargeManuel) {
            List<Txn> trouves = transfertsVers(w);
            for (Txn t : trouves) {
                LinearLayout k = column(4);
                k.setBackground(box(GOOD_BG, 0, 12));
                k.setPadding(dp(12), dp(10), dp(12), dp(10));
                k.addView(text("Transfert de " + SmsParser.fmt(t.montant) + " FCFA vers " + formatNumero(t.numero), 14, INK, true));
                k.addView(text(Txn.opName(t.op) + " · " + when(t.date) + " · ID " + t.txnId, 12, MUTED, false));
                final String op = "OM".equals(t.op) ? "OM" : "MOMO";
                final long fcfa = t.montant / 100;
                final String ref = t.txnId;
                k.addView(button("Déclarer ce paiement", true, v -> declarer(op, ref, fcfa)), fill());
                card.addView(k, fill());
            }
            if (!w.numeroOm.isEmpty() && !w.numeroMomo.isEmpty()) {
                card.addView(segmented(new String[] {"Orange Money", "MTN MoMo"}, om ? 0 : 1, k -> { rechargeOp = k == 0 ? "OM" : "MOMO"; render(); }), fill());
            }
            if (rechargeMontantManuel == null) {
                rechargeMontantManuel = amountField("Montant envoyé, en FCFA");
                rechargeMontantManuel.setInputType(InputType.TYPE_CLASS_NUMBER);
            }
            card.addView(detach(rechargeMontantManuel), fill());
            if (rechargeRef == null) {
                rechargeRef = new EditText(this);
                rechargeRef.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                rechargeRef.setSingleLine(true);
            }
            rechargeRef.setHint(om ? "ID reçu par SMS, ex. PP251114.1530.A12345" : "ID reçu par SMS, ex. 8472619305");
            card.addView(detach(rechargeRef), fill());
            card.addView(button("Déclarer la recharge", false, v -> declarerRecharge()), fill());
        }
        card.addView(text("Votre solde est crédité dès que le paiement est reçu par le marchand, en général en quelques minutes.", 12, MUTED, false));
    }

    /** Transferts récents (3 jours) de ce téléphone vers les numéros de recharge, pas encore déclarés. */
    private List<Txn> transfertsVers(Cloud.Portefeuille w) {
        List<Txn> out = new ArrayList<>();
        if (result == null) return out;
        String om = w.numeroOm.replaceAll("\\D", ""), momo = w.numeroMomo.replaceAll("\\D", "");
        java.util.Set<String> deja = new java.util.HashSet<>(java.util.Arrays.asList(Store.pref(this, "recharges_declarees", "").split(",")));
        long depuis = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        for (Txn t : result.tx) {
            if (t.date < depuis) break; // du plus récent au plus ancien
            if (t.isBalanceCheck() || t.sens >= 0 || t.txnId == null || t.numero == null) continue;
            String n = t.numero.replaceAll("\\D", "");
            if (n.length() > 9) n = n.substring(n.length() - 9);
            boolean vers = ("OM".equals(t.op) && n.equals(om)) || ("MOMO".equals(t.op) && n.equals(momo));
            if (vers && !deja.contains(t.txnId.toUpperCase(Locale.ROOT))) out.add(t);
            if (out.size() >= 3) break;
        }
        return out;
    }

    private View historiqueCard(Cloud.Portefeuille w) {
        LinearLayout card = listCard();
        TextView t = text("Historique", 16, INK, true);
        t.setPadding(0, dp(10), 0, dp(4));
        card.addView(t);
        int n = Math.min(w.historique.length(), 20);
        for (int i = 0; i < n; i++) {
            org.json.JSONObject h = w.historique.optJSONObject(i);
            if (h == null) continue;
            boolean recharge = "recharge".equals(h.optString("nature", ""));
            String statut = h.isNull("statut") ? "" : h.optString("statut", "");
            long montant = h.optLong("montant", 0);
            String detail = h.isNull("detail") ? "" : h.optString("detail", "");
            String motif = h.isNull("motif") ? "" : h.optString("motif", "");
            LinearLayout row = column(2);
            row.setPadding(0, dp(8), 0, dp(8));
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            String titre = recharge
                    ? ("ADMIN".equals(detail) ? "Crédit offert" : "Recharge " + ("OM".equals(detail) ? "Orange Money" : "MTN MoMo"))
                    : "Export Excel · " + detail + " opér.";
            top.addView(text(titre, 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            String m = "offert".equals(statut) ? "offert" : (montant >= 0 ? "+" : "−") + SmsParser.fmt(Math.abs(montant) * 100);
            TextView mt = text(m, 14, "refuse".equals(statut) ? MUTED : montant > 0 ? GOOD : INK, true);
            mt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            top.addView(mt);
            row.addView(top, fill());
            LinearLayout sub = new LinearLayout(this);
            sub.setGravity(Gravity.CENTER_VERTICAL);
            sub.addView(text(when(h.optLong("quand", 0)) + (recharge || motif.isEmpty() ? "" : " · " + motif), 12, MUTED, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (recharge && "en_attente".equals(statut)) sub.addView(chip("En attente", WARN, WARN_BG));
            if (recharge && "refuse".equals(statut)) sub.addView(chip("Refusée", BAD, BAD_BG));
            row.addView(sub, fill());
            if (recharge && "refuse".equals(statut) && !motif.isEmpty()) row.addView(text(motif, 12, BAD, false));
            card.addView(row, fill());
            if (i < n - 1) card.addView(divider());
        }
        return card;
    }

    private static String formatNumero(String n) {
        String d = n == null ? "" : n.replaceAll("\\D", "");
        if (d.length() == 9) return d.charAt(0) + " " + d.substring(1, 3) + " " + d.substring(3, 5) + " " + d.substring(5, 7) + " " + d.substring(7);
        return n == null ? "" : n;
    }

    // ----- Page Compte -----

    private void comptePage() {
        pageTitle("Compte", null);

        LinearLayout acc = card();
        acc.addView(text("Compte et sauvegarde", 16, INK, true));
        if (!Cloud.configured()) {
            acc.addView(text("Sauvegarde en ligne non configurée dans cette version : vos données restent sur ce téléphone.", 13, MUTED, false));
        } else if (!Cloud.loggedIn(this)) {
            acc.addView(text("Un compte sauvegarde vos opérations, catégories, motifs et espèces, et permet d'exporter en Excel. Si vous changez de téléphone, connectez-vous pour tout retrouver.", 13, MUTED, false));
            if (phoneField == null) {
                phoneField = new EditText(this);
                phoneField.setInputType(InputType.TYPE_CLASS_PHONE);
                phoneField.setHint("Numéro : 6XXXXXXXX");
                String guess = Store.pref(this, "ownerOmAuto", "");
                if (!guess.isEmpty()) phoneField.setText(guess);
                secretField = new EditText(this);
                secretField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                secretField.setHint("Code secret (6 caractères minimum)");
            }
            acc.addView(detach(phoneField), fill());
            acc.addView(detach(secretField), fill());
            acc.addView(text("N'utilisez jamais votre code Orange Money ou MoMo. Choisissez un code propre à cette application et notez-le.", 12, BAD, true));
            LinearLayout b = new LinearLayout(this);
            b.addView(button("Créer mon compte", true, v -> doAuth(true, phoneField.getText().toString(), secretField.getText().toString())), weight(false));
            b.addView(button("Me connecter", false, v -> doAuth(false, phoneField.getText().toString(), secretField.getText().toString())), weight(true));
            acc.addView(b, fill());
        } else {
            acc.addView(kv("Numéro", formatNumero(Cloud.phone(this))));
            long last = Cloud.lastSync(this);
            String err = Cloud.lastError(this);
            if (!err.isEmpty()) {
                acc.addView(chipRow("Dernière sauvegarde : " + (last > 0 ? when(last) : "jamais"), BAD, BAD_BG));
                acc.addView(text(err, 12, BAD, false));
            } else {
                acc.addView(chipRow("✓  Sauvegardé" + (last > 0 ? " · " + when(last) : ""), GOOD, GOOD_BG));
            }
            acc.addView(text("La sauvegarde se fait toute seule dès qu'il y a du réseau.", 12, MUTED, false));
            LinearLayout b = new LinearLayout(this);
            b.addView(button("Sauvegarder maintenant", false, v -> syncNow()), weight(false));
            b.addView(button("Se déconnecter", false, v -> confirmSignOut()), weight(true));
            acc.addView(b, fill());
        }
        page.addView(acc, fill());
        if (Cloud.configured() && Cloud.loggedIn(this)) page.addView(applicationsCard(), fill());
        if (Cloud.configured() && Cloud.loggedIn(this) && visible(Modules.ALERTES)) page.addView(alertesCard(), fill());
        if (Cloud.configured() && Cloud.loggedIn(this) && (visible(Modules.MULTI) || Cloud.caisses(this).liste.size() >= 2 || Cloud.caisseAChoisir(this)))
            page.addView(caissesCard(), fill());
        page.addView(categoriesCard(), fill());

        // Réglages de la caisse.
        if (agentOmBox == null) {
            agentOmBox = new CheckBox(this);
            agentOmBox.setText("Orange Money : compte agent (dépôts et retraits clients)");
            agentOmBox.setChecked(agent(Caisse.OM));
            agentMomoBox = new CheckBox(this);
            agentMomoBox.setText("MTN MoMo : compte agent (dépôts et retraits clients)");
            agentMomoBox.setChecked(agent(Caisse.MOMO));
            ownerField = new EditText(this);
            ownerField.setInputType(InputType.TYPE_CLASS_PHONE);
            ownerField.setText(Store.pref(this, "ownerOm", ""));
            omField = new EditText(this);
            omField.setInputType(InputType.TYPE_CLASS_TEXT);
            omField.setText(Store.pref(this, "om", SmsParser.DEFAULT_OM));
            momoField = new EditText(this);
            momoField.setInputType(InputType.TYPE_CLASS_TEXT);
            momoField.setText(Store.pref(this, "momo", SmsParser.DEFAULT_MOMO));
            banquesField = new EditText(this);
            banquesField.setInputType(InputType.TYPE_CLASS_TEXT);
            banquesField.setText(Store.pref(this, "banques", SmsParser.DEFAULT_BANQUES));
            autoBox = new CheckBox(this);
            autoBox.setText("Lire aussi les SMS d'autres expéditeurs qui parlent d'Orange Money ou de MoMo");
            autoBox.setChecked(Store.prefBool(this, "auto", false));
        }
        LinearLayout set = card();
        set.addView(text("Ma caisse", 16, INK, true));
        set.addView(detach(agentOmBox), fill());
        set.addView(detach(agentMomoBox), fill());
        set.addView(text("En mode agent, les dépôts et retraits clients sont classés automatiquement et font bouger la caisse espèces.", 12, MUTED, false));
        String detected = Store.pref(this, "ownerOmAuto", "");
        set.addView(text("Mon numéro Orange Money (pour savoir si un transfert est reçu ou envoyé)" + (detected.isEmpty() ? "" : " · détecté : " + detected), 13, MUTED, false));
        ownerField.setHint(detected.isEmpty() ? "6XXXXXXXX" : detected);
        set.addView(detach(ownerField), fill());
        set.addView(button("Enregistrer", true, v -> saveSettings()), fill());
        page.addView(set, fill());

        // Lecture des SMS.
        LinearLayout sms = card();
        sms.addView(text("Lecture des SMS", 16, INK, true));
        if (hasSmsPermission()) {
            sms.addView(chipRow("✓  Lecture autorisée" + (result != null ? " · " + result.tx.size() + " opérations lues" : ""), GOOD, GOOD_BG));
        } else {
            sms.addView(detach(permBox), fill());
        }
        TextView adv = text((avanceOuvert ? "▾" : "▸") + "  Réglages avancés", 14, ACCENT, true);
        adv.setPadding(0, dp(8), 0, dp(4));
        adv.setOnClickListener(v -> { avanceOuvert = !avanceOuvert; render(); });
        sms.addView(adv, fill());
        if (avanceOuvert) {
            sms.addView(text("Expéditeurs Orange Money (séparés par des virgules)", 13, MUTED, false));
            sms.addView(detach(omField), fill());
            sms.addView(text("Expéditeurs MTN MoMo (séparés par des virgules)", 13, MUTED, false));
            sms.addView(detach(momoField), fill());
            sms.addView(text("Expéditeurs des banques (séparés par des virgules ; ajoutez le nom exact affiché par votre banque)", 13, MUTED, false));
            sms.addView(detach(banquesField), fill());
            sms.addView(detach(autoBox), fill());
            sms.addView(button("Enregistrer les expéditeurs", false, v -> saveSettings()), fill());
            sms.addView(text(regles.size() + " contact(s) classé(s) automatiquement. Pour retirer une règle, ouvrez une opération du contact et décochez « Toujours classer ».", 12, MUTED, false));
            sms.addView(button("Relire toute la messagerie", false, v -> { resetInbox(); reload(true); notice("Relecture de la messagerie lancée.", 0); }), fill());
            sms.addView(small("Effacer la copie des SMS et tout relire", BAD, v -> confirmErase()), fill());
        }
        page.addView(sms, fill());

        TextView about = text("Carnet Mobile Money · version " + Cloud.VERSION_APP + "\nLes montants viennent des SMS de l'opérateur et ne se modifient jamais.", 12, MUTED, false);
        about.setGravity(Gravity.CENTER);
        page.addView(about, fill());
    }

    private void saveSettings() {
        String oldOm = Store.pref(this, "om", SmsParser.DEFAULT_OM), oldMomo = Store.pref(this, "momo", SmsParser.DEFAULT_MOMO);
        String oldBq = Store.pref(this, "banques", SmsParser.DEFAULT_BANQUES), bq = banquesField.getText().toString();
        Store.savePref(this, "banques", bq);
        boolean oldAuto = Store.prefBool(this, "auto", false);
        String om = omField.getText().toString(), momo = momoField.getText().toString();
        boolean auto = autoBox.isChecked();
        Store.saveConfig(this, om, momo, auto, ownerField.getText().toString().trim());
        Store.saveFlag(this, "agent" + Caisse.OM, agentOmBox.isChecked());
        Store.saveFlag(this, "agent" + Caisse.MOMO, agentMomoBox.isChecked());
        boolean expediteurs = !om.equals(oldOm) || !momo.equals(oldMomo) || !bq.equals(oldBq) || auto != oldAuto;
        if (expediteurs) resetInbox();
        notice(expediteurs ? "Réglages enregistrés : relecture de la messagerie avec les nouveaux expéditeurs." : "Réglages enregistrés.", 1);
        reload(expediteurs);
    }

    /** Une ligne de caisse issue d'un SMS ; quick = boutons de classement directement sous la ligne. */
    private View ligneRow(Caisse.Ligne l, boolean quick) {
        Txn t = l.sms;
        LinearLayout row = column(3);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginEnd(dp(8));
        top.addView(tag(opShort(t.op), opColor(t.op)), tl);
        top.addView(text(t.typeLabel(), 15, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView amt = text((t.sens > 0 ? "+" : "−") + SmsParser.fmt(t.montant), 15, t.sens > 0 ? GOOD : INK, true);
        amt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(amt);
        row.addView(top, fill());

        String who = (t.nom != null ? t.nom : "") + (t.numero != null ? (t.nom != null ? " · " : "") + t.numero : "");
        if (!who.isEmpty()) row.addView(text(who, 13, MUTED, false));
        Soldes.Ligne sl = releve != null ? releve.parSms.get(t) : null;
        String apres = sl != null ? Soldes.texteApres(releve, sl) : "solde " + SmsParser.fmt(t.solde != null ? t.solde : t.apres);
        row.addView(text(when(t.date) + (t.txnId != null ? " · ID " + t.txnId : "") + (t.frais > 0 ? " · frais " + SmsParser.fmt(t.frais) : "")
                + (apres.isEmpty() ? "" : " · " + apres), 12, MUTED, false));

        if (douteux().contains(Caisse.cle(t))) row.addView(alerteDouteux(t), fill());

        LinearLayout cat = new LinearLayout(this);
        cat.setGravity(Gravity.CENTER_VERTICAL);
        cat.addView(catChip(l.categorie, l.auto));
        if (!l.motif.isEmpty()) {
            TextView m = text("  " + l.motif, 13, INK, false);
            cat.addView(m, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        row.addView(cat, fill());
        if (l.reparti > 0) {
            String rs = "Répartie : " + SmsParser.fmt(l.reparti) + (l.reste() > 0 ? " · reste à justifier " + SmsParser.fmt(l.reste()) : " · entièrement justifiée") + "  ›";
            TextView rt = text(rs, 12, l.reste() > 0 ? WARN : GOOD, true);
            rt.setOnClickListener(v -> ouvrirSuivi(Caisse.cle(t)));
            row.addView(rt, fill());
        }
        Dettes.Engagement eng = engagementDe(Caisse.cle(t));
        if (eng != null) {
            TextView et = text((Dettes.CREANCE.equals(eng.type) ? "Créance · " : "Dette · ") + eng.tiers + "  ›", 12, PURPLE, true);
            et.setOnClickListener(v -> ouvrirPersonne(eng.tiers));
            row.addView(et, fill());
        }
        for (String f : t.flags) if (f.startsWith("!")) row.addView(text(f.substring(1), 12, BAD, true));

        if (quick) {
            Classeur.Proposition prop = propsCourantes.get(Caisse.cle(t));
            if (prop != null) row.addView(propositionRow(l, prop), fill());
            LinearLayout q = new LinearLayout(this);
            for (String c : rapides(t.sens)) {
                q.addView(small(Caisse.court(c), catColor(c), v -> classer(t, c, l.motif, false)), weight(false));
            }
            q.addView(small(t.sens > 0 ? "Autres ▸" : "Charges / autres ▸", MUTED, v -> openClassement(l)), weight(true));
            row.addView(q, fill());
        }
        row.setOnClickListener(v -> openClassement(l));
        row.addView(divider());
        return row;
    }

    private View especeRow(Caisse.Saisie s) {
        LinearLayout row = column(3);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginEnd(dp(8));
        top.addView(tag("Espèces", CASH), tl);
        top.addView(text(s.libelle.isEmpty() ? (s.sens > 0 ? "Entrée d'espèces" : "Sortie d'espèces") : s.libelle, 15, s.annule ? MUTED : INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView amt = text((s.sens > 0 ? "+" : "−") + SmsParser.fmt(s.montant), 15, s.annule ? MUTED : (s.sens > 0 ? GOOD : INK), true);
        amt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(amt);
        row.addView(top, fill());
        Soldes.Ligne sl = releve != null && !s.annule ? releve.parSaisie.get(s) : null;
        String apres = sl != null ? Soldes.texteApres(releve, sl) : "";
        row.addView(text(when(s.date) + (apres.isEmpty() ? "" : " · " + apres), 12, MUTED, false));
        LinearLayout act = new LinearLayout(this);
        act.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout chipBox = new LinearLayout(this);
        chipBox.addView(s.annule ? chip("Annulée", MUTED, BG) : catChip(Caisse.normaliserSaisie(s.categorie, s.sens), false));
        act.addView(chipBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!s.annule) act.addView(small("Annuler", BAD, v -> confirmAnnuler(s)));
        row.addView(act, fill());
        row.addView(divider());
        return row;
    }

    private View unknownRow(Txn u) {
        LinearLayout row = column(4);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(text(Txn.opName(u.op) + " · " + u.reason, 14, INK, true));
        row.addView(text((u.sender == null || u.sender.isEmpty() ? "expéditeur inconnu" : u.sender) + " · " + when(u.smsDate), 12, MUTED, false));
        TextView raw = text(u.raw, 13, INK, false);
        raw.setTextIsSelectable(true);
        raw.setBackground(box(BG, 0, 10));
        raw.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.addView(raw, fill());
        if (u.kind == Txn.UNKNOWN) row.addView(lireParIa(u), fill());
        return row;
    }

    // ---------- Classement ----------

    private void classer(Txn t, String categorie, String motif, boolean toujours) {
        final String key = Caisse.cle(t);
        final String numero = t.numero;
        final int sens = t.sens;
        io.execute(() -> {
            Store store = Store.get(this);
            store.classer(key, categorie, motif);
            if (toujours && numero != null) store.regle(Caisse.cleRegle(numero, sens), categorie);
            SyncJob.soon(this);
            Map<String, Caisse.Classement> cl = store.classements();
            Map<String, String> rg = store.regles();
            runOnUiThread(() -> {
                classements = cl;
                regles = rg;
                render();
            });
        });
    }

    /** Montant affiché mais verrouillé ; catégorie et motif modifiables. */
    private void openClassement(Caisse.Ligne l) {
        Txn t = l.sms;
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        TextView amt = big((t.sens > 0 ? "+" : "−") + SmsParser.fmt(t.montant) + " FCFA");
        amt.setTextColor(t.sens > 0 ? GOOD : INK);
        form.addView(amt);
        form.addView(text(Txn.opName(t.op) + " · " + t.typeLabel() + " · " + when(t.date)
                + (t.nom != null ? "\n" + t.nom : "") + (t.numero != null ? " · " + t.numero : "")
                + "\nMontant lu dans le SMS : non modifiable.", 13, MUTED, false));

        final String cleOp = Caisse.cle(t);
        final String[] choix = {l.categorie != null && !Caisse.AGENT.equals(l.categorie) && !Caisse.REPARTI.equals(l.categorie) ? l.categorie : null};
        Dettes.Engagement eng = engagementDe(cleOp);
        ChampPersonne pers = new ChampPersonne(eng != null ? eng.tiers : (t.nom != null ? t.nom : t.numero != null ? formatNumero(t.numero) : null),
                t.numero, eng != null ? eng.echeance : null);
        form.addView(text(t.sens > 0 ? "Catégorie d'entrée" : "Catégorie de sortie", 13, MUTED, false));
        LinearLayout grille = column(6);
        TextView aide = text("", 12, MUTED, false);
        categorieGrid(grille, aide, t.sens, choix, () -> pers.maj(choix[0]));
        form.addView(grille, fill());
        form.addView(aide);
        form.addView(pers.box, fill());
        pers.maj(choix[0]);

        form.addView(text("Motif", 13, MUTED, false));
        EditText motif = new EditText(this);
        motif.setInputType(InputType.TYPE_CLASS_TEXT);
        motif.setHint("ex. Vente de 2 sacs de riz");
        motif.setText(l.motif);
        form.addView(motif, fill());

        CheckBox toujours = new CheckBox(this);
        if (t.numero != null) {
            String who = t.nom != null ? t.nom : t.numero;
            toujours.setText("Toujours classer les " + (t.sens > 0 ? "entrées venant de " : "sorties vers ") + who + " dans cette catégorie");
            toujours.setChecked(regles.containsKey(Caisse.cleRegle(t.numero, t.sens)) || regles.containsKey(t.numero));
            form.addView(toujours, fill());
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog[] dlg = new AlertDialog[1];
        // Suivre cet argent (entrée) ou répartir cette sortie en plusieurs dépenses.
        boolean suivi = liens.containsKey(cleOp) || l.reparti > 0;
        Button suivre = small(suivi ? "Voir le suivi de cet argent  ›" : t.sens > 0 ? "Suivre cet argent (ce que j'en fais)  ›" : "Répartir cette sortie en dépenses  ›",
                PURPLE, v -> { if (dlg[0] != null) dlg[0].dismiss(); commencerSuivi(cleOp); });
        if (visible(Modules.SUIVI) || suivi) form.addView(suivre, fill());
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("Classer l'opération")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    if (pers.requis() && pers.nom().isEmpty()) { notice("Indiquez la personne : opération non classée.", 2); return; }
                    Long ech = pers.echeance();
                    if (ech != null && ech < 0) { notice("Date de remboursement illisible : écrivez JJ/MM/AAAA.", 2); return; }
                    final String cat = choix[0], nomP = pers.nom(), motifTxt = motif.getText().toString().trim();
                    if (Dettes.estCategorieDette(cat) || eng != null) {
                        io.execute(() -> {
                            majEngagement(Store.get(this), cleOp, cat, nomP, t.numero, t.montant, t.date, ech, motifTxt);
                            List<Dettes.Engagement> en = Store.get(this).engagements();
                            runOnUiThread(() -> { engagements = en; render(); });
                        });
                    }
                    boolean always = t.numero != null && toujours.isChecked();
                    final String cleSens = t.numero != null ? Caisse.cleRegle(t.numero, t.sens) : null;
                    if (!always && t.numero != null && (regles.containsKey(cleSens) || regles.containsKey(t.numero))) {
                        final String numero = t.numero;
                        final boolean ancienne = regles.containsKey(numero);
                        io.execute(() -> {
                            Store st = Store.get(this);
                            st.regle(cleSens, null);
                            if (ancienne) st.regle(numero, null);
                            SyncJob.soon(this);
                        });
                    }
                    classer(t, choix[0], motif.getText().toString().trim(), always && choix[0] != null);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- Espèces ----------

    private void openEspeces() {
        if (!exiger(Modules.CAISSE)) return;
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        final int[] sens = {1};
        final String[] cat = {Caisse.VENTE};
        LinearLayout grille = column(6);
        TextView aide = text("", 12, MUTED, false);

        LinearLayout sensRow = new LinearLayout(this);
        Button in = button("Entrée", false, null), out = button("Sortie", false, null);
        sensRow.addView(in, weight(false));
        sensRow.addView(out, weight(true));
        form.addView(sensRow, fill());

        form.addView(text("Montant (FCFA)", 13, MUTED, false));
        EditText montant = amountField("ex. 15000");
        form.addView(montant, fill());

        TextView catTitre = text("", 13, MUTED, false);
        final ChampPersonne[] persRef = new ChampPersonne[1];
        form.addView(catTitre);
        form.addView(grille, fill());
        form.addView(aide);
        Runnable paint = () -> {
            select(in, sens[0] > 0);
            select(out, sens[0] < 0);
            catTitre.setText(sens[0] > 0 ? "Catégorie d'entrée" : "Catégorie de sortie");
            categorieGrid(grille, aide, sens[0], cat, () -> { if (persRef[0] != null) persRef[0].maj(cat[0]); });
        };
        // En changeant de sens, une catégorie qui ne va pas avec le nouveau sens est remplacée par la plus courante.
        in.setOnClickListener(v -> { sens[0] = 1; if (!Caisse.compatible(cat[0], 1)) cat[0] = Caisse.VENTE; paint.run(); });
        ChampPersonne pers = new ChampPersonne(null, null, null);
        out.setOnClickListener(v -> { sens[0] = -1; if (!Caisse.compatible(cat[0], -1)) cat[0] = Caisse.ACHAT; paint.run(); });
        persRef[0] = pers;
        paint.run();
        form.addView(pers.box, fill());

        EditText libelle = new EditText(this);
        libelle.setInputType(InputType.TYPE_CLASS_TEXT);
        libelle.setHint("Motif (facultatif)");
        form.addView(libelle, fill());

        ScrollView svE = new ScrollView(this);
        svE.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Mouvement d'espèces")
                .setView(svE)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    Long cents = SmsParser.money(montant.getText().toString());
                    if (cents == null || cents <= 0) {
                        notice("Montant invalide : mouvement non enregistré.", 2);
                        return;
                    }
                    if (pers.requis() && pers.nom().isEmpty()) { notice("Indiquez la personne : mouvement non enregistré.", 2); return; }
                    Long ech = pers.echeance();
                    if (ech != null && ech < 0) { notice("Date de remboursement illisible : écrivez JJ/MM/AAAA.", 2); return; }
                    Caisse.Saisie s = new Caisse.Saisie();
                    s.date = System.currentTimeMillis();
                    s.mode = Caisse.ESPECES;
                    s.sens = sens[0];
                    s.montant = cents;
                    s.categorie = cat[0];
                    s.libelle = libelle.getText().toString().trim();
                    final String nomP = pers.nom();
                    io.execute(() -> {
                        Store.get(this).addSaisie(s);
                        if (Dettes.estCategorieDette(s.categorie)) majEngagement(Store.get(this), Suivi.cle(s), s.categorie, nomP, null, cents, s.date, ech, s.libelle);
                        SyncJob.soon(this);
                        runOnUiThread(() -> {
                            notice((s.sens > 0 ? "Entrée" : "Sortie") + " d'espèces de " + SmsParser.fmt(cents) + " FCFA enregistrée.", 1);
                            reload(false);
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmAnnuler(Caisse.Saisie s) {
        new AlertDialog.Builder(this)
                .setTitle("Annuler ce mouvement ?")
                .setMessage((s.sens > 0 ? "+" : "−") + SmsParser.fmt(s.montant) + " FCFA en espèces, " + when(s.date)
                        + ". Il restera visible, marqué « Annulé », et ne comptera plus dans la caisse.")
                .setPositiveButton("Annuler le mouvement", (d, w) -> io.execute(() -> {
                    Store.get(this).annulerSaisie(s.id);
                    if (s.uid != null) majEngagement(Store.get(this), Suivi.cle(s), null, null, null, 0, 0, null, null);
                    SyncJob.soon(this);
                    runOnUiThread(() -> reload(false));
                }))
                .setNegativeButton("Garder", null)
                .show();
    }

    // ---------- Fond de caisse et clôture ----------

    private void openFond() {
        if (!exiger(Modules.CAISSE)) return;
        String today = Caisse.jourDe(System.currentTimeMillis());
        Caisse.Jour j = null;
        Long last = null;
        for (Caisse.Jour x : jours) {
            if (x.jour.equals(today)) j = x;
            if (x.jour.compareTo(today) < 0 && x.compte != null) last = x.compte;
        }
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(text("Espèces présentes dans la caisse à l'ouverture d'aujourd'hui." + (last != null ? " Comptage de la dernière clôture : " + SmsParser.fmt(last) + " FCFA." : ""), 13, MUTED, false));
        EditText fond = amountField("Montant en FCFA");
        if (j != null && j.fond != null) fond.setText(SmsParser.plain(j.fond));
        else if (last != null) fond.setText(SmsParser.plain(last));
        form.addView(fond, fill());
        new AlertDialog.Builder(this)
                .setTitle("Fond de caisse")
                .setView(form)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    Long cents = SmsParser.money(fond.getText().toString());
                    if (cents == null) { notice("Montant invalide.", 2); return; }
                    io.execute(() -> {
                        Store.get(this).setFond(today, cents);
                        SyncJob.soon(this);
                        runOnUiThread(() -> reload(false));
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void openCloture() {
        if (!exiger(Modules.CAISSE)) return;
        String today = Caisse.jourDe(System.currentTimeMillis());
        Caisse.Bilan b = computeBilan(Caisse.debutJour(today), Long.MAX_VALUE);
        Caisse.Especes e = b.especes;
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(kv("Résultat du jour", (b.resultat >= 0 ? "+" : "−") + SmsParser.fmt(Math.abs(b.resultat)) + " FCFA", b.resultat >= 0 ? GOOD : BAD, true));
        form.addView(kv("Espèces théoriques", SmsParser.fmt(e.theo) + " FCFA"));
        if (!b.aClasser.isEmpty()) form.addView(text(b.aClasser.size() + " opération(s) d'aujourd'hui restent à classer (onglet « À classer »).", 13, BAD, true));
        if (e.ouverture == null) form.addView(text("Le fond de caisse d'aujourd'hui n'a pas été saisi : l'écart espèces ne pourra pas être calculé.", 13, WARN, true));
        form.addView(text("Espèces comptées dans la caisse (FCFA)", 13, MUTED, false));
        EditText compte = amountField("Montant compté");
        form.addView(compte, fill());
        EditText note = new EditText(this);
        note.setInputType(InputType.TYPE_CLASS_TEXT);
        note.setHint("Note (facultatif)");
        form.addView(note, fill());
        new AlertDialog.Builder(this)
                .setTitle("Clôturer le " + Caisse.affiche(today))
                .setView(form)
                .setPositiveButton("Clôturer", (d, w) -> {
                    Long cents = SmsParser.money(compte.getText().toString());
                    if (cents == null) { notice("Montant compté invalide : clôture non enregistrée.", 2); return; }
                    String n = note.getText().toString().trim();
                    io.execute(() -> {
                        Store.get(this).cloturer(today, cents, n, System.currentTimeMillis());
                        SyncJob.soon(this);
                        runOnUiThread(() -> {
                            setPeriod(0);
                            reload(false);
                            String ecart = e.theo == null ? "écart espèces non calculable" : ecartText(cents - e.theo).toLowerCase(Locale.FRANCE) + " en espèces";
                            new AlertDialog.Builder(this)
                                    .setTitle("Journée clôturée")
                                    .setMessage("Comptage enregistré : " + SmsParser.fmt(cents) + " FCFA, " + ecart + ". Exporter le rapport Excel du jour ?")
                                    .setPositiveButton("Exporter", (d2, w2) -> lancerExport())
                                    .setNegativeButton("Plus tard", null)
                                    .show();
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }


    // ---------- Période précise ----------

    private void pickRange() {
        Calendar start = Calendar.getInstance();
        if (customFrom > 0) start.setTimeInMillis(customFrom);
        else start.set(Calendar.DAY_OF_MONTH, 1);
        DatePickerDialog first = new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar a = Calendar.getInstance();
            a.clear();
            a.set(y, m, d, 0, 0, 0);
            final long from = a.getTimeInMillis();
            Calendar end = Calendar.getInstance();
            if (customTo > 0 && customTo >= from) end.setTimeInMillis(customTo);
            DatePickerDialog second = new DatePickerDialog(this, (v2, y2, m2, d2) -> {
                Calendar b = Calendar.getInstance();
                b.clear();
                b.set(y2, m2, d2, 23, 59, 59);
                b.set(Calendar.MILLISECOND, 999);
                long to = b.getTimeInMillis();
                long f = from;
                if (to < f) {
                    Calendar sw = Calendar.getInstance();
                    sw.setTimeInMillis(to);
                    sw.set(Calendar.HOUR_OF_DAY, 0); sw.set(Calendar.MINUTE, 0); sw.set(Calendar.SECOND, 0); sw.set(Calendar.MILLISECOND, 0);
                    to = from + 86399999L;
                    f = sw.getTimeInMillis();
                }
                customFrom = f;
                customTo = to;
                Store.savePref(this, "debut", String.valueOf(customFrom));
                Store.savePref(this, "fin", String.valueOf(customTo));
                setPeriod(CUSTOM);
            }, end.get(Calendar.YEAR), end.get(Calendar.MONTH), end.get(Calendar.DAY_OF_MONTH));
            second.setTitle("Date de fin");
            second.show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH));
        first.setTitle("Date de début");
        first.show();
    }

    // ---------- Compte et sauvegarde ----------

    /** Sauvegarde à l'ouverture si la dernière date de plus de 15 minutes (met aussi à jour le porte-monnaie). */
    private void autoSync() {
        if (!Cloud.configured() || !Cloud.loggedIn(this)) return;
        SyncJob.schedule(this);
        if (System.currentTimeMillis() - Cloud.lastSync(this) < 15 * 60000L) return;
        io.execute(() -> {
            try { Cloud.sync(this); } catch (Exception ignored) {}
            runOnUiThread(this::render);
        });
    }

    private void syncNow() {
        notice("Sauvegarde en cours…", 0);
        io.execute(() -> {
            String msg;
            int niveau;
            try {
                String r = Cloud.sync(this);
                if (Cloud.caisseAChoisir(this)) {
                    msg = "Choisissez d'abord la caisse de ce téléphone (onglet Compte) : rien n'est sauvegardé d'ici là.";
                    niveau = 2;
                } else {
                    msg = "Sauvegarde terminée : " + r;
                    niveau = 1;
                }
            } catch (Cloud.CloudException e) {
                msg = e.getMessage();
                niveau = 2;
            }
            final String m = msg;
            final int n = niveau;
            runOnUiThread(() -> { notice(m, n); reload(false); });
        });
    }

    private void doAuth(boolean create, String phoneText, String secret) {
        String p9 = Cloud.phone9(phoneText);
        if (p9 == null) { notice("Numéro invalide : saisissez un numéro camerounais à 9 chiffres (6XXXXXXXX).", 2); return; }
        if (secret.length() < 6) { notice("Le code secret doit faire au moins 6 caractères.", 2); return; }
        notice(create ? "Création du compte…" : "Connexion et récupération de vos données…", 0);
        io.execute(() -> {
            String msg;
            int niveau;
            try {
                if (create) Cloud.signUp(this, p9, secret); else Cloud.signIn(this, p9, secret);
                String r = Cloud.sync(this);
                SyncJob.schedule(this);
                if (Cloud.caisseAChoisir(this)) {
                    msg = "Connecté. Ce compte est déjà utilisé sur un autre téléphone : choisissez ci-dessous la caisse de ce téléphone.";
                    niveau = 0;
                } else {
                    msg = (create ? "Compte créé : vos données sont sauvegardées et votre premier export est offert. "
                            : "Connecté : les données de la caisse « " + Cloud.caisseNom(this) + " » sont à jour. ") + r;
                    niveau = 1;
                }
            } catch (Cloud.CloudException e) {
                msg = e.getMessage();
                niveau = 2;
            }
            final String m = msg;
            final int n = niveau;
            runOnUiThread(() -> {
                if (n == 1 && secretField != null) secretField.setText("");
                etatModules = Cloud.modules(this);
                if (n != 2 && etatModules.aChoisir()) { choixModules = true; profilChoisi = -1; cochees.clear(); }
                notice(m, n);
                reload(false);
            });
        });
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Se déconnecter ?")
                .setMessage("Les données restent sur ce téléphone et dans votre compte en ligne. La sauvegarde automatique s'arrête. Attention : si vous vous connectez ensuite à un autre compte, les données de ce téléphone y seront ajoutées.")
                .setPositiveButton("Se déconnecter", (d, w) -> io.execute(() -> {
                    SyncJob.cancel(this);
                    Cloud.signOut(this);
                    runOnUiThread(() -> {
                        codePatron = null;
                        vueToutes = false;
                        vueData = null;
                        vueKey = vueKeyTentee = "";
                        notice("Déconnecté.", 0);
                        render();
                    });
                }))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmErase() {
        new AlertDialog.Builder(this)
                .setTitle("Effacer la copie des SMS ?")
                .setMessage("La copie locale des SMS mobile money sera effacée, puis relue depuis votre messagerie. Les SMS déjà supprimés de la messagerie seront perdus. Vos catégories, motifs, espèces et clôtures sont conservés.")
                .setPositiveButton("Effacer", (d, w) -> io.execute(() -> {
                    Store.get(this).clear();
                    runOnUiThread(() -> { resetInbox(); reload(true); });
                }))
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- Porte-monnaie ----------

    private void actualiserPortefeuille() {
        notice("Mise à jour du porte-monnaie…", 0);
        io.execute(() -> {
            String err = null;
            try { Cloud.rafraichirPortefeuille(this); } catch (Cloud.CloudException e) { err = e.getMessage(); }
            final String e2 = err;
            runOnUiThread(() -> {
                if (e2 != null) notice(e2, 2);
                else notice("Porte-monnaie à jour : solde " + Tarif.fcfa(Cloud.portefeuille(this).solde) + ".", 1);
                render();
            });
        });
    }

    /** Déclaration saisie à la main. */
    private void declarerRecharge() {
        Cloud.Portefeuille w = Cloud.portefeuille(this);
        String digits = rechargeMontantManuel.getText().toString().replaceAll("\\D", "");
        long montant = digits.isEmpty() || digits.length() > 7 ? 0 : Long.parseLong(digits);
        String ref = rechargeRef.getText().toString().trim();
        if (montant < w.rechargeMin) { notice("Indiquez le montant envoyé (minimum " + Tarif.fcfa(w.rechargeMin) + ").", 2); return; }
        if (ref.replaceAll("[^A-Za-z0-9]", "").length() < 6) { notice("Recopiez l'ID de transaction complet reçu par SMS après le transfert.", 2); return; }
        declarer(rechargeOp, ref, montant);
    }

    /** Envoie la déclaration de recharge (ID trouvé dans les SMS ou saisi). */
    private void declarer(String op, String ref, long montant) {
        Cloud.Portefeuille w = Cloud.portefeuille(this);
        if (montant < w.rechargeMin) { notice("Montant trop faible : la recharge minimum est de " + Tarif.fcfa(w.rechargeMin) + ".", 2); return; }
        notice("Envoi de la déclaration…", 0);
        io.execute(() -> {
            String msg;
            int niveau;
            try {
                msg = Cloud.declarerRecharge(this, op, ref, montant);
                niveau = 1;
                Cloud.marquerDeclaree(this, ref);
            } catch (Cloud.CloudException e) {
                msg = e.getMessage();
                niveau = 2;
                if (msg != null && msg.contains("déjà été déclaré")) Cloud.marquerDeclaree(this, ref);
            }
            final String m = msg;
            final int n = niveau;
            runOnUiThread(() -> {
                if (n == 1) {
                    if (rechargeRef != null) rechargeRef.setText("");
                    if (rechargeMontantManuel != null) rechargeMontantManuel.setText("");
                    rechargeOuverte = false;
                    rechargeManuel = false;
                    Cloud.setRechargeAttente(this, null);
                }
                notice(m, n);
                render();
            });
        });
    }

    // ---------- Applications à la carte (0.11) ----------

    /** État des applications (relu après chaque synchronisation ou activation). */
    private Modules.Etat mods() {
        if (etatModules == null) etatModules = Cloud.modules(this);
        return etatModules;
    }

    /** Accès à une application. Sans serveur configuré (version de test) : tout est ouvert. */
    private Modules.Acces acces(String code) {
        if (!Cloud.configured()) return Modules.Acces.ACTIF;
        if (!Cloud.loggedIn(this)) return Modules.Acces.ABSENT;
        return mods().acces(code, System.currentTimeMillis());
    }

    private boolean actif(String code) {
        return acces(code) == Modules.Acces.ACTIF;
    }

    /** Visible (active ou en lecture seule). */
    private boolean visible(String code) {
        return acces(code) != Modules.Acces.ABSENT;
    }

    /** Vrai si l'application est active ; sinon propose de l'installer et renvoie faux. */
    private boolean exiger(String code) {
        if (actif(code)) return true;
        proposerModule(code);
        return false;
    }

    private String nomModule(String code) {
        Modules.Module m = mods().module(code);
        if (m != null) return m.nom;
        switch (code) {
            case Modules.CAISSE: return "Caisse commerçant";
            case Modules.FINANCES: return "Finances & budgets";
            case Modules.SUIVI: return "Suivi de l'argent";
            case Modules.DETTES: return "Dettes & créances";
            case Modules.MULTI: return "Multi-téléphones";
            case Modules.ANALYSE: return "Analyse";
            default: return code;
        }
    }

    /** Libellé du bouton principal d'une application ou d'un pack. */
    private String boutonModule(String code) {
        Modules.Etat e = mods();
        Modules.Module m = e.module(code);
        Modules.Abonnement a = e.abonnement(code);
        if (a == null || !a.essaiUtilise) return "Essayer " + e.essaiJours + " jours gratuits";
        if (a.fin > System.currentTimeMillis() + e.decalage) return a.renouveler ? "Active" : "Reprendre le renouvellement";
        return "Activer · " + (m != null ? Modules.prixTexte(m) : "");
    }

    /** Fenêtre de présentation d'une application qu'on n'a pas (ou plus). */
    private void proposerModule(String code) {
        if (!Cloud.configured()) return;
        if (!Cloud.loggedIn(this)) {
            notice("« " + nomModule(code) + " » demande un compte (gratuit) : créez-le dans l'onglet Compte, puis essayez l'application "
                    + mods().essaiJours + " jours gratuitement.", 0);
            showPage(PAGE_COMPTE);
            return;
        }
        Modules.Module m = mods().module(code);
        Modules.Module pack = null;
        for (Modules.Module x : mods().catalogue) if (x.estPack && x.contenu.contains(code) && (pack == null || x.prixCompte < pack.prixCompte)) pack = x;
        String msg = (m != null ? m.description : "") + "\n\n" + Modules.etatTexte(mods(), code, System.currentTimeMillis())
                + (m != null ? "\nPrix : " + Modules.prixTexte(m) + " après l'essai" + (m.enPromo() ? " (" + m.promo + ")" : "") + "." : "")
                + (m != null && m.annuel() ? "\nFormule annuelle : " + Tarif.fcfa(m.prixAnnuelCompte) + " par an." : "")
                + (pack != null ? "\n\nAussi dans le " + pack.nom + " (" + Tarif.fcfa(pack.prixCompte) + "/mois)." : "");
        new AlertDialog.Builder(this)
                .setTitle(nomModule(code))
                .setMessage(msg)
                .setPositiveButton(boutonModule(code), (d, w) -> activer(code))
                .setNeutralButton("Toutes les applications", (d, w) -> ouvrirApplications())
                .setNegativeButton("Plus tard", null)
                .show();
    }

    /** Active une application ou un pack ; confirme d'abord si de l'argent va être débité. */
    private void activer(String code) {
        activer(code, "mois");
    }

    /** periode "an" : formule annuelle, payée tout de suite pour 12 mois. */
    private void activer(String code, String periode) {
        Modules.Etat e = mods();
        Modules.Module m = e.module(code);
        Modules.Abonnement a = e.abonnement(code);
        boolean an = "an".equals(periode);
        boolean payant = an || (a != null && a.essaiUtilise && a.fin <= System.currentTimeMillis() + e.decalage);
        Runnable go = () -> {
            notice("Activation de « " + nomModule(code) + " »…", 0);
            io.execute(() -> {
                String msg;
                int n;
                try {
                    msg = Cloud.activerModule(this, code, periode);
                    n = 1;
                } catch (Cloud.CloudException ex) {
                    msg = ex.getMessage();
                    n = 2;
                }
                final String fm = msg;
                final int fn = n;
                runOnUiThread(() -> { etatModules = Cloud.modules(this); notice(fm, fn); render(); });
            });
        };
        if (!payant || m == null) { go.run(); return; }
        Cloud.Portefeuille w = Cloud.portefeuille(this);
        long prix = an ? m.prixAnnuelCompte : m.prixCompte;
        boolean enCours = a != null && a.fin > System.currentTimeMillis() + e.decalage;
        new AlertDialog.Builder(this)
                .setTitle((an ? "Formule annuelle : " : "Activer ") + m.nom + " ?")
                .setMessage(Tarif.fcfa(prix) + " seront débités de votre porte-monnaie (solde : " + Tarif.fcfa(Math.max(0, w.solde)) + ").\n\n"
                        + (an ? "L'application est active 12 mois" + (enCours ? " de plus, après la période en cours" : "")
                                + (m.economieAnnuelle() > 0 ? " : vous économisez " + Tarif.fcfa(m.economieAnnuelle()) + " par rapport au mois" : "")
                                + ". Elle se renouvelle chaque année si le solde suffit."
                              : "L'application est active un mois et se renouvelle chaque mois si le solde suffit.")
                        + " Vous pouvez arrêter le renouvellement à tout moment.")
                .setPositiveButton("Activer", (d, x) -> go.run())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void arreter(String code) {
        new AlertDialog.Builder(this)
                .setTitle("Arrêter le renouvellement ?")
                .setMessage("« " + nomModule(code) + " » reste active jusqu'à la fin de la période payée, puis passe en lecture seule. Vos données sont gardées.")
                .setPositiveButton("Arrêter", (d, w) -> io.execute(() -> {
                    String msg;
                    try {
                        Cloud.arreterModule(this, code);
                        msg = "Renouvellement arrêté.";
                    } catch (Cloud.CloudException ex) {
                        msg = ex.getMessage();
                    }
                    final String fm = msg;
                    runOnUiThread(() -> { etatModules = Cloud.modules(this); notice(fm, 0); render(); });
                }))
                .setNegativeButton("Garder", null)
                .show();
    }

    /** Carte qui présente une application absente (ou en lecture seule) à l'endroit où elle servirait. */
    private View promoModule(String code, String accroche) {
        LinearLayout c = card();
        Modules.Acces ac = acces(code);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(nomModule(code), 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(chip(ac == Modules.Acces.LECTURE ? "Lecture seule" : "Application", ac == Modules.Acces.LECTURE ? WARN : PURPLE,
                ac == Modules.Acces.LECTURE ? WARN_BG : SURFACE));
        c.addView(head, fill());
        c.addView(text(accroche, 13, MUTED, false));
        c.addView(button(Cloud.loggedIn(this) ? boutonModule(code) : "Créer un compte pour l'essayer", true, v -> {
            if (Cloud.loggedIn(this)) activer(code); else proposerModule(code);
        }), fill());
        return c;
    }

    /** Bandeau « lecture seule » au-dessus d'une partie dont l'abonnement est terminé. */
    private View bandeauLecture(String code) {
        TextView t = text("Lecture seule : « " + nomModule(code) + " » n'est plus active. Vos données restent visibles ; touchez ici pour la réactiver.",
                13, WARN, true);
        t.setBackground(box(WARN_BG, 0, 10));
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        t.setOnClickListener(v -> activer(code));
        return t;
    }

    /** Rappels en haut de la Caisse : mois offert qui se termine, solde trop bas pour les renouvellements. */
    private View bandeauModules() {
        if (!Cloud.configured() || !Cloud.loggedIn(this)) return null;
        long now = System.currentTimeMillis();
        Modules.Etat e = mods();
        String msg = e.rappel(now);
        long offre = e.finOffre(now);
        if (msg == null && offre > 0)
            msg = "Vos applications offertes s'arrêtent le " + Modules.date(offre) + ". Choisissez celles que vous gardez  ›";
        if (msg == null) return null;
        TextView t = text(msg, 13, WARN, true);
        t.setBackground(box(WARN_BG, 0, 10));
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        t.setOnClickListener(v -> ouvrirApplications());
        return t;
    }

    // ---------- Agent IA (0.13) ----------

    /** Propositions de catégorie pour les opérations à classer : apprises sur le téléphone, complétées par l'IA. */
    private Map<String, Classeur.Proposition> propositions() {
        Map<String, Classeur.Proposition> out = new HashMap<>();
        if (result == null || bilan == null || !actif(Modules.ASSISTANT)) return out;
        Classeur.Modele m = Classeur.apprendre(result, classements);
        for (Classeur.Proposition p : Classeur.proposer(m, bilan.aClasser)) out.put(p.cle, p);
        for (Map.Entry<String, Classeur.Proposition> e : propositionsIa.entrySet()) if (!out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** En-tête de la page À classer : tout accepter, proposer avec l'IA ; ou présentation de l'application. */
    private void enteteClassementIa(Map<String, Classeur.Proposition> props) {
        if (!Cloud.configured()) return;
        if (!visible(Modules.ASSISTANT)) {
            if (bilan.aClasser.size() >= 5) page.addView(promoModule(Modules.ASSISTANT,
                    "Laissez l'assistant proposer la catégorie de chaque opération (il apprend sur vos classements) et répondre à vos questions sur vos chiffres."), fill());
            return;
        }
        if (!actif(Modules.ASSISTANT)) { page.addView(bandeauLecture(Modules.ASSISTANT), fill()); return; }
        int surs = 0, sans = 0;
        for (Caisse.Ligne l : bilan.aClasser) {
            Classeur.Proposition p = l.sms == null ? null : props.get(Caisse.cle(l.sms));
            if (p == null) sans++; else if (p.confiance >= 90) surs++;
        }
        LinearLayout act = new LinearLayout(this);
        final int fs = surs;
        if (surs > 0) act.addView(button("Tout accepter ≥ 90 % (" + surs + ")", true, v -> accepterTout(props, 90, fs)), weight(sans == 0));
        if (sans > 0) act.addView(button("Proposer avec l'IA (" + Math.min(40, sans) + ")", surs == 0, v -> proposerIa(props)), weight(true));
        if (act.getChildCount() > 0) page.addView(act, fill());
    }

    private void accepterTout(Map<String, Classeur.Proposition> props, int seuil, int nb) {
        new AlertDialog.Builder(this)
                .setTitle("Classer " + nb + " opération(s) ?")
                .setMessage("Les opérations dont la proposition est sûre à " + seuil + " % au moins prennent la catégorie proposée. Vous pourrez toujours les corriger.")
                .setPositiveButton("Classer", (d, w) -> io.execute(() -> {
                    Store st = Store.get(this);
                    int n = 0;
                    for (Caisse.Ligne l : bilan.aClasser) {
                        if (l.sms == null) continue;
                        Classeur.Proposition p = props.get(Caisse.cle(l.sms));
                        if (p == null || p.confiance < seuil) continue;
                        st.classer(p.cle, p.categorie, l.motif);
                        n++;
                    }
                    SyncJob.soon(this);
                    final int fn = n;
                    runOnUiThread(() -> { notice(fn + " opération(s) classée(s).", 1); reload(false); });
                }))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void proposerIa(Map<String, Classeur.Proposition> props) {
        List<Txn> ops = new ArrayList<>();
        for (Caisse.Ligne l : bilan.aClasser) {
            if (l.sms == null || props.containsKey(Caisse.cle(l.sms))) continue;
            if (ops.size() >= 40) break;
            ops.add(l.sms);
        }
        if (ops.isEmpty()) return;
        notice("L'assistant examine " + ops.size() + " opération(s)… (1 question)", 0);
        io.execute(() -> {
            String m;
            int n;
            try {
                Map<String, Classeur.Proposition> r = Cloud.classerIa(this, ops);
                runOnUiThread(() -> propositionsIa.putAll(r));
                m = r.size() + " proposition(s) de l'assistant : vérifiez puis acceptez d'un toucher.";
                n = 1;
            } catch (Cloud.CloudException e) {
                m = e.getMessage();
                n = 2;
            }
            final String fm = m;
            final int fn = n;
            runOnUiThread(() -> { notice(fm, fn); render(); });
        });
    }

    /** Ligne « Proposition : Vente 92 % · Accepter » sous une opération à classer. */
    private View propositionRow(Caisse.Ligne l, Classeur.Proposition p) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(box(GOOD_BG, 0, 10));
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        r.addView(text("✨ " + Caisse.categorieNom(p.categorie) + " · " + p.confiance + " %" + ("ia".equals(p.source) ? " (IA)" : ""), 13, ACCENT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(small("Accepter", ACCENT, v -> classer(l.sms, p.categorie, l.motif, false)));
        return r;
    }

    /** Bouton « Faire lire par l'IA » d'un SMS inconnu (gratuit, compte nécessaire). */
    private View lireParIa(Txn u) {
        return small("✨ Faire lire ce SMS par l'IA (gratuit)", ACCENT, v -> {
            if (!Cloud.configured() || !Cloud.loggedIn(this)) { notice("Créez un compte (gratuit) pour faire lire vos SMS par l'IA.", 0); return; }
            notice("L'IA lit le SMS (numéros et noms masqués)…", 0);
            io.execute(() -> {
                String m;
                int n;
                try { m = Cloud.lireSmsIa(this, u); n = m.startsWith("SMS compris") ? 1 : 0; } catch (Cloud.CloudException e) { m = e.getMessage(); n = 2; }
                final String fm = m;
                final int fn = n;
                runOnUiThread(() -> { notice(fm, fn); reload(true); });
            });
        });
    }

    /** Bandeau rouge après un faux SMS de paiement (48 heures, ou jusqu'à fermeture). */
    /** Paiement reçu non confirmé (version 0.17) : vérifié et bon, ou faux à écarter. */
    private View alerteDouteux(Txn t) {
        LinearLayout b = column(4);
        b.setBackground(box(BAD_BG, 0, 10));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        String ussd = Caisse.MOMO.equals(t.op) ? "*126#" : "#150#";
        b.addView(text("⚠  Paiement non confirmé : le solde annoncé ne suit pas vos SMS précédents. Vérifiez au " + ussd + ".", 12, BAD, true));
        LinearLayout act = new LinearLayout(this);
        act.addView(small("Vérifié, c'est bon", GOOD, v -> {
            retirerDouteux(Caisse.cle(t));
            Store.savePref(this, "faux_sms", "");
            notice("Paiement confirmé.", 1);
            render();
        }), weight(false));
        act.addView(small("C'est un faux : l'écarter", BAD, v -> new AlertDialog.Builder(this)
                .setTitle("Écarter ce SMS ?")
                .setMessage("Il ne comptera plus dans vos soldes, votre caisse ni vos exports. Faites-le seulement si votre solde (" + ussd
                        + ") montre que l'argent n'est pas arrivé.")
                .setPositiveButton("Écarter", (x, y) -> io.execute(() -> {
                    Store.get(this).ecarterFaux(t.sender, t.raw);
                    runOnUiThread(() -> {
                        retirerDouteux(Caisse.cle(t));
                        Store.savePref(this, "faux_sms", "");
                        analysedKey = "";
                        notice("Faux SMS écarté.", 1);
                        reload(false);
                    });
                }))
                .setNegativeButton("Annuler", null)
                .show()), weight(false));
        b.addView(act, fill());
        return b;
    }

    private View bandeauFauxSms() {
        String v = Store.pref(this, "faux_sms", "");
        int i = v.indexOf('|');
        if (i <= 0) return null;
        long quand;
        try { quand = Long.parseLong(v.substring(0, i)); } catch (NumberFormatException e) { return null; }
        if (System.currentTimeMillis() - quand > 48L * 3600 * 1000) return null;
        LinearLayout b = column(4);
        b.setBackground(box(BAD_BG, 0, 12));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.addView(text((v.substring(i + 1).startsWith("Paiement") ? "⚠  Paiement non confirmé · " : "⚠  Faux SMS de paiement probable · ") + when(quand), 14, BAD, true));
        b.addView(text(v.substring(i + 1), 13, INK, false));
        b.addView(small("J'ai compris", BAD, x -> { Store.savePref(this, "faux_sms", ""); render(); }));
        return b;
    }

    // ----- Assistant qui répond -----

    // ----- Float agent (0.17) -----

    private View floatCard() {
        long now = System.currentTimeMillis();
        List<String> ops = new ArrayList<>();
        if (agent(Caisse.OM)) ops.add(Caisse.OM);
        if (agent(Caisse.MOMO)) ops.add(Caisse.MOMO);
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Float agent", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(small("Seuils", INK, v -> ouvrirSeuilsFloat(ops)));
        c.addView(head, fill());
        List<Flotte.Etat> etats = new ArrayList<>();
        for (String op : ops) etats.add(Flotte.electronique(result, op, Store.prefLong(this, "seuil_float_" + op, 0) * 100, now));
        Caisse.Especes e = computeBilan(Caisse.debutJour(Caisse.jourDe(now)), now).especes;
        Long esp = e.compte != null ? e.compte : (e.ouverture != null || e.entrees > 0 || e.sorties > 0 || e.depotsClients > 0 ? (Long) e.theo : null);
        etats.add(Flotte.especes(result, ops, esp, Store.prefLong(this, "seuil_float_" + Caisse.ESPECES, 0) * 100, now));
        for (Flotte.Etat f : etats) {
            LinearLayout row = column(2);
            row.setPadding(0, dp(5), 0, dp(5));
            LinearLayout h = new LinearLayout(this);
            h.addView(text(f.nom(), 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView v = text(f.solde == null ? "—" : SmsParser.fmt(f.solde) + " FCFA", 14, f.bas ? BAD : INK, true);
            v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            h.addView(v);
            row.addView(h, fill());
            row.addView(text(Flotte.texte(f), 12, MUTED, false));
            if (f.seuil > 0) row.addView(text("Seuil d'alerte : " + SmsParser.fmt(f.seuil) + (f.manuel ? "" : " (une journée moyenne)"), 11, MUTED, false));
            String al = Flotte.alerte(f);
            if (al != null) row.addView(text("⚠  " + al, 13, BAD, true));
            c.addView(row, fill());
        }
        c.addView(text("Les dépôts clients consomment votre float électronique ; les retraits clients, vos espèces. "
                + "Une notification vous prévient quand un compte agent passe sous son seuil.", 11, MUTED, false));
        return c;
    }

    private void ouvrirSeuilsFloat(List<String> ops) {
        LinearLayout form = column(8);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        List<String> comptes = new ArrayList<>(ops);
        comptes.add(Caisse.ESPECES);
        Map<String, EditText> champs = new HashMap<>();
        for (String k : comptes) {
            form.addView(label(Caisse.ESPECES.equals(k) ? "Espèces (retraits clients)" : Txn.opName(k) + " (dépôts clients)"));
            EditText f = amountField("Seuil en FCFA (vide = automatique)");
            f.setInputType(InputType.TYPE_CLASS_NUMBER);
            long v = Store.prefLong(this, "seuil_float_" + k, 0);
            if (v > 0) f.setText(String.valueOf(v));
            form.addView(f, fill());
            champs.put(k, f);
        }
        form.addView(text("Automatique : une journée moyenne de dépôts (ou de retraits) des 7 derniers jours.", 12, MUTED, false));
        new AlertDialog.Builder(this)
                .setTitle("Seuils d'alerte du float")
                .setView(form)
                .setPositiveButton("Enregistrer", (x, y) -> {
                    for (Map.Entry<String, EditText> en : champs.entrySet()) {
                        String d = en.getValue().getText().toString().replaceAll("\\D", "");
                        Store.savePref(this, "seuil_float_" + en.getKey(), d.isEmpty() || d.length() > 9 ? "0" : d);
                    }
                    notice("Seuils enregistrés.", 1);
                    render();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ----- Épargne et tontines (0.17, gratuites) -----

    private boolean epargneOuvert;
    private String projetOuvert;

    private List<Epargne.Projet> projets() {
        List<Epargne.Projet> out = new ArrayList<>();
        for (Epargne.Projet p : Store.get(this).projetsEpargne()) if (!p.annule) out.add(p);
        return out;
    }

    private Epargne.Projet projet(String id) {
        for (Epargne.Projet p : Store.get(this).projetsEpargne()) if (p.id.equals(id)) return p;
        return null;
    }

    private View epargneCard() {
        long now = System.currentTimeMillis();
        List<Epargne.Projet> ps = projets();
        List<Epargne.Versement> vs = Store.get(this).versementsEpargne();
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("🐷 Épargne & tontines", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(chip("Gratuit", GOOD, GOOD_BG));
        c.addView(head, fill());
        if (ps.isEmpty()) {
            c.addView(text("Suivez un objectif d'épargne (rentrée, loyer, marchandise…) ou vos tontines : cotisations, tour de chacun, ce que vous avez reçu.", 13, MUTED, false));
        } else {
            for (String r : Epargne.rappels(ps, vs, now)) c.addView(text("🔔  " + r, 13, WARN, true));
            for (Epargne.Projet p : ps) c.addView(resumeProjet(p, vs, now), fill());
        }
        LinearLayout act = new LinearLayout(this);
        act.addView(small("+ Objectif d'épargne", ACCENT, v -> ouvrirProjet(null, Epargne.OBJECTIF)), weight(false));
        act.addView(small("+ Tontine", ACCENT, v -> ouvrirProjet(null, Epargne.TONTINE)), weight(false));
        c.addView(act, fill());
        return c;
    }

    private View resumeProjet(Epargne.Projet p, List<Epargne.Versement> vs, long now) {
        LinearLayout r = column(2);
        r.setPadding(0, dp(6), 0, dp(6));
        LinearLayout h = new LinearLayout(this);
        h.addView(text((p.tontine() ? "Tontine · " : "") + p.nom, 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.addView(text("›", 16, MUTED, true));
        r.addView(h, fill());
        if (p.tontine()) {
            Epargne.EtatTontine e = Epargne.tontine(p, vs, now);
            String prochaine = e.prochaine == null ? "" : "Prochaine séance le " + Modules.date(e.prochaine.date) + " · tour de "
                    + (e.prochaine.monTour(p) ? "vous" : e.prochaine.nomBeneficiaire);
            r.addView(text(prochaine, 12, MUTED, false));
            r.addView(text("Cotisé " + SmsParser.fmt(e.cotise) + " · reçu " + SmsParser.fmt(e.recu)
                    + (e.monTour != null && (e.prochaine == null || e.monTour.n != e.prochaine.n) ? " · votre tour le " + Modules.date(e.monTour.date) : ""), 12, MUTED, false));
        } else {
            Epargne.EtatObjectif e = Epargne.objectif(p, vs, now);
            r.addView(barre(SmsParser.fmt(e.epargne) + " / " + SmsParser.fmt(p.montant), e.epargne, p.montant, e.atteint ? GOOD : ACCENT, e.pourcent + " %"), fill());
            if (e.atteint) r.addView(text("Objectif atteint 🎉", 12, GOOD, true));
            else if (e.parMois != null) r.addView(text("Mettez " + SmsParser.fmt(e.parMois) + " par mois (" + SmsParser.fmt(e.parSemaine) + " par semaine) pour le " + Modules.date(p.fin), 12, MUTED, false));
        }
        r.setOnClickListener(v -> { fermerDetail(); projetOuvert = p.id; pageIdx = PAGE_FINANCES; render(); });
        return r;
    }

    /** Création (p == null) ou modification d'un objectif ou d'une tontine. */
    private void ouvrirProjet(Epargne.Projet p, String type) {
        boolean tontine = Epargne.TONTINE.equals(p != null ? p.type : type);
        LinearLayout form = column(8);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText nom = new EditText(this);
        nom.setSingleLine(true);
        nom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nom.setHint(tontine ? "Nom (ex. Njangi du marché)" : "Nom (ex. Rentrée scolaire)");
        EditText montant = amountField(tontine ? "Cotisation par séance (FCFA)" : "Montant à atteindre (FCFA)");
        montant.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText date = new EditText(this);
        date.setSingleLine(true);
        date.setHint(tontine ? "Date de la 1re séance (JJ/MM/AAAA)" : "À atteindre le (JJ/MM/AAAA, facultatif)");
        date.setInputType(InputType.TYPE_CLASS_DATETIME);
        final String[] freq = {p != null ? p.frequence : Epargne.MOIS};
        EditText membres = new EditText(this);
        membres.setHint("Membres dans l'ordre du tour, un par ligne (écrivez « Moi » pour vous)");
        membres.setMinLines(4);
        membres.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE);
        if (p != null) {
            nom.setText(p.nom);
            montant.setText(String.valueOf(p.montant / 100));
            if (tontine) date.setText(df.format(new Date(p.debut)));
            else if (p.fin != null) date.setText(df.format(new Date(p.fin)));
            List<String> ms = new ArrayList<>(p.membres);
            if (p.moi >= 0 && p.moi < ms.size()) ms.set(p.moi, "Moi");
            membres.setText(String.join("\n", ms));
        } else if (tontine) {
            date.setText(df.format(new Date()));
            membres.setText("Moi\n");
        }
        form.addView(nom, fill());
        form.addView(montant, fill());
        form.addView(date, fill());
        if (tontine) {
            form.addView(label("Séances"));
            LinearLayout fr = new LinearLayout(this);
            List<Button> bts = new ArrayList<>();
            String[][] choix = {{Epargne.SEMAINE, "Chaque semaine"}, {Epargne.QUINZAINE, "2 semaines"}, {Epargne.MOIS, "Chaque mois"}};
            for (String[] ch : choix) {
                Button b = small(ch[1], ch[0].equals(freq[0]) ? ACCENT : INK, null);
                b.setOnClickListener(v -> {
                    freq[0] = ch[0];
                    for (int i = 0; i < bts.size(); i++) bts.get(i).setTextColor(choix[i][0].equals(freq[0]) ? ACCENT : INK);
                });
                bts.add(b);
                fr.addView(b, weight(false));
            }
            form.addView(fr, fill());
            form.addView(membres, fill());
            form.addView(text("À chaque séance, c'est le tour du membre suivant de la liste. Laissez « Moi » de côté si vous tenez seulement les comptes.", 12, MUTED, false));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(p != null ? "Modifier « " + p.nom + " »" : tontine ? "Nouvelle tontine" : "Nouvel objectif d'épargne")
                .setView(sv)
                .setPositiveButton("Enregistrer", (x, y) -> {
                    String n = nom.getText().toString().trim();
                    String dg = montant.getText().toString().replaceAll("\\D", "");
                    if (n.isEmpty() || dg.isEmpty() || dg.length() > 10) { notice("Indiquez un nom et un montant.", 2); return; }
                    Long quand = null;
                    String ds = date.getText().toString().trim();
                    if (!ds.isEmpty()) {
                        try { df.setLenient(false); quand = df.parse(ds).getTime(); } catch (Exception ex) { notice("Date à écrire JJ/MM/AAAA (ex. 15/01/2027).", 2); return; }
                    }
                    Epargne.Projet q = p != null ? p : new Epargne.Projet();
                    if (q.id == null) q.id = java.util.UUID.randomUUID().toString();
                    q.type = tontine ? Epargne.TONTINE : Epargne.OBJECTIF;
                    q.nom = n;
                    q.montant = Long.parseLong(dg) * 100;
                    if (tontine) {
                        q.debut = quand != null ? quand : Caisse.debutJour(Caisse.jourDe(System.currentTimeMillis()));
                        q.frequence = freq[0];
                        q.membres.clear();
                        q.moi = -1;
                        for (String m : membres.getText().toString().split("\n")) {
                            String mm = m.trim();
                            if (mm.isEmpty()) continue;
                            if (q.moi < 0 && (mm.equalsIgnoreCase("moi") || mm.equalsIgnoreCase("moi-même"))) { q.moi = q.membres.size(); mm = "Moi"; }
                            q.membres.add(mm);
                        }
                        if (q.membres.size() < 2) { notice("Une tontine a au moins deux membres.", 2); return; }
                    } else {
                        if (q.debut == 0) q.debut = System.currentTimeMillis();
                        q.fin = quand == null ? null : quand + 86400000L - 1;
                    }
                    io.execute(() -> {
                        Store.get(this).setProjetEpargne(q);
                        SyncJob.soon(this);
                        runOnUiThread(() -> { notice("« " + n + " » enregistré.", 1); projetOuvert = q.id; pageIdx = PAGE_FINANCES; render(); });
                    });
                })
                .setNegativeButton("Annuler", null);
        if (p != null) {
            d.setNeutralButton("Supprimer", (x, y) -> new AlertDialog.Builder(this)
                    .setTitle("Supprimer « " + p.nom + " » ?")
                    .setMessage("Ses versements ne seront plus suivis. Les opérations classées en Épargne gardent leur catégorie.")
                    .setPositiveButton("Supprimer", (x2, y2) -> io.execute(() -> {
                        p.annule = true;
                        Store.get(this).setProjetEpargne(p);
                        SyncJob.soon(this);
                        runOnUiThread(() -> { projetOuvert = null; notice("« " + p.nom + " » supprimé.", 0); render(); });
                    }))
                    .setNegativeButton("Annuler", null)
                    .show());
        }
        d.show();
    }

    /**
     * Versement (sens +1) ou retrait / tour reçu (sens -1). membre et seance : tontine (membre = moi pour ses propres
     * cotisations). Propose les opérations récentes de même sens pour les relier.
     */
    private void ouvrirVersement(Epargne.Projet p, int sens, int membre, int seance, long montantDefaut, String titre) {
        LinearLayout form = column(8);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText montant = amountField("Montant (FCFA)");
        montant.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (montantDefaut > 0) montant.setText(String.valueOf(montantDefaut / 100));
        EditText motif = new EditText(this);
        motif.setSingleLine(true);
        motif.setHint("Note (facultatif)");
        form.addView(montant, fill());
        form.addView(motif, fill());
        final String[] lien = {null};
        final long[] dateLien = {0};
        boolean propre = !p.tontine() || membre == p.moi;
        if (propre && result != null) {
            // Opérations des 14 derniers jours, de même sens, pas encore reliées à une épargne.
            java.util.Set<String> deja = new java.util.HashSet<>();
            for (Epargne.Versement v : Store.get(this).versementsEpargne()) if (!v.annule && v.cle != null) deja.add(v.cle);
            long now = System.currentTimeMillis();
            List<Txn> props = new ArrayList<>();
            for (Txn t : result.tx) {
                if (t.kind != Txn.TX || t.isBalanceCheck() || t.date < now - 14 * 86400000L || deja.contains(Caisse.cle(t))) continue;
                if ((sens > 0 && t.sens < 0) || (sens < 0 && t.sens > 0)) props.add(t);
            }
            props.sort((a, b) -> Long.compare(b.date, a.date));
            if (!props.isEmpty()) {
                form.addView(label(sens > 0 ? "Payé par une de ces opérations ?" : "Reçu par une de ces opérations ?"));
                LinearLayout liste = column(2);
                List<TextView> items = new ArrayList<>();
                for (int i = 0; i < Math.min(6, props.size()); i++) {
                    Txn t = props.get(i);
                    String who = t.nom != null ? t.nom : t.numero != null ? t.numero : "";
                    TextView it = text("○  " + opShort(t.op) + " " + SmsParser.fmt(t.montant) + " · " + when(t.date) + (who.isEmpty() ? "" : " · " + who), 13, INK, false);
                    it.setPadding(0, dp(5), 0, dp(5));
                    it.setOnClickListener(v -> {
                        boolean choisi = !Caisse.cle(t).equals(lien[0]);
                        lien[0] = choisi ? Caisse.cle(t) : null;
                        dateLien[0] = choisi ? t.date : 0;
                        for (int k = 0; k < items.size(); k++) {
                            String tx = items.get(k).getText().toString().substring(1);
                            items.get(k).setText((items.get(k) == it && choisi ? "●" : "○") + tx);
                        }
                        if (choisi) montant.setText(String.valueOf(t.montant / 100));
                    });
                    items.add(it);
                    liste.addView(it, fill());
                }
                form.addView(liste, fill());
                form.addView(text("L'opération choisie sera classée « Épargne / tontine » si elle n'est pas encore classée.", 11, MUTED, false));
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        new AlertDialog.Builder(this)
                .setTitle(titre)
                .setView(sv)
                .setPositiveButton("Enregistrer", (x, y) -> {
                    String dg = montant.getText().toString().replaceAll("\\D", "");
                    if (dg.isEmpty() || dg.length() > 10 || Long.parseLong(dg) == 0) { notice("Indiquez le montant.", 2); return; }
                    Epargne.Versement v = new Epargne.Versement();
                    v.id = java.util.UUID.randomUUID().toString();
                    v.projet = p.id;
                    v.sens = sens;
                    v.membre = membre;
                    v.seance = seance;
                    v.montant = Long.parseLong(dg) * 100;
                    v.date = dateLien[0] > 0 ? dateLien[0] : System.currentTimeMillis();
                    v.cle = lien[0];
                    v.motif = motif.getText().toString().trim();
                    io.execute(() -> {
                        Store st = Store.get(this);
                        st.setVersementEpargne(v);
                        if (v.cle != null && !classements.containsKey(v.cle)) st.classer(v.cle, Caisse.EPARGNE, p.nom);
                        SyncJob.soon(this);
                        runOnUiThread(() -> { notice("Enregistré : " + SmsParser.fmt(v.montant) + " FCFA.", 1); reload(false); });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void annulerVersement(Epargne.Versement v) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer ce mouvement ?")
                .setMessage(SmsParser.fmt(v.montant) + " FCFA du " + Modules.date(v.date) + ".")
                .setPositiveButton("Supprimer", (x, y) -> io.execute(() -> {
                    v.annule = true;
                    Store.get(this).setVersementEpargne(v);
                    SyncJob.soon(this);
                    runOnUiThread(this::render);
                }))
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Coche ou décoche la cotisation d'un membre à une séance (tenue des comptes de la tontine). */
    private void basculerCotisation(Epargne.Projet p, int seance, int membre) {
        io.execute(() -> {
            Store st = Store.get(this);
            Epargne.Versement trouve = null;
            for (Epargne.Versement v : st.versementsEpargne())
                if (!v.annule && p.id.equals(v.projet) && v.seance == seance && v.membre == membre && v.sens > 0) trouve = v;
            if (trouve != null) {
                trouve.annule = true;
                st.setVersementEpargne(trouve);
            } else {
                Epargne.Versement v = new Epargne.Versement();
                v.id = java.util.UUID.randomUUID().toString();
                v.projet = p.id;
                v.sens = 1;
                v.membre = membre;
                v.seance = seance;
                v.montant = p.montant;
                v.date = System.currentTimeMillis();
                st.setVersementEpargne(v);
            }
            SyncJob.soon(this);
            runOnUiThread(this::render);
        });
    }

    private void projetPage() {
        Epargne.Projet p = projet(projetOuvert);
        page.addView(retour("Finances"));
        if (p == null || p.annule) { page.addView(empty("Ce projet a été supprimé.")); return; }
        long now = System.currentTimeMillis();
        List<Epargne.Versement> vs = Store.get(this).versementsEpargne();
        List<Epargne.Versement> miens = new ArrayList<>();
        for (Epargne.Versement v : vs) if (!v.annule && p.id.equals(v.projet)) miens.add(v);
        miens.sort((a, b) -> Long.compare(b.date, a.date));
        if (p.tontine()) {
            Epargne.EtatTontine e = Epargne.tontine(p, vs, now);
            pageTitle("Tontine · " + p.nom, SmsParser.fmt(p.montant) + " FCFA " + Epargne.frequenceNom(p.frequence) + " · " + p.membres.size()
                    + " membres · " + SmsParser.fmt(p.pot()) + " FCFA par tour");
            LinearLayout s = card();
            if (p.moi >= 0) {
                s.addView(kvSmall("Cotisé", SmsParser.fmt(e.cotise)));
                s.addView(kvSmall("Reçu à mon tour", SmsParser.fmt(e.recu)));
                s.addView(kvSmall(e.net() >= 0 ? "J'ai reçu en plus" : "Il me reste à recevoir", SmsParser.fmt(Math.abs(e.net()))));
                if (e.monTour != null) s.addView(text("Votre tour : séance du " + Modules.date(e.monTour.date), 14, ACCENT, true));
                if (e.retards > 0) s.addView(text("⚠  " + e.retards + " cotisation(s) non notée(s) aux séances passées.", 13, WARN, true));
            } else {
                s.addView(text("Vous tenez les comptes de cette tontine sans en être membre.", 13, MUTED, false));
            }
            s.addView(small("Modifier", INK, v -> ouvrirProjet(p, null)));
            page.addView(s, fill());
            page.addView(label("Séances"));
            List<Epargne.Seance> ordre = new ArrayList<>(e.seances);
            java.util.Collections.reverse(ordre);
            for (Epargne.Seance se : ordre) {
                LinearLayout c = card();
                boolean courante = e.prochaine != null && se.n == e.prochaine.n;
                LinearLayout h = new LinearLayout(this);
                h.setGravity(Gravity.CENTER_VERTICAL);
                h.addView(text("Séance " + (se.n + 1) + " · " + Modules.date(se.date), 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                if (courante) h.addView(chip("Prochaine", GOOD, GOOD_BG));
                else if (se.passee) h.addView(chip("Passée", MUTED, BG));
                c.addView(h, fill());
                c.addView(text(se.monTour(p) ? "★ C'est votre tour (" + SmsParser.fmt(p.pot()) + " FCFA)" : "Tour de " + se.nomBeneficiaire, 13, se.monTour(p) ? GOOD : MUTED, se.monTour(p)));
                c.addView(text(se.payes + " / " + p.membres.size() + " ont cotisé", 12, MUTED, false));
                LinearLayout act = new LinearLayout(this);
                if (p.moi >= 0) {
                    if (se.moiPaye(p)) act.addView(small("✓ J'ai cotisé", GOOD, v -> basculerCotisation(p, se.n, p.moi)), weight(false));
                    else act.addView(small("J'ai cotisé", ACCENT, v -> ouvrirVersement(p, 1, p.moi, se.n, p.montant, "Ma cotisation · séance du " + Modules.date(se.date))), weight(false));
                    if (se.monTour(p)) {
                        if (se.recu > 0) act.addView(small("✓ Reçu " + SmsParser.fmt(se.recu), GOOD, null), weight(false));
                        else act.addView(small("J'ai reçu", GOOD, v -> ouvrirVersement(p, -1, p.moi, se.n, p.pot(), "Mon tour · séance du " + Modules.date(se.date))), weight(false));
                    }
                }
                c.addView(act, fill());
                // Tenue des comptes : qui a cotisé.
                LinearLayout qui = column(0);
                for (int i = 0; i < p.membres.size(); i++) {
                    if (i == p.moi) continue;
                    final int membre = i;
                    CheckBox cb = new CheckBox(this);
                    cb.setText(p.membres.get(i) + (i == se.beneficiaire ? " (bénéficiaire)" : ""));
                    cb.setChecked(se.paye[i]);
                    cb.setOnCheckedChangeListener((b, coche) -> basculerCotisation(p, se.n, membre));
                    qui.addView(cb, fill());
                }
                if (qui.getChildCount() > 0 && (se.passee || courante)) {
                    c.addView(label("Qui a cotisé"));
                    c.addView(qui, fill());
                }
                page.addView(c, fill());
            }
        } else {
            Epargne.EtatObjectif e = Epargne.objectif(p, vs, now);
            pageTitle(p.nom, "Objectif : " + SmsParser.fmt(p.montant) + " FCFA" + (p.fin != null ? " pour le " + Modules.date(p.fin) : ""));
            LinearLayout s = card();
            TextView big = text(SmsParser.fmt(e.epargne) + " FCFA", 24, e.atteint ? GOOD : INK, true);
            big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            s.addView(big);
            s.addView(barre("Épargné", e.epargne, p.montant, e.atteint ? GOOD : ACCENT, e.pourcent + " %"), fill());
            if (e.atteint) s.addView(text("Objectif atteint 🎉", 14, GOOD, true));
            else {
                s.addView(kvSmall("Reste", SmsParser.fmt(e.reste)));
                if (e.parMois != null) s.addView(text("Pour y arriver à temps : " + SmsParser.fmt(e.parMois) + " par mois, ou " + SmsParser.fmt(e.parSemaine) + " par semaine.", 13, ACCENT, true));
                if (e.enRetard) s.addView(text("⚠  La date est passée.", 13, WARN, true));
            }
            LinearLayout act = new LinearLayout(this);
            act.addView(small("+ Verser", ACCENT, v -> ouvrirVersement(p, 1, -1, -1, e.parMois != null ? e.parMois : 0, "Verser · " + p.nom)), weight(true));
            act.addView(small("− Retirer", INK, v -> ouvrirVersement(p, -1, -1, -1, 0, "Retirer · " + p.nom)), weight(false));
            act.addView(small("Modifier", INK, v -> ouvrirProjet(p, null)), weight(false));
            s.addView(act, fill());
            page.addView(s, fill());
        }
        page.addView(label("Mouvements"));
        LinearLayout l = card();
        if (miens.isEmpty()) l.addView(text("Aucun mouvement pour l'instant.", 13, MUTED, false));
        for (Epargne.Versement v : miens) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            String qui = p.tontine() && v.membre >= 0 && v.membre < p.membres.size() ? p.membres.get(v.membre) : "";
            String desc = Modules.date(v.date) + (qui.isEmpty() ? "" : " · " + qui) + (v.seance >= 0 ? " · séance " + (v.seance + 1) : "")
                    + (v.cle != null ? " · opération reliée" : "") + (v.motif.isEmpty() ? "" : " · " + v.motif);
            row.addView(text(desc, 12, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(text((v.sens > 0 ? "+" : "−") + SmsParser.fmt(v.montant), 14, v.sens > 0 ? INK : GOOD, true));
            row.setOnLongClickListener(x -> { annulerVersement(v); return true; });
            l.addView(row, fill());
        }
        if (!miens.isEmpty()) l.addView(text("Appui long sur un mouvement pour le supprimer.", 11, MUTED, false));
        page.addView(l, fill());
    }

    // ----- Application Analyse (0.16) -----

    private Analyse.Rapport rapportAnalyse;
    private String cleAnalyse = "";

    /** Rapport d'analyse, recalculé seulement si les données ont changé (ou au plus toutes les minutes). */
    private Analyse.Rapport analyse() {
        long now = System.currentTimeMillis();
        Map<String, Long> budgets = Store.get(this).budgets();
        String cle = System.identityHashCode(result) + ":" + classements.size() + ":" + saisies.size() + ":" + jours.size() + ":"
                + budgets + ":" + regles.size() + ":" + (now / 60000);
        if (rapportAnalyse == null || !cle.equals(cleAnalyse)) {
            rapportAnalyse = Analyse.calculer(this::computeBilan, this::computeSoldes, budgets, now);
            cleAnalyse = cle;
        }
        return rapportAnalyse;
    }

    private View analyseCard() {
        if (acces(Modules.ANALYSE) == Modules.Acces.ABSENT)
            return promoModule(Modules.ANALYSE, "Évolution sur 12 mois, meilleurs jours et heures de vente, clients perdus, où part votre argent, "
                    + "prévision de fin de mois et budgets proposés d'après vos dépenses.");
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("📊 Analyse", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!actif(Modules.ANALYSE)) head.addView(chip("Lecture seule", WARN, WARN_BG));
        c.addView(head, fill());
        if (result != null && actif(Modules.ANALYSE)) {
            Analyse.Rapport r = analyse();
            if (!r.alertes.isEmpty()) c.addView(text("⚠  " + r.alertes.get(0) + (r.alertes.size() > 1 ? " (+" + (r.alertes.size() - 1) + ")" : ""), 13, WARN, true));
            else c.addView(text("Aucune alerte : tout est dans vos habitudes.", 13, GOOD, true));
        } else {
            c.addView(text("Tendances, ventes, dépenses, prévisions et budgets proposés.", 13, MUTED, false));
        }
        c.addView(button("Voir l'analyse  ›", true, v -> ouvrirAnalyse()), fill());
        return c;
    }

    private void ouvrirAnalyse() {
        fermerDetail();
        analyseOuvert = true;
        pageIdx = PAGE_FINANCES;
        render();
    }

    /** Barre horizontale : libellé, barre proportionnelle, valeur. */
    private View barre(String libelle, long valeur, long max, int couleur, String texte) {
        LinearLayout r = column(2);
        r.setPadding(0, dp(3), 0, dp(3));
        LinearLayout top = new LinearLayout(this);
        top.addView(text(libelle, 13, INK, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(text(texte, 13, INK, true));
        r.addView(top, fill());
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(box(BG, 0, 4));
        int plein = (int) Math.round(Math.min(1.0, Math.max(0, valeur) / (double) Math.max(1, max)) * 1000);
        View f = new View(this);
        f.setBackground(box(couleur, 0, 4));
        bar.addView(f, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, plein)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, 1000 - plein)));
        r.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)));
        return r;
    }

    private void analysePage() {
        page.addView(retour("Finances"));
        pageTitle("Analyse", "Calculée sur votre téléphone à partir de vos opérations classées");
        if (result == null) { page.addView(empty("Ouverture de vos données…")); return; }
        boolean lecture = !actif(Modules.ANALYSE);
        if (lecture) page.addView(bandeauLecture(Modules.ANALYSE), fill());
        Analyse.Rapport r = analyse();

        // Alertes.
        if (!lecture && !r.alertes.isEmpty()) {
            LinearLayout a = column(6);
            a.setBackground(box(WARN_BG, 0, 14));
            a.setPadding(dp(14), dp(12), dp(14), dp(12));
            a.addView(text("À surveiller", 15, WARN, true));
            for (String s : r.alertes) a.addView(text("•  " + s, 13, INK, false));
            page.addView(a, fill());
        }

        // Prévisions.
        if (!lecture) {
            LinearLayout p = card();
            p.addView(text("Ce mois, à ce rythme", 16, INK, true));
            p.addView(text("Jour " + r.joursEcoules + " sur " + r.joursMois + " : projection d'après ce qui est déjà fait ce mois-ci.", 12, MUTED, false));
            p.addView(kvSmall("Ventes prévues", SmsParser.fmt(r.ventesProjetees) + ecartTexte(r.ecartVentes)));
            p.addView(kvSmall("Dépenses prévues (frais compris)", SmsParser.fmt(r.depensesProjetees) + ecartTexte(r.ecartDepenses)));
            p.addView(kvSmall("Résultat prévu", SmsParser.fmt(r.ventesProjetees - r.depensesProjetees)));
            if (r.moyVentes > 0 || r.moyDepenses > 0)
                p.addView(text("Moyenne des 3 mois précédents : ventes " + SmsParser.fmt(r.moyVentes) + " · dépenses " + SmsParser.fmt(r.moyDepenses), 12, MUTED, false));
            p.addView(divider());
            if (r.tresorerie != null) p.addView(kvSmall("Trésorerie aujourd'hui", SmsParser.fmt(r.tresorerie)));
            if (r.tresoreriePrevue != null) {
                p.addView(kvSmall("Trésorerie prévue fin du mois", SmsParser.fmt(r.tresoreriePrevue)));
                p.addView(text("D'après l'évolution des 30 derniers jours (" + (r.fluxJour >= 0 ? "+" : "−") + SmsParser.fmt(Math.abs(r.fluxJour)) + " par jour).", 12, MUTED, false));
            }
            if (r.dateManque != null) p.addView(text("⚠  À ce rythme, plus d'argent sur vos comptes vers le " + Modules.date(r.dateManque) + ".", 13, BAD, true));
            page.addView(p, fill());
        }

        // Évolution sur 12 mois.
        LinearLayout ev = card();
        ev.addView(text("Évolution sur 12 mois", 16, INK, true));
        long max = 1;
        for (Analyse.Mois m : r.mois) if (!m.vide) max = Math.max(max, Math.max(m.chiffres.ventes, m.chiffres.depenses + m.chiffres.frais));
        int affiches = 0;
        for (Analyse.Mois m : r.mois) {
            if (m.vide) continue;
            affiches++;
            long dep = m.chiffres.depenses + m.chiffres.frais;
            LinearLayout bloc = column(1);
            bloc.setPadding(0, dp(4), 0, dp(4));
            LinearLayout h = new LinearLayout(this);
            h.addView(text(m.nom + (m.enCours ? " (en cours)" : ""), 13, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            h.addView(text("résultat " + (m.chiffres.resultat < 0 ? "−" : "") + SmsParser.fmt(Math.abs(m.chiffres.resultat)), 12, m.chiffres.resultat < 0 ? BAD : GOOD, true));
            bloc.addView(h, fill());
            bloc.addView(barre("Ventes", m.chiffres.ventes, max, GOOD, SmsParser.fmt(m.chiffres.ventes)), fill());
            bloc.addView(barre("Dépenses", dep, max, BAD, SmsParser.fmt(dep)), fill());
            if (m.tresorerie != null) bloc.addView(text("Trésorerie " + (m.enCours ? "aujourd'hui" : "fin du mois") + " : " + SmsParser.fmt(m.tresorerie), 11, MUTED, false));
            ev.addView(bloc, fill());
        }
        if (affiches == 0) ev.addView(text("Pas encore assez d'opérations classées.", 13, MUTED, false));
        page.addView(ev, fill());
        if (lecture) return;

        // Ventes.
        LinearLayout ve = card();
        ve.addView(text("Vos ventes · 90 derniers jours", 16, INK, true));
        if (r.nbVentes == 0) {
            ve.addView(text("Aucune vente classée sur 90 jours. Classez vos entrées en « Vente » pour voir cette analyse.", 13, MUTED, false));
        } else {
            ve.addView(kvSmall("Ventes", r.nbVentes + " · " + SmsParser.fmt(r.totalVentes) + " FCFA"));
            ve.addView(kvSmall("Panier moyen", SmsParser.fmt(r.panierMoyen) + " FCFA"));
            ve.addView(text("Meilleur jour : " + r.meilleurJour() + " · meilleures heures : " + r.meilleureTranche(), 13, ACCENT, true));
            ve.addView(label("Par jour de la semaine"));
            long mj = 1;
            for (long v : r.ventesJour) mj = Math.max(mj, v);
            for (int i = 0; i < 7; i++) ve.addView(barre(Analyse.JOURS[i] + " · " + r.nbJour[i], r.ventesJour[i], mj, ACCENT, SmsParser.fmt(r.ventesJour[i])), fill());
            ve.addView(label("Par heure"));
            long mt = 1;
            for (long v : r.ventesTranche) mt = Math.max(mt, v);
            for (int i = 0; i < 6; i++) ve.addView(barre(Analyse.TRANCHES[i] + " · " + r.nbTranche[i], r.ventesTranche[i], mt, ACCENT, SmsParser.fmt(r.ventesTranche[i])), fill());
            ve.addView(divider());
            ve.addView(label("Clients (paiements reçus par OM, MoMo ou banque)"));
            ve.addView(kvSmall("Clients sur 90 jours", String.valueOf(r.clients)));
            ve.addView(kvSmall("Fidèles (2 des 3 derniers mois)", String.valueOf(r.fideles)));
            ve.addView(kvSmall("Nouveaux (30 derniers jours)", String.valueOf(r.nouveaux)));
            ve.addView(kvSmall("Perdus (rien depuis 45 jours)", String.valueOf(r.perdus)));
            for (Analyse.Item it : r.clientsPerdus)
                ve.addView(text("   " + nomItem(it) + " · " + it.nombre + " achats, " + SmsParser.fmt(it.montant) + " · dernier le " + Modules.date(it.derniere), 12, MUTED, false));
        }
        page.addView(ve, fill());

        // Dépenses.
        LinearLayout de = card();
        de.addView(text("Où part l'argent · 90 derniers jours", 16, INK, true));
        if (r.postes.isEmpty()) {
            de.addView(text("Aucune dépense classée sur 90 jours.", 13, MUTED, false));
        } else {
            long mp = 1;
            for (Analyse.Item it : r.postes) mp = Math.max(mp, it.montant);
            for (Analyse.Item it : r.postes)
                de.addView(barre(it.nom + " · " + it.nombre, it.montant, mp, BAD, SmsParser.fmt(it.montant) + " (≈ " + SmsParser.fmt(it.montant / 3) + "/mois)"), fill());
            if (!r.beneficiaires.isEmpty()) {
                de.addView(label("À qui vous payez le plus"));
                for (Analyse.Item it : r.beneficiaires) de.addView(kvSmall(nomItem(it) + " · " + it.nombre + " fois", SmsParser.fmt(it.montant)));
            }
        }
        de.addView(kvSmall("Frais opérateur", SmsParser.fmt(r.frais) + " (" + r.fraisPourcent() + " % des sorties)"));
        if (!r.inhabituelles.isEmpty()) {
            de.addView(label("Dépenses inhabituelles · 30 derniers jours"));
            for (Analyse.Inhabituelle x : r.inhabituelles)
                de.addView(text("⚠  " + SmsParser.fmt(x.montant) + " · " + Caisse.categorieNom(x.poste) + " · " + Modules.date(x.sms.date)
                        + (x.sms.nom != null ? " · " + x.sms.nom : "") + " (d'habitude " + SmsParser.fmt(x.habituel) + ")", 13, WARN, true));
        }
        page.addView(de, fill());

        // Budgets proposés et suivi.
        LinearLayout bu = card();
        bu.addView(text("Budgets", 16, INK, true));
        bu.addView(text("Proposition = moyenne de vos dépenses des 3 derniers mois, arrondie aux 500 FCFA au-dessus.", 12, MUTED, false));
        if (r.budgets.isEmpty()) bu.addView(text("Pas encore de dépenses classées pour proposer des budgets.", 13, MUTED, false));
        for (Analyse.BudgetSuivi b : r.budgets) {
            LinearLayout k = column(3);
            k.setPadding(0, dp(6), 0, dp(6));
            LinearLayout h = new LinearLayout(this);
            h.setGravity(Gravity.CENTER_VERTICAL);
            h.addView(text(Caisse.categorieNom(b.poste), 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            h.addView(text(b.budget > 0 ? "budget " + SmsParser.fmt(b.budget) : "pas de budget", 12, MUTED, false));
            k.addView(h, fill());
            if (b.budget > 0) {
                int coul = b.pourcent >= 100 ? BAD : b.pourcentProjete >= 100 ? WARN : GOOD;
                k.addView(barre("Ce mois : " + SmsParser.fmt(b.depense), b.depense, b.budget, coul, b.pourcent + " %"), fill());
                k.addView(text("À ce rythme : " + SmsParser.fmt(b.projection) + " à la fin du mois (" + b.pourcentProjete + " %)", 12, coul == GOOD ? MUTED : coul, coul != GOOD));
                StringBuilder hs = new StringBuilder();
                for (int i = 0; i < b.historique.size(); i++) {
                    if (b.historique.get(i) == null) continue;
                    hs.append(hs.length() > 0 ? " · " : "").append(b.moisHistorique.get(i)).append(" ").append(b.historique.get(i)).append(" %");
                }
                if (hs.length() > 0) k.addView(text("Mois passés : " + hs, 11, MUTED, false));
            } else {
                k.addView(text("Dépensé ce mois : " + SmsParser.fmt(b.depense), 12, MUTED, false));
            }
            if (b.propose > 0 && b.propose != b.budget) {
                final Analyse.BudgetSuivi fb = b;
                k.addView(small("Appliquer " + SmsParser.fmt(b.propose) + " FCFA / mois (moyenne " + SmsParser.fmt(b.moyenne) + ")", ACCENT,
                        v -> appliquerBudgets(java.util.Collections.singletonList(fb))));
            }
            k.addView(divider());
            bu.addView(k, fill());
        }
        List<Analyse.BudgetSuivi> aa = r.aAppliquer();
        if (aa.size() > 1) bu.addView(button("Appliquer les " + aa.size() + " budgets proposés", true, v -> appliquerBudgets(aa)), fill());
        page.addView(bu, fill());
        page.addView(text("Les chiffres viennent des opérations classées : classez les opérations « À classer » pour une analyse complète. "
                + "Elle est aussi dans l'export Excel (feuille « Analyse »).", 12, MUTED, false));
    }

    private static String ecartTexte(Integer e) {
        return e == null ? "" : " (" + (e >= 0 ? "+" : "") + e + " % / moyenne)";
    }

    private String nomItem(Analyse.Item it) {
        if (it.nom != null && !it.nom.trim().isEmpty()) return it.nom.trim();
        return it.numero != null ? formatNumero(it.numero) : "—";
    }

    private void appliquerBudgets(List<Analyse.BudgetSuivi> liste) {
        if (!exiger(Modules.ANALYSE)) return;
        final List<Analyse.BudgetSuivi> l = new ArrayList<>(liste);
        io.execute(() -> {
            Store st = Store.get(this);
            for (Analyse.BudgetSuivi b : l) st.setBudget(b.poste, b.propose);
            SyncJob.soon(this);
            runOnUiThread(() -> {
                rapportAnalyse = null;
                notice(l.size() == 1 ? "Budget « " + Caisse.categorieNom(l.get(0).poste) + " » : " + SmsParser.fmt(l.get(0).propose) + " FCFA par mois."
                        : l.size() + " budgets enregistrés.", 1);
                render();
            });
        });
    }

    private View assistantCard() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("✨ Assistant IA", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!actif(Modules.ASSISTANT)) head.addView(chip("Lecture seule", WARN, WARN_BG));
        c.addView(head, fill());
        c.addView(text("Posez une question sur vos chiffres : ventes de la semaine, plus grosses dépenses, qui vous doit…", 13, MUTED, false));
        c.addView(button("Poser une question  ›", true, v -> { if (exiger(Modules.ASSISTANT)) ouvrirAssistant(); }), fill());
        return c;
    }

    private void ouvrirAssistant() {
        fermerDetail();
        assistantOuvert = true;
        pageIdx = PAGE_FINANCES;
        render();
    }

    private static final String[] QUESTIONS_TYPES = {
            "Combien j'ai vendu cette semaine ?",
            "Quelles sont mes plus grosses dépenses du mois ?",
            "Mon résultat est-il meilleur que le mois dernier ?",
            "Qui me doit de l'argent ?",
            "Comment améliorer mon résultat ?",
    };

    private void assistantPage() {
        page.addView(retour("Finances"));
        pageTitle("Assistant IA", "Vos chiffres restent masqués : l'IA ne voit ni SMS, ni noms, ni numéros");
        LinearLayout fil = column(8);
        if (conversation.isEmpty()) {
            fil.addView(text("Bonjour ! Je réponds à partir de vos totaux (jour, semaine, 3 derniers mois, trésorerie, budgets, dettes). Exemples :", 14, INK, false));
            for (String q : QUESTIONS_TYPES) {
                TextView t = text("›  " + q, 14, ACCENT, true);
                t.setPadding(0, dp(4), 0, dp(4));
                t.setOnClickListener(v -> poserQuestion(q));
                fil.addView(t, fill());
            }
        }
        for (String[] m : conversation) {
            boolean moi = "user".equals(m[0]);
            TextView t = text(m[1], 14, moi ? Color.WHITE : INK, false);
            t.setTextIsSelectable(!moi);
            t.setBackground(box(moi ? ACCENT : SURFACE, moi ? 0 : LINE, 14));
            t.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = moi ? Gravity.END : Gravity.START;
            if (moi) lp.setMarginStart(dp(40)); else lp.setMarginEnd(dp(24));
            fil.addView(t, lp);
        }
        if (assistantAttente) fil.addView(text("L'assistant réfléchit…", 13, MUTED, false));
        page.addView(fil, fill());
        if (questionChamp == null) {
            questionChamp = new EditText(this);
            questionChamp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            questionChamp.setHint("Votre question…");
        }
        page.addView(detach(questionChamp), fill());
        page.addView(button("Envoyer", true, v -> {
            String q = questionChamp.getText().toString().trim();
            if (!q.isEmpty()) poserQuestion(q);
        }), fill());
        if (iaCompteur != null) page.addView(text(iaCompteur, 12, MUTED, false));
        if (!conversation.isEmpty()) page.addView(small("Nouvelle conversation", MUTED, v -> { conversation.clear(); historiqueIa = new org.json.JSONArray(); render(); }), fill());
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Contexte masqué envoyé à l'IA (totaux seulement) et correspondance des noms gardée sur le téléphone. */
    private Contexte.Resultat contexteIa() {
        long now = System.currentTimeMillis();
        List<String> libelles = new ArrayList<>();
        List<Caisse.Bilan> mois = new ArrayList<>();
        SimpleDateFormat mf = new SimpleDateFormat("MMMM yyyy", Locale.FRANCE);
        for (int k = 0; k > -3; k--) {
            long d = Finances.debutMois(now, k);
            long f = k == 0 ? now : Finances.debutMois(now, k + 1) - 1;
            libelles.add((k == 0 ? "Mois en cours (" : "Mois de ") + mf.format(new Date(d)) + (k == 0 ? ")" : ""));
            mois.add(computeBilan(d, f));
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long debutJour = c.getTimeInMillis();
        Caisse.Bilan jour = computeBilan(debutJour, now);
        Caisse.Bilan semaine = computeBilan(debutJour - 6L * 86400000, now);
        Finances.Tresorerie tr = Finances.tresorerie(result, jour.especes, Store.get(this).avoirs());
        Dettes.Bilan d = visible(Modules.DETTES) ? Dettes.calculer(engagements, now) : null;
        List<Suivi.Source> s = visible(Modules.SUIVI) ? sourcesSuivies() : null;
        String nom = Cloud.caisseNom(this).isEmpty() ? "Ma caisse" : Cloud.caisseNom(this);
        return Contexte.construire(nom, now, libelles, mois, semaine, jour, tr, Store.get(this).budgets(), d, s);
    }

    private void poserQuestion(String q) {
        if (assistantAttente || result == null) return;
        if (!exiger(Modules.ASSISTANT)) return;
        if (questionChamp != null) questionChamp.setText("");
        conversation.add(new String[] {"user", q});
        assistantAttente = true;
        render();
        Contexte.Resultat ctx = contexteIa();
        final org.json.JSONArray histo = historiqueIa;
        io.execute(() -> {
            String rep;
            String compteur = null;
            boolean ok;
            try {
                String[] r = Cloud.questionIa(this, q, ctx.texte.toString(), histo);
                try {
                    org.json.JSONObject u = new org.json.JSONObject();
                    u.put("role", "user");
                    u.put("texte", q);
                    org.json.JSONObject a = new org.json.JSONObject();
                    a.put("role", "assistant");
                    a.put("texte", r[0]);
                    histo.put(u);
                    histo.put(a);
                } catch (Exception ignored) {}
                rep = Contexte.demasquer(r[0], ctx.noms);
                compteur = r[1] + " question(s) sur " + r[2] + " ce mois-ci.";
                ok = true;
            } catch (Cloud.CloudException e) {
                rep = e.getMessage();
                ok = false;
            }
            final String fr = rep, fc = compteur;
            final boolean fok = ok;
            runOnUiThread(() -> {
                assistantAttente = false;
                if (fok) { conversation.add(new String[] {"assistant", fr}); if (fc != null) iaCompteur = fc; }
                else { conversation.remove(conversation.size() - 1); notice(fr, 2); }
                render();
            });
        });
    }

    // ---------- Alertes WhatsApp (0.12) ----------

    /** Carte de l'onglet Compte : destinataires, choix des alertes, envoi d'essai. */
    private View alertesCard() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Alertes WhatsApp", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!actif(Modules.ALERTES)) head.addView(chip("Lecture seule", WARN, WARN_BG));
        c.addView(head, fill());
        org.json.JSONObject etat = Cloud.alertesEtat(this);
        if (etat == null) {
            c.addView(text("Chargement des réglages… (il faut du réseau)", 13, MUTED, false));
            if (!alertesChargees) {
                alertesChargees = true;
                io.execute(() -> {
                    try { Cloud.rafraichirAlertes(this); } catch (Exception ignored) {}
                    runOnUiThread(this::render);
                });
            }
            return c;
        }
        if (!Cloud.whatsappConfigure(this)) {
            TextView w = text("L'envoi WhatsApp est en cours d'activation par le service client : vos alertes attendent jusqu'à 24 heures puis expirent.", 12, WARN, false);
            w.setBackground(box(WARN_BG, 0, 10));
            w.setPadding(dp(10), dp(8), dp(10), dp(8));
            c.addView(w, fill());
        }

        // Destinataires.
        c.addView(label("Destinataires (3 au plus)"));
        org.json.JSONArray dest = etat.optJSONArray("destinataires");
        int nb = dest == null ? 0 : dest.length();
        for (int i = 0; i < nb; i++) {
            org.json.JSONObject d = dest.optJSONObject(i);
            if (d == null) continue;
            String num = d.optString("numero", ""), nom = d.optString("nom", "");
            boolean ok = d.optBoolean("confirme", false);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(4), 0, dp(4));
            LinearLayout t = column(0);
            t.addView(text((nom.isEmpty() ? "" : nom + " · ") + formatNumero(num), 14, INK, true));
            t.addView(text(ok ? "✓ Confirmé" : "En attente du code", 12, ok ? GOOD : WARN, false));
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (!ok) r.addView(small("Saisir le code", ACCENT, v -> saisirCode(num)));
            r.addView(small("Retirer", BAD, v -> appelAlerte(() -> Cloud.retirerDestinataire(this, num), "Numéro retiré.")));
            c.addView(r, fill());
        }
        if (nb == 0) c.addView(text("Ajoutez votre numéro WhatsApp (et jusqu'à 2 autres : associé, comptable). Chacun reçoit un code à confirmer.", 13, MUTED, false));
        if (nb < 3) c.addView(button("+ Ajouter un numéro", false, v -> ajouterDestinataire(nb == 0)), fill());

        // Choix des alertes.
        Alertes.Reglages g = Cloud.reglagesAlertes(this);
        c.addView(label("Messages envoyés"));
        CheckBox bilan = new CheckBox(this);
        bilan.setText("Bilan du soir (ventes, dépenses, résultat, soldes)");
        bilan.setChecked(g.bilan);
        c.addView(bilan, fill());
        LinearLayout hr = new LinearLayout(this);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        hr.addView(text("    à partir de", 13, MUTED, false));
        EditText heure = amountField("20");
        heure.setText(String.valueOf(g.bilanHeure));
        hr.addView(heure, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT));
        hr.addView(text("heures", 13, MUTED, false));
        c.addView(hr, fill());
        CheckBox ecart = new CheckBox(this);
        ecart.setText("Écart de caisse à la clôture");
        ecart.setChecked(g.ecart);
        c.addView(ecart, fill());
        CheckBox chaine = new CheckBox(this);
        chaine.setText("SMS manquant ou faux (chaîne des soldes rompue)");
        chaine.setChecked(g.chaine);
        c.addView(chaine, fill());
        CheckBox solde = new CheckBox(this);
        solde.setText("Solde bas");
        solde.setChecked(g.solde);
        c.addView(solde, fill());
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        EditText sOm = amountField("OM");
        sOm.setText(String.valueOf(g.seuilOm));
        EditText sMomo = amountField("MoMo");
        sMomo.setText(String.valueOf(g.seuilMomo));
        sr.addView(text("    OM sous", 13, MUTED, false));
        sr.addView(sOm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sr.addView(text(" MoMo sous", 13, MUTED, false));
        sr.addView(sMomo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(sr, fill());
        CheckBox dettes = new CheckBox(this);
        dettes.setText("Échéances de dettes (aujourd'hui et en retard)");
        dettes.setChecked(g.dettes);
        c.addView(dettes, fill());
        if (!visible(Modules.DETTES)) c.addView(text("    Demande l'application Dettes & créances.", 12, MUTED, false));

        LinearLayout act = new LinearLayout(this);
        act.addView(button("Enregistrer", true, v -> {
            try {
                org.json.JSONObject p = new org.json.JSONObject();
                p.put("bilan", bilan.isChecked());
                p.put("bilan_heure", entier(heure, 20));
                p.put("ecart", ecart.isChecked());
                p.put("chaine", chaine.isChecked());
                p.put("solde", solde.isChecked());
                p.put("seuil_om", entier(sOm, 5000));
                p.put("seuil_momo", entier(sMomo, 5000));
                p.put("dettes", dettes.isChecked());
                if (!exiger(Modules.ALERTES)) return;
                appelAlerte(() -> Cloud.reglerAlertes(this, p), "Réglages des alertes enregistrés.");
            } catch (Exception e) {
                notice("Réglages invalides.", 2);
            }
        }), weight(false));
        act.addView(button("Envoyer le bilan maintenant", false, v -> {
            if (!exiger(Modules.ALERTES)) return;
            notice("Envoi du bilan du jour…", 0);
            io.execute(() -> {
                String m;
                int n;
                try { m = Cloud.envoyerBilanMaintenant(this); n = 1; } catch (Cloud.CloudException e) { m = e.getMessage(); n = 2; }
                final String fm = m;
                final int fn = n;
                runOnUiThread(() -> { notice(fm, fn); render(); });
            });
        }), weight(true));
        c.addView(act, fill());

        // Derniers envois.
        org.json.JSONArray rec = etat.optJSONArray("recents");
        if (rec != null && rec.length() > 0) {
            c.addView(label("Derniers messages · " + etat.optInt("envois_jour", 0) + " sur 30 aujourd'hui"));
            for (int i = 0; i < Math.min(5, rec.length()); i++) {
                org.json.JSONObject x = rec.optJSONObject(i);
                if (x == null) continue;
                String st = x.optString("statut", "");
                String lib = "envoye".equals(st) ? "✓ envoyé" : "en_attente".equals(st) ? "en attente" : "expire".equals(st) ? "expiré" : "échec";
                c.addView(text(when(x.optLong("quand", 0)) + " · " + typeAlerte(x.optString("type", "")) + " → " + formatNumero(x.optString("numero", ""))
                        + " · " + lib, 12, "envoye".equals(st) ? MUTED : WARN, false));
            }
        }
        return c;
    }

    private static String typeAlerte(String t) {
        switch (t) {
            case "bilan": return "Bilan";
            case "ecart": return "Écart de caisse";
            case "chaine": return "SMS manquant";
            case "solde": return "Solde bas";
            case "dettes": return "Échéances";
            default: return t;
        }
    }

    private static int entier(EditText e, int defaut) {
        String d = e.getText().toString().replaceAll("\\D", "");
        if (d.isEmpty() || d.length() > 8) return defaut;
        return Integer.parseInt(d);
    }

    private interface AppelCloud { void run() throws Cloud.CloudException; }

    private void appelAlerte(AppelCloud a, String ok) {
        io.execute(() -> {
            String m;
            int n;
            try { a.run(); m = ok; n = 1; } catch (Cloud.CloudException e) { m = e.getMessage(); n = 2; }
            final String fm = m;
            final int fn = n;
            runOnUiThread(() -> { notice(fm, fn); render(); });
        });
    }

    private void ajouterDestinataire(boolean premier) {
        if (!exiger(Modules.ALERTES)) return;
        LinearLayout f = column(8);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        EditText num = new EditText(this);
        num.setInputType(InputType.TYPE_CLASS_PHONE);
        num.setHint("Numéro WhatsApp (6XXXXXXXX)");
        if (premier) num.setText(Cloud.phone(this));
        f.addView(num, fill());
        EditText nom = new EditText(this);
        nom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nom.setHint("Nom (ex. Patron, Comptable)");
        f.addView(nom, fill());
        f.addView(text("Un code à 6 chiffres sera envoyé sur WhatsApp à ce numéro.", 12, MUTED, false));
        new AlertDialog.Builder(this)
                .setTitle("Ajouter un numéro")
                .setView(f)
                .setPositiveButton("Envoyer le code", (d, w) -> {
                    String p9 = Cloud.phone9(num.getText().toString());
                    if (p9 == null) { notice("Numéro invalide : 9 chiffres, 6XXXXXXXX.", 2); return; }
                    notice("Envoi du code sur WhatsApp…", 0);
                    io.execute(() -> {
                        String m = null;
                        try { Cloud.demanderCode(this, p9, nom.getText().toString().trim()); } catch (Cloud.CloudException e) { m = e.getMessage(); }
                        final String fm = m;
                        runOnUiThread(() -> {
                            render();
                            if (fm != null) notice(fm, 2); else saisirCode(p9);
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void saisirCode(String numero) {
        EditText code = amountField("Code à 6 chiffres");
        LinearLayout f = column(8);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(text("Code reçu sur WhatsApp au " + formatNumero(numero) + " (valable 10 minutes).", 13, MUTED, false));
        f.addView(code, fill());
        new AlertDialog.Builder(this)
                .setTitle("Confirmer le numéro")
                .setView(f)
                .setPositiveButton("Confirmer", (d, w) -> appelAlerte(() -> Cloud.confirmerDestinataire(this, numero, code.getText().toString().trim()),
                        "Numéro confirmé : il recevra vos alertes."))
                .setNeutralButton("Renvoyer un code", (d, w) -> io.execute(() -> {
                    String m;
                    int n;
                    try { Cloud.demanderCode(this, numero, ""); m = "Nouveau code envoyé."; n = 1; } catch (Cloud.CloudException e) { m = e.getMessage(); n = 2; }
                    final String fm = m;
                    final int fn = n;
                    runOnUiThread(() -> { notice(fm, fn); if (fn == 1) saisirCode(numero); });
                }))
                .setNegativeButton("Plus tard", null)
                .show();
    }

    private void ouvrirApplications() {
        fermerDetail();
        applicationsOuvert = true;
        pageIdx = PAGE_COMPTE;
        render();
        scroll.scrollTo(0, 0);
        if (Cloud.loggedIn(this)) io.execute(() -> {
            try { Cloud.rafraichirModules(this); } catch (Exception ignored) {}
            runOnUiThread(() -> { etatModules = Cloud.modules(this); if (applicationsOuvert) render(); });
        });
    }

    /** Carte de l'onglet Compte : applications actives et accès au catalogue. */
    private View applicationsCard() {
        LinearLayout c = card();
        c.addView(text("Mes applications", 16, INK, true));
        long now = System.currentTimeMillis();
        List<String> noms = new ArrayList<>();
        for (String code : mods().actifs(now)) noms.add(nomModule(code));
        c.addView(text("Base gratuite (lecture des SMS, journal, entrées et dépenses)" + (noms.isEmpty() ? "" : " + " + String.join(", ", noms)) + ".", 13, MUTED, false));
        c.addView(button("Gérer mes applications  ›", false, v -> ouvrirApplications()), fill());
        return c;
    }

    private void applicationsPage() {
        page.addView(retour("Compte"));
        pageTitle("Applications", "Installez seulement ce qui vous sert");
        Modules.Etat e = mods();
        long now = System.currentTimeMillis();
        if (!Cloud.loggedIn(this)) {
            page.addView(empty("Créez un compte (gratuit) pour installer des applications."));
            return;
        }
        if (!e.connu) {
            page.addView(empty("Chargement des applications… (il faut du réseau la première fois)"));
            return;
        }
        Cloud.Portefeuille w = Cloud.portefeuille(this);
        page.addView(kvSmall("Porte-monnaie", Tarif.fcfa(Math.max(0, w.solde))));
        String rap = e.rappel(now);
        if (rap != null) {
            TextView t = text(rap, 13, WARN, true);
            t.setBackground(box(WARN_BG, 0, 10));
            t.setPadding(dp(12), dp(9), dp(12), dp(9));
            page.addView(t, fill());
        }

        if (!e.promos.isEmpty()) {
            LinearLayout pc = card();
            pc.setBackground(box(GOOD_BG, 0, 14));
            pc.addView(text("Vos promotions", 15, GOOD, true));
            for (Modules.Promo p : e.promos) {
                pc.addView(text("• " + p.nom + " : " + Modules.promoTexte(e, p), 13, INK, true));
                if (!p.description.isEmpty()) pc.addView(text(p.description, 12, MUTED, false));
            }
            pc.addView(text("La réduction est appliquée toute seule au paiement ; les prix ci-dessous en tiennent compte.", 12, MUTED, false));
            page.addView(pc, fill());
        }
        LinearLayout cp = card();
        cp.addView(text("J'ai un code promo", 14, INK, true));
        if (codePromoField == null) {
            codePromoField = new EditText(this);
            codePromoField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            codePromoField.setSingleLine(true);
            codePromoField.setHint("Ex. RENTREE30");
        }
        cp.addView(detach(codePromoField), fill());
        cp.addView(button("Utiliser le code", false, v -> utiliserCode()), fill());
        page.addView(cp, fill());

        LinearLayout base = card();
        LinearLayout bh = new LinearLayout(this);
        bh.setGravity(Gravity.CENTER_VERTICAL);
        bh.addView(text("Base", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bh.addView(chip("Gratuite · incluse", GOOD, GOOD_BG));
        base.addView(bh, fill());
        base.addView(text("Lecture des SMS Orange Money, MTN MoMo et banques, journal et classement des opérations, entrées et dépenses par catégorie, "
                + "soldes de chaque compte, catégories personnelles, sauvegarde.", 13, MUTED, false));
        page.addView(base, fill());

        page.addView(label("Packs : plusieurs applications moins cher"));
        for (Modules.Module m : e.catalogue) if (m.estPack) page.addView(moduleTuile(m, now), fill());
        page.addView(label("Applications"));
        for (Modules.Module m : e.catalogue) if (!m.estPack) page.addView(moduleTuile(m, now), fill());
        page.addView(text("Chaque application démarre par " + e.essaiJours + " jours d'essai gratuit, puis se paie au mois depuis votre porte-monnaie. "
                + "La formule annuelle se paie en une fois, sans essai. "
                + "Non renouvelée, elle passe en lecture seule : vous gardez toutes vos données. L'export Excel est inclus avec Finances & budgets.", 12, MUTED, false));
    }

    private void utiliserCode() {
        String code = codePromoField.getText().toString().trim();
        if (code.length() < 3) { notice("Saisissez le code promo reçu.", 2); return; }
        notice("Vérification du code…", 0);
        io.execute(() -> {
            String msg;
            int n;
            try {
                msg = Cloud.utiliserCode(this, code);
                n = 1;
            } catch (Cloud.CloudException ex) {
                msg = ex.getMessage();
                n = 2;
            }
            final String fm = msg;
            final int fn = n;
            runOnUiThread(() -> {
                if (fn == 1) codePromoField.setText("");
                etatModules = Cloud.modules(this);
                notice(fm, fn);
                render();
            });
        });
    }

    private View moduleTuile(Modules.Module m, long now) {
        Modules.Etat e = mods();
        LinearLayout c = card();
        Modules.Acces ac = m.estPack ? (e.abonnement(m.code) == null ? Modules.Acces.ABSENT
                : e.abonnement(m.code).fin > now + e.decalage ? Modules.Acces.ACTIF : Modules.Acces.LECTURE) : e.acces(m.code, now);
        Modules.Module pack = m.estPack ? null : e.packActif(m.code, now);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = text(m.nom.substring(0, 1), 18, Color.WHITE, true);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(box(ac == Modules.Acces.ACTIF ? ACCENT : ac == Modules.Acces.LECTURE ? WARN : MUTED, 0, 12));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(40), dp(40));
        il.setMarginEnd(dp(12));
        head.addView(ic, il);
        LinearLayout tx = column(1);
        tx.addView(text(m.nom, 15, INK, true));
        String prix = Modules.prixTexte(m) + (m.parTelephone ? " (" + Tarif.fcfa(m.prix) + " par téléphone en plus)" : "");
        if (m.estPack && !m.enPromo()) {
            long separes = 0;
            for (String x : m.contenu) { Modules.Module y = e.module(x); if (y != null) separes += y.prixCompte; }
            if (separes > m.prixCompte) prix += " au lieu de " + Tarif.fcfa(separes);
        }
        tx.addView(text(prix, 13, ACCENT, true));
        if (m.enPromo()) tx.addView(text("Promotion : " + m.promo, 12, GOOD, true));
        if (m.annuel()) tx.addView(text("ou " + Tarif.fcfa(m.prixAnnuelCompte) + " par an"
                + (m.economieAnnuelle() > 0 ? " (" + Tarif.fcfa(m.economieAnnuelle()) + " d'économie)" : ""), 12, MUTED, false));
        head.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(head, fill());
        c.addView(text(m.description, 13, MUTED, false));
        c.addView(text(Modules.etatTexte(e, m.code, now), 12, ac == Modules.Acces.ACTIF ? GOOD : ac == Modules.Acces.LECTURE ? WARN : MUTED, true));
        if (pack != null) return c;
        Modules.Abonnement a = e.abonnement(m.code);
        LinearLayout act = new LinearLayout(this);
        if (ac == Modules.Acces.ACTIF && a != null && a.renouveler) {
            act.addView(button("Arrêter le renouvellement", false, v -> arreter(m.code)), weight(true));
        } else {
            act.addView(button(boutonModule(m.code), true, v -> activer(m.code)), weight(true));
        }
        c.addView(act, fill());
        if (m.annuel() && !(a != null && "an".equals(a.periode) && a.fin > now + e.decalage)) {
            c.addView(button("Passer à l'année · " + Tarif.fcfa(m.prixAnnuelCompte), false, v -> activer(m.code, "an")), fill());
        }
        return c;
    }

    // ----- Choix des applications à l'inscription -----

    private void choixModulesPage() {
        Modules.Etat e = mods();
        pageTitle("Bienvenue !", "Choisissez les applications qui vous servent");
        page.addView(text("La base est gratuite : lecture de vos SMS Orange Money, MoMo et banques, journal, entrées et dépenses par catégorie. "
                + "Ajoutez seulement ce dont vous avez besoin : chaque application est gratuite " + e.essaiJours + " jours, sans engagement.", 13, MUTED, false));
        page.addView(label("Vous êtes…"));
        for (int i = 0; i < Modules.PROFILS.length; i++) {
            final int k = i;
            String[] p = Modules.PROFILS[i];
            boolean on = profilChoisi == i;
            LinearLayout r = column(2);
            r.setPadding(dp(14), dp(12), dp(14), dp(12));
            r.setBackground(box(on ? GOOD_BG : SURFACE, on ? ACCENT : LINE, 14));
            Modules.Module pk = p[2].isEmpty() ? null : e.module(p[2]);
            r.addView(text((on ? "●  " : "○  ") + p[0] + (pk != null ? " · " + Tarif.fcfa(pk.prixCompte) + "/mois après l'essai" : ""), 15, on ? ACCENT : INK, true));
            r.addView(text(p[1] + (pk != null ? " — " + pk.description : ""), 12, MUTED, false));
            r.setOnClickListener(v -> {
                profilChoisi = k;
                cochees.clear();
                if (pk != null) cochees.addAll(pk.contenu);
                render();
            });
            page.addView(r, fill());
        }
        if (profilChoisi == Modules.PROFILS.length - 1) {
            LinearLayout l = card();
            for (String code : Modules.TOUTES) {
                Modules.Module m = e.module(code);
                if (m == null) continue;
                CheckBox cb = new CheckBox(this);
                cb.setText(m.nom + " · " + Modules.prixTexte(m));
                cb.setChecked(cochees.contains(code));
                cb.setOnClickListener(v -> { if (cb.isChecked()) cochees.add(code); else cochees.remove(code); render(); });
                l.addView(cb, fill());
                l.addView(text(m.description, 12, MUTED, false));
            }
            String pc = Modules.packConseille(e, cochees);
            if (pc != null && !cochees.equals(new java.util.HashSet<>(e.module(pc).contenu)))
                l.addView(text("Astuce : le " + e.module(pc).nom + " contient tout cela pour " + Tarif.fcfa(e.module(pc).prixCompte) + "/mois.", 12, ACCENT, true));
            page.addView(l, fill());
        }
        List<String> choix = choixActuel();
        String bouton = choix.isEmpty() ? "Continuer avec la base gratuite"
                : "Commencer · " + e.essaiJours + " jours gratuits, puis " + Tarif.fcfa(Modules.prixMensuel(e, choix)) + "/mois";
        Button go = button(bouton, true, v -> installerChoix(choix));
        go.setMinHeight(dp(52));
        page.addView(go, fill());
        page.addView(small("Plus tard", MUTED, v -> {
            Store.savePref(this, "choix_modules_fait", "1");
            choixModules = false;
            render();
        }), fill());
        page.addView(text("Vous pourrez ajouter ou retirer des applications à tout moment dans Compte → Applications.", 12, MUTED, false));
    }

    /** Sélection à installer : le pack du profil, ou (sur mesure) le pack conseillé ou les applications cochées. */
    private List<String> choixActuel() {
        List<String> out = new ArrayList<>();
        if (profilChoisi < 0) return out;
        String pk = Modules.PROFILS[profilChoisi][2];
        if (!pk.isEmpty()) { out.add(pk); return out; }
        String pc = Modules.packConseille(mods(), cochees);
        if (pc != null && new java.util.HashSet<>(mods().module(pc).contenu).equals(cochees)) { out.add(pc); return out; }
        for (String c : Modules.TOUTES) if (cochees.contains(c)) out.add(c);
        return out;
    }

    private void installerChoix(List<String> choix) {
        Store.savePref(this, "choix_modules_fait", "1");
        if (choix.isEmpty()) { choixModules = false; render(); return; }
        notice("Installation de vos applications…", 0);
        io.execute(() -> {
            List<String> ok = new ArrayList<>();
            String err = null;
            for (String c : choix) {
                try { Cloud.activerModule(this, c); ok.add(nomModule(c)); } catch (Cloud.CloudException ex) { err = ex.getMessage(); }
            }
            final String fe = err;
            runOnUiThread(() -> {
                etatModules = Cloud.modules(this);
                choixModules = false;
                if (fe != null) notice(fe, 2);
                else notice(String.join(", ", ok) + " : essai gratuit de " + mods().essaiJours + " jours. Bonne gestion !", 1);
                render();
            });
        });
    }

    // ---------- Recharge en un geste (0.10) ----------

    /** Ouvre le menu de l'opérateur (code USSD) : le client paie avec son code secret, que l'application ne voit jamais. */
    private void payerRecharge(Cloud.Portefeuille w) {
        String digits = rechargeMontant == null ? "" : rechargeMontant.getText().toString().replaceAll("\\D", "");
        long montant = digits.isEmpty() || digits.length() > 7 ? 0 : Long.parseLong(digits);
        if (montant < w.rechargeMin) { notice("Indiquez un montant d'au moins " + Tarif.fcfa(w.rechargeMin) + ".", 2); return; }
        if (montant > 500000) { notice("Montant trop élevé (500 000 FCFA au plus).", 2); return; }
        boolean om = "OM".equals(rechargeOp);
        Recharges.Attente a = new Recharges.Attente();
        a.op = om ? "OM" : "MOMO";
        a.montant = montant;
        a.numero = om ? w.numeroOm : w.numeroMomo;
        a.depuis = System.currentTimeMillis();
        Cloud.setRechargeAttente(this, a);
        String code = Recharges.codeUssd(om ? w.ussdOm : w.ussdMomo, a.numero, montant);
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code))));
        } catch (Exception e) {
            notice("Composez " + code + " sur votre téléphone, puis envoyez " + Tarif.fcfa(montant) + " au " + formatNumero(a.numero) + ".", 0);
        }
        render();
    }

    /** Recharge payée, en attente du SMS de l'opérateur. */
    private void rechargeEnCours(LinearLayout card, Cloud.Portefeuille w, Recharges.Attente a) {
        boolean om = "OM".equals(a.op);
        LinearLayout k = column(6);
        k.setBackground(box(GOOD_BG, 0, 12));
        k.setPadding(dp(12), dp(12), dp(12), dp(12));
        k.addView(label("Paiement en cours · " + (om ? "Orange Money" : "MTN MoMo")));
        k.addView(text("Dans le menu " + (om ? "Orange Money" : "MoMo") + ", faites un transfert :", 13, INK, false));
        TextView num = text("Numéro  " + formatNumero(a.numero), 18, om ? OM : MOMO, true);
        num.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        k.addView(num);
        TextView mt = text("Montant  " + SmsParser.fmt(a.montant * 100), 18, INK, true);
        mt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        k.addView(mt);
        if (!w.marchand.isEmpty()) k.addView(text("Nom affiché par l'opérateur : " + w.marchand, 12, MUTED, false));
        k.addView(text("Validez avec votre code secret. Dès que le SMS de confirmation arrive, l'application déclare le paiement "
                + "toute seule, avec son ID : vous n'avez rien à recopier.", 12, INK, false));
        LinearLayout act = new LinearLayout(this);
        act.addView(button("Rouvrir le menu", false, v -> {
            String code = Recharges.codeUssd(om ? w.ussdOm : w.ussdMomo, a.numero, a.montant);
            try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code)))); } catch (Exception ignored) {}
        }), weight(false));
        act.addView(button("Vérifier le SMS", true, v -> { notice("Recherche du SMS de confirmation…", 0); reload(true); }), weight(true));
        k.addView(act, fill());
        k.addView(small("Annuler ce paiement", MUTED, v -> { Cloud.setRechargeAttente(this, null); render(); }), fill());
        card.addView(k, fill());
    }

    // ---------- Personne (dettes et créances) dans un formulaire ----------

    /** Champ « Personne » et échéance, visibles seulement pour une catégorie de dette ou de créance. */
    private final class ChampPersonne {
        final LinearLayout box = column(6);
        final EditText nom = new EditText(MainActivity.this);
        final EditText echeance = new EditText(MainActivity.this);
        final TextView titre = text("", 13, MUTED, false);
        final TextView echeanceTitre = text("Remboursement prévu le (facultatif)", 13, MUTED, false);
        String numero;

        ChampPersonne(String nomParDefaut, String numero, Long echeanceParDefaut) {
            this.numero = numero;
            nom.setSingleLine(true);
            nom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            nom.setHint("Nom de la personne");
            if (nomParDefaut != null) nom.setText(nomParDefaut);
            echeance.setSingleLine(true);
            echeance.setInputType(InputType.TYPE_CLASS_DATETIME);
            echeance.setHint("JJ/MM/AAAA");
            if (echeanceParDefaut != null) echeance.setText(new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date(echeanceParDefaut)));
            box.addView(titre);
            box.addView(nom, fill());
            Dettes.Bilan db = Dettes.calculer(engagements, System.currentTimeMillis());
            List<String> sug = Dettes.suggestions(db, numero, 4);
            if (!sug.isEmpty()) {
                LinearLayout chips = new LinearLayout(MainActivity.this);
                for (String s : sug) {
                    TextView c = chip(s, ACCENT, GOOD_BG);
                    c.setOnClickListener(v -> nom.setText(s));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(dp(6));
                    chips.addView(c, lp);
                }
                HorizontalScrollView hs = new HorizontalScrollView(MainActivity.this);
                hs.addView(chips);
                box.addView(hs, fill());
            }
            box.addView(echeanceTitre);
            box.addView(echeance, fill());
        }

        /** La personne est demandée (catégorie de dette et application Dettes & créances active). */
        boolean requis() {
            return box.getVisibility() == View.VISIBLE;
        }

        void maj(String categorie) {
            Object[] n = actif(Modules.DETTES) ? Dettes.nature(categorie) : null;
            box.setVisibility(n == null ? View.GONE : View.VISIBLE);
            if (n == null) return;
            boolean creance = Dettes.CREANCE.equals(n[0]);
            boolean hausse = (Integer) n[1] > 0;
            titre.setText(creance ? (hausse ? "À qui prêtez-vous ?" : "Qui vous rembourse ?") : (hausse ? "Qui vous prête ?" : "Qui remboursez-vous ?"));
            echeanceTitre.setVisibility(hausse ? View.VISIBLE : View.GONE);
            echeance.setVisibility(hausse ? View.VISIBLE : View.GONE);
        }

        String nom() { return nom.getText().toString().trim(); }

        /** Échéance saisie, ou null ; -1 si la date est illisible. */
        Long echeance() {
            String s = echeance.getText().toString().trim();
            if (s.isEmpty() || echeance.getVisibility() != View.VISIBLE) return null;
            try {
                SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE);
                f.setLenient(false);
                return f.parse(s).getTime() + 20L * 3600 * 1000;
            } catch (Exception e) {
                return -1L;
            }
        }
    }

    /**
     * Après le classement d'une opération (ou une dépense suivie) : crée, met à jour ou retire son mouvement de dette.
     * À appeler hors du fil d'affichage.
     */
    private void majEngagement(Store st, String cleOp, String categorie, String tiers, String numero, long montant, long date, Long echeance, String motif) {
        if (!actif(Modules.DETTES)) return; // lecture seule : les dettes ne bougent plus
        String id = Dettes.idPour(cleOp);
        Object[] n = Dettes.nature(categorie);
        Dettes.Engagement ex = st.engagement(id);
        if (n == null || tiers == null || tiers.isEmpty()) {
            if (ex != null && !ex.annule) { ex.annule = true; st.setEngagement(ex); }
            return;
        }
        Dettes.Engagement e = ex != null ? ex : new Dettes.Engagement();
        e.id = id;
        e.cle = cleOp;
        e.tiers = tiers;
        e.numero = numero;
        e.type = (String) n[0];
        e.sens = (Integer) n[1];
        e.montant = montant;
        e.date = date;
        e.echeance = e.sens > 0 ? echeance : null;
        e.motif = motif == null ? "" : motif;
        e.annule = false;
        st.setEngagement(e);
    }

    private Dettes.Engagement engagementDe(String cleOp) {
        String id = Dettes.idPour(cleOp);
        for (Dettes.Engagement e : engagements) if (e.id.equals(id) && !e.annule) return e;
        return null;
    }

    // ---------- Écrans de détail de l'onglet Finances ----------

    /** Ferme un écran de détail (suivi, personne, catégorie) ; vrai s'il y en avait un. */
    private boolean fermerDetail() {
        boolean ouvert = suiviOuvert != null || personneOuverte != null || categorieOuverte != null || applicationsOuvert || assistantOuvert || analyseOuvert
                || projetOuvert != null;
        analyseOuvert = false;
        projetOuvert = null;
        applicationsOuvert = false;
        assistantOuvert = false;
        suiviOuvert = null;
        personneOuverte = null;
        categorieOuverte = null;
        return ouvert;
    }

    private void ouvrirSuivi(String cle) {
        fermerDetail();
        suiviOuvert = cle;
        pageIdx = PAGE_FINANCES;
        render();
        scroll.scrollTo(0, 0);
    }

    private void ouvrirPersonne(String nom) {
        fermerDetail();
        personneOuverte = nom;
        pageIdx = PAGE_FINANCES;
        render();
        scroll.scrollTo(0, 0);
    }

    private void ouvrirCategorie(String cat, int sens) {
        fermerDetail();
        categorieOuverte = cat == null ? "" : cat;
        categorieSens = sens;
        render();
        scroll.scrollTo(0, 0);
    }

    private View retour(String titre) {
        Button b = small("◂  " + titre, ACCENT, v -> { fermerDetail(); render(); });
        return b;
    }

    /** Barre de progression (part utilisée). */
    private View barre(long fait, long total, int couleur) {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(box(BG, 0, 4));
        int plein = (int) Math.round(Math.min(1.0, fait / (double) Math.max(1, total)) * 1000);
        View f = new View(this);
        f.setBackground(box(couleur, 0, 4));
        bar.addView(f, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, plein)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, 1000 - plein)));
        return bar;
    }

    // ----- Entrées et sorties par catégorie -----

    private View entreesSortiesCard(Caisse.Bilan b) {
        LinearLayout c = card();
        c.addView(text("Entrées et sorties du mois", 16, INK, true));
        c.addView(text("Par catégorie, tous moyens confondus (OM, MoMo, banques, espèces). Touchez une ligne pour voir les opérations.", 12, MUTED, false));
        for (int sens : new int[] {1, -1}) {
            List<Map.Entry<String, Caisse.Total>> lignes = new ArrayList<>();
            long total = 0;
            for (Map.Entry<String, Caisse.Total> e : b.totaux.entrySet()) {
                long v = sens > 0 ? e.getValue().entrees : e.getValue().sorties;
                if (v <= 0) continue;
                lignes.add(e);
                total += v;
            }
            final int s = sens;
            java.util.Collections.sort(lignes, (x, y) -> Long.compare(s > 0 ? y.getValue().entrees : y.getValue().sorties,
                    s > 0 ? x.getValue().entrees : x.getValue().sorties));
            LinearLayout head = new LinearLayout(this);
            head.setPadding(0, dp(8), 0, dp(2));
            head.addView(label(sens > 0 ? "Entrées" : "Sorties"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView tt = text((sens > 0 ? "+" : "−") + SmsParser.fmt(total), 13, sens > 0 ? GOOD : INK, true);
            tt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            head.addView(tt);
            c.addView(head, fill());
            if (lignes.isEmpty()) { c.addView(text("Aucune " + (sens > 0 ? "entrée" : "sortie") + " ce mois-ci.", 12, MUTED, false)); continue; }
            for (Map.Entry<String, Caisse.Total> e : lignes) {
                long v = sens > 0 ? e.getValue().entrees : e.getValue().sorties;
                LinearLayout r = column(2);
                r.setPadding(0, dp(5), 0, dp(5));
                LinearLayout top = new LinearLayout(this);
                top.setGravity(Gravity.CENTER_VERTICAL);
                View dot = new View(this);
                dot.setBackground(box(catColor(e.getKey()), 0, 5));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
                dl.setMarginEnd(dp(8));
                top.addView(dot, dl);
                top.addView(text(Caisse.categorieNom(e.getKey()), 14, e.getKey() == null ? BAD : INK, false),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView val = text(SmsParser.fmt(v) + "  " + Math.round(v * 100.0 / Math.max(1, total)) + " %  ›", 13, INK, true);
                val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                top.addView(val);
                r.addView(top, fill());
                r.addView(barre(v, total, sens > 0 ? GOOD : catColor(e.getKey())), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));
                final String cat = e.getKey();
                r.setOnClickListener(x -> ouvrirCategorie(cat, s));
                c.addView(r, fill());
            }
        }
        return c;
    }

    /** Opérations du mois affiché pour une catégorie et un sens. */
    private void categoriePage() {
        long now = System.currentTimeMillis();
        long debut = Finances.debutMois(now, moisDecalage);
        long fin = moisDecalage == 0 ? now : Finances.debutMois(now, moisDecalage + 1) - 1;
        String cat = categorieOuverte.isEmpty() ? null : categorieOuverte;
        page.addView(retour("Finances"));
        String nomMois = new SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(new Date(debut));
        pageTitle(Caisse.categorieNom(cat), (categorieSens > 0 ? "Entrées · " : "Sorties · ") + nomMois);
        if (result == null) return;
        Caisse.Bilan b = computeBilan(debut, fin);
        Caisse.Total t = b.total(cat);
        long tot = categorieSens > 0 ? t.entrees : t.sorties;
        TextView big = big((categorieSens > 0 ? "+" : "−") + SmsParser.fmt(tot) + " FCFA");
        page.addView(big);
        LinearLayout list = listCard();
        int n = 0;
        for (Caisse.Ligne l : b.lignes) {
            if ((l.sms.sens > 0) != (categorieSens > 0)) continue;
            String lc = Caisse.REPARTI.equals(l.categorie) ? null : l.categorie;
            if (cat == null ? lc != null : !cat.equals(lc)) continue;
            if (Caisse.REPARTI.equals(l.categorie)) continue;
            list.addView(ligneRow(l, false), fill());
            n++;
        }
        for (Caisse.Saisie s : b.mouvementsEspeces) {
            if (s.annule || (s.sens > 0) != (categorieSens > 0)) continue;
            if (cat == null || !cat.equals(Caisse.normaliserSaisie(s.categorie, s.sens))) continue;
            list.addView(especeRow(s), fill());
            n++;
        }
        for (Caisse.Saisie s : b.depensesSuivies) {
            if (s.annule || (s.sens > 0) != (categorieSens > 0)) continue;
            if (cat == null || !cat.equals(Caisse.normaliserSaisie(s.categorie, s.sens))) continue;
            list.addView(depenseSuivieRow(s), fill());
            n++;
        }
        if (n == 0) list.addView(empty("Aucune opération."));
        page.addView(list, fill());
    }

    private View depenseSuivieRow(Caisse.Saisie s) {
        LinearLayout row = column(3);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginEnd(dp(8));
        top.addView(tag("Suivi", PURPLE), tl);
        top.addView(text(s.libelle.isEmpty() ? Caisse.categorieNom(Caisse.normaliserSaisie(s.categorie, s.sens)) : s.libelle, 15, INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView amt = text("−" + SmsParser.fmt(s.montant), 15, INK, true);
        amt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(amt);
        row.addView(top, fill());
        row.addView(text(when(s.date) + " · payé avec un argent suivi", 12, MUTED, false));
        if (s.source != null) row.setOnClickListener(v -> ouvrirSuivi(s.source));
        row.addView(divider());
        return row;
    }

    // ----- Suivi de l'argent -----

    private List<Suivi.Source> sourcesSuivies() {
        return Suivi.sources(result, saisies, liens, classements);
    }

    private void ajouterSuiviEtDettes(Finances.Tresorerie tr) {
        if (visible(Modules.SUIVI)) {
            if (!actif(Modules.SUIVI)) page.addView(bandeauLecture(Modules.SUIVI), fill());
            page.addView(suiviCard(), fill());
        } else {
            page.addView(promoModule(Modules.SUIVI, "Salaire ou argent reçu : suivez ce que vous en faites jusqu'au dernier franc. Répartissez un retrait en dépenses avec le reste à justifier."), fill());
        }
        if (visible(Modules.DETTES)) {
            if (!actif(Modules.DETTES)) page.addView(bandeauLecture(Modules.DETTES), fill());
            page.addView(dettesCard(tr), fill());
        } else {
            page.addView(promoModule(Modules.DETTES, "Qui vous doit, à qui vous devez, combien et pour quand : prêts, emprunts et remboursements par personne, avec les retards."), fill());
        }
    }

    private View suiviCard() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Suivi de l'argent", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(small("+ Suivre", ACCENT, v -> choisirSource()));
        c.addView(head, fill());
        List<Suivi.Source> src = sourcesSuivies();
        if (src.isEmpty()) {
            c.addView(text("Salaire reçu, retrait, gros transfert : suivez ce que vous en faites, dépense par dépense, et voyez ce qui reste. "
                    + "Touchez « + Suivre », ou ouvrez une opération et choisissez « Suivre cet argent ».", 13, MUTED, false));
            return c;
        }
        int n = 0;
        for (Suivi.Source s : src) {
            if (n++ >= 5) break;
            c.addView(sourceRow(s), fill());
        }
        if (src.size() > 5) c.addView(text("+ " + (src.size() - 5) + " autre(s) suivi(s) plus ancien(s)", 12, MUTED, false));
        return c;
    }

    private View sourceRow(Suivi.Source s) {
        LinearLayout r = column(3);
        r.setPadding(0, dp(6), 0, dp(6));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text((s.sens > 0 ? "+" : "−") + SmsParser.fmt(s.montant) + " · " + s.libelle, 14, INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(text("›", 16, MUTED, true));
        r.addView(top, fill());
        boolean depasse = s.reste() < 0;
        r.addView(barre(s.utilise, s.montant, depasse ? BAD : s.reste() == 0 ? GOOD : PURPLE),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        String resteNom = Suivi.repartition(s) ? "reste à justifier " : "reste ";
        r.addView(text(when(s.date).substring(0, 10) + " · utilisé " + SmsParser.fmt(s.utilise) + " · "
                + (depasse ? "dépassé de " + SmsParser.fmt(-s.reste()) : resteNom + SmsParser.fmt(s.reste())), 12, depasse ? BAD : MUTED, depasse));
        r.setOnClickListener(v -> ouvrirSuivi(s.cle));
        return r;
    }

    /** Choix d'un argent à suivre : entrées et sorties récentes (SMS et espèces), les plus grosses en premier. */
    private void choisirSource() {
        if (result == null || !exiger(Modules.SUIVI)) return;
        LinearLayout list = column(4);
        list.setPadding(dp(16), dp(8), dp(16), 0);
        list.addView(text("Opérations des 60 derniers jours, les plus importantes d'abord.", 12, MUTED, false));
        long depuis = System.currentTimeMillis() - 60L * 24 * 3600 * 1000;
        List<Object[]> ops = new ArrayList<>();
        for (Txn t : result.tx) {
            if (t.isBalanceCheck() || t.date < depuis) continue;
            if (liens.containsKey(Caisse.cle(t))) continue;
            ops.add(new Object[] {t.montant, t});
        }
        for (Caisse.Saisie s : saisies) {
            if (s.annule || s.uid == null || !Caisse.ESPECES.equals(s.mode) || s.sens <= 0 || s.date < depuis) continue;
            if (liens.containsKey(Suivi.cle(s))) continue;
            ops.add(new Object[] {s.montant, s});
        }
        java.util.Collections.sort(ops, (a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        final AlertDialog[] dlg = new AlertDialog[1];
        int n = 0;
        for (Object[] o : ops) {
            if (n++ >= 25) break;
            String cle, titre, detail;
            if (o[1] instanceof Txn) {
                Txn t = (Txn) o[1];
                cle = Caisse.cle(t);
                String qui = t.nom != null ? t.nom : t.numero;
                titre = (t.sens > 0 ? "+" : "−") + SmsParser.fmt(t.montant) + " · " + t.typeLabel();
                detail = opShort(t.op) + " · " + when(t.date) + (qui != null ? " · " + qui : "");
            } else {
                Caisse.Saisie s = (Caisse.Saisie) o[1];
                cle = Suivi.cle(s);
                titre = "+" + SmsParser.fmt(s.montant) + " · Espèces";
                detail = when(s.date) + (s.libelle.isEmpty() ? "" : " · " + s.libelle);
            }
            LinearLayout r = column(1);
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            r.setBackground(box(SURFACE, LINE, 10));
            r.addView(text(titre, 14, INK, true));
            r.addView(text(detail, 12, MUTED, false));
            final String k = cle;
            r.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); commencerSuivi(k); });
            list.addView(r, fill());
        }
        if (ops.isEmpty()) list.addView(text("Aucune opération récente.", 13, MUTED, false));
        ScrollView sv = new ScrollView(this);
        sv.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg[0] = new AlertDialog.Builder(this).setTitle("Quel argent suivre ?").setView(sv).setNegativeButton("Fermer", null).show();
    }

    private void commencerSuivi(String cle) {
        if (!exiger(Modules.SUIVI)) return;
        io.execute(() -> {
            Store st = Store.get(this);
            if (!cle.equals(st.liens().get(cle))) st.setLien(cle, cle);
            SyncJob.soon(this);
            Map<String, String> l = st.liens();
            runOnUiThread(() -> { liens = l; ouvrirSuivi(cle); });
        });
    }

    private void suiviPage() {
        page.addView(retour("Finances"));
        Suivi.Source s = Suivi.trouver(sourcesSuivies(), suiviOuvert);
        if (s == null) {
            pageTitle("Suivi de l'argent", null);
            page.addView(empty("Cette opération n'est pas sur ce téléphone, ou son suivi a été arrêté."));
            return;
        }
        boolean rep = Suivi.repartition(s);
        pageTitle(rep ? "Répartir cette sortie" : "Suivre cet argent", s.libelle);

        LinearLayout h = column(6);
        h.setBackground(box(ACCENT, 0, 18));
        h.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView lab = text((rep ? "SORTIE DU " : "REÇU LE ") + when(s.date).toUpperCase(Locale.FRANCE), 11, ON_ACCENT_SOFT, true);
        lab.setLetterSpacing(0.05f);
        h.addView(lab);
        TextView tot = text(SmsParser.fmt(s.montant) + " FCFA", 28, Color.WHITE, true);
        tot.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        h.addView(tot);
        if (s.categorie != null) h.addView(text("Catégorie : " + Caisse.categorieNom(s.categorie), 12, ON_ACCENT_SOFT, false));
        h.addView(barre(s.utilise, s.montant, Color.WHITE), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        LinearLayout chiffres = new LinearLayout(this);
        LinearLayout u = column(0);
        u.addView(text("Utilisé", 11, ON_ACCENT_SOFT, false));
        u.addView(text(SmsParser.fmt(s.utilise), 16, Color.WHITE, true));
        chiffres.addView(u, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout rs = column(0);
        rs.addView(text(s.reste() < 0 ? "Dépassé de" : rep ? "Reste à justifier" : "Reste disponible", 11, ON_ACCENT_SOFT, false));
        rs.addView(text(SmsParser.fmt(Math.abs(s.reste())), 16, Color.WHITE, true));
        chiffres.addView(rs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.addView(chiffres, fill());
        page.addView(h, fill());

        LinearLayout act = new LinearLayout(this);
        act.addView(button("+ Dépense", true, v -> ajouterDepenseSuivie(s)), weight(false));
        act.addView(button("Rattacher une opération", false, v -> rattacher(s)), weight(true));
        page.addView(act, fill());
        page.addView(text(rep
                ? "Chaque dépense remplace une part de cette sortie dans vos totaux (transport, salaire, prêt à quelqu'un…). "
                  + "Le reste garde la catégorie de la sortie ; sans catégorie, il reste « À classer »."
                : "Ajoutez ce que vous avez payé avec cet argent (en espèces par exemple), ou rattachez une sortie déjà enregistrée "
                  + "(transfert OM, retrait, paiement en espèces).", 12, MUTED, false));

        LinearLayout list = listCard();
        TextView t = text("Utilisations", 16, INK, true);
        t.setPadding(0, dp(10), 0, dp(4));
        list.addView(t);
        if (s.usages.isEmpty()) list.addView(empty("Aucune utilisation pour l'instant."));
        for (Suivi.Usage x : s.usages) list.addView(usageRow(s, x), fill());
        page.addView(list, fill());
        if (s.usages.isEmpty()) {
            page.addView(small("Arrêter ce suivi", MUTED, v -> io.execute(() -> {
                Store.get(this).setLien(s.cle, null);
                SyncJob.soon(this);
                runOnUiThread(() -> { fermerDetail(); reload(false); });
            })), fill());
        }
    }

    private View usageRow(Suivi.Source src, Suivi.Usage x) {
        LinearLayout row = column(2);
        row.setPadding(0, dp(9), 0, dp(9));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginEnd(dp(8));
        top.addView(tag(x.depense ? "Dépense" : x.sms != null ? opShort(x.sms.op) : "Espèces", x.depense ? PURPLE : x.sms != null ? opColor(x.sms.op) : CASH), tl);
        String titre = x.depense ? (x.libelle == null || x.libelle.isEmpty() ? Caisse.categorieNom(x.categorie) : x.libelle) : x.libelle;
        top.addView(text(titre, 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView m = text("−" + SmsParser.fmt(x.montant), 14, INK, true);
        m.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(m);
        row.addView(top, fill());
        LinearLayout bas = new LinearLayout(this);
        bas.setGravity(Gravity.CENTER_VERTICAL);
        Dettes.Engagement e = engagementDe(x.cle);
        bas.addView(text(when(x.date) + " · " + Caisse.categorieNom(x.categorie) + (e != null ? " · " + e.tiers : ""), 12, MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bas.addView(small(x.depense ? "Retirer" : "Détacher", BAD, v -> io.execute(() -> {
            Store st = Store.get(this);
            if (x.depense) {
                st.annulerSaisie(x.saisie.id);
                majEngagement(st, x.cle, null, null, null, 0, 0, null, null);
            } else {
                st.setLien(x.cle, null);
            }
            SyncJob.soon(this);
            runOnUiThread(() -> reload(false));
        })));
        row.addView(bas, fill());
        row.addView(divider());
        return row;
    }

    /** Dépense payée avec l'argent suivi : catégorie de sortie, montant (le reste par défaut), motif, personne si prêt. */
    private void ajouterDepenseSuivie(Suivi.Source src) {
        if (!exiger(Modules.SUIVI)) return;
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(text("Montant (FCFA)", 13, MUTED, false));
        EditText montant = amountField("ex. 10000");
        if (src.reste() > 0) montant.setText(String.valueOf(src.reste() / 100));
        form.addView(montant, fill());
        final String[] cat = {null};
        form.addView(text("Catégorie de la dépense", 13, MUTED, false));
        LinearLayout grille = column(6);
        TextView aide = text("", 12, MUTED, false);
        ChampPersonne pers = new ChampPersonne(null, null, null);
        categorieGrid(grille, aide, -1, cat, () -> pers.maj(cat[0]));
        form.addView(grille, fill());
        form.addView(aide);
        form.addView(pers.box, fill());
        pers.maj(cat[0]);
        EditText motif = new EditText(this);
        motif.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        motif.setHint("Motif (ex. Taxi pour le marché)");
        form.addView(motif, fill());
        ScrollView sv = new ScrollView(this);
        sv.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(Suivi.repartition(src) ? "Part de la sortie" : "Dépense payée avec cet argent")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    Long cents = SmsParser.money(montant.getText().toString());
                    if (cents == null || cents <= 0) { notice("Montant invalide : dépense non enregistrée.", 2); return; }
                    if (cat[0] == null) { notice("Choisissez la catégorie de la dépense.", 2); return; }
                    if (pers.requis() && pers.nom().isEmpty()) { notice("Indiquez la personne.", 2); return; }
                    Long ech = pers.echeance();
                    if (ech != null && ech < 0) { notice("Date de remboursement illisible : écrivez JJ/MM/AAAA.", 2); return; }
                    Caisse.Saisie s = new Caisse.Saisie();
                    // Une part de sortie garde la date de la sortie (les totaux du mois restent justes) ; une dépense
                    // payée avec un salaire est datée d'aujourd'hui.
                    s.date = Suivi.repartition(src) ? src.date : System.currentTimeMillis();
                    s.mode = Caisse.SUIVI;
                    s.sens = -1;
                    s.montant = cents;
                    s.categorie = cat[0];
                    s.libelle = motif.getText().toString().trim();
                    s.source = src.cle;
                    final String nomP = pers.nom();
                    io.execute(() -> {
                        Store st = Store.get(this);
                        st.addSaisie(s);
                        majEngagement(st, Suivi.cle(s), cat[0], nomP, null, cents, s.date, ech, s.libelle);
                        SyncJob.soon(this);
                        runOnUiThread(() -> { notice("Dépense de " + SmsParser.fmt(cents) + " FCFA ajoutée au suivi.", 1); reload(false); });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void rattacher(Suivi.Source src) {
        if (!exiger(Modules.SUIVI)) return;
        List<Suivi.Usage> ops = Suivi.rattachables(src, result, saisies, liens, 30);
        LinearLayout list = column(4);
        list.setPadding(dp(16), dp(8), dp(16), 0);
        list.addView(text("Sorties depuis le " + when(src.date).substring(0, 10) + ", pas encore rattachées.", 12, MUTED, false));
        final AlertDialog[] dlg = new AlertDialog[1];
        for (Suivi.Usage u : ops) {
            LinearLayout r = column(1);
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            r.setBackground(box(SURFACE, LINE, 10));
            r.addView(text("−" + SmsParser.fmt(u.montant) + " · " + u.libelle, 14, INK, true));
            Caisse.Classement c = u.sms != null ? classements.get(u.cle) : null;
            String cat = u.sms != null ? (c != null ? Caisse.categorieNom(Caisse.normaliser(c.categorie, -1)) : "À classer") : Caisse.categorieNom(u.categorie);
            r.addView(text(when(u.date) + " · " + cat, 12, MUTED, false));
            r.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                io.execute(() -> {
                    Store.get(this).setLien(u.cle, src.cle);
                    SyncJob.soon(this);
                    runOnUiThread(() -> reload(false));
                });
            });
            list.addView(r, fill());
        }
        if (ops.isEmpty()) list.addView(text("Aucune sortie à rattacher.", 13, MUTED, false));
        ScrollView sv = new ScrollView(this);
        sv.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg[0] = new AlertDialog.Builder(this).setTitle("Rattacher une opération").setView(sv).setNegativeButton("Fermer", null).show();
    }

    // ----- Dettes et créances -----

    private View dettesCard(Finances.Tresorerie tr) {
        long now = System.currentTimeMillis();
        Dettes.Bilan d = Dettes.calculer(engagements, now);
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Dettes et créances", 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(small("+ Ajouter", ACCENT, v -> nouvelEngagement(null, 0)));
        c.addView(head, fill());
        LinearLayout tot = new LinearLayout(this);
        LinearLayout a = column(0);
        a.addView(label("On me doit"));
        TextView av = text(SmsParser.fmt(d.onMeDoit), 18, GOOD, true);
        av.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        a.addView(av);
        tot.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout b = column(0);
        b.addView(label("Je dois"));
        TextView bv = text(SmsParser.fmt(d.jeDois), 18, d.jeDois > 0 ? BAD : INK, true);
        bv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.addView(bv);
        tot.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(tot, fill());
        if (d.enRetard > 0) {
            TextView r = text("⚠  " + d.enRetard + " remboursement(s) en retard", 13, BAD, true);
            r.setBackground(box(BAD_BG, 0, 10));
            r.setPadding(dp(10), dp(6), dp(10), dp(6));
            c.addView(r, fill());
        }
        if (d.personnes.isEmpty()) {
            c.addView(text("Classez une sortie en « Prêt accordé » ou « Remboursement de dette », une entrée en « Emprunt » ou "
                    + "« Remboursement reçu », et indiquez la personne : les soldes se calculent seuls. Une dette ancienne se saisit avec « + Ajouter ».", 13, MUTED, false));
            return c;
        }
        int n = 0;
        for (Dettes.Personne p : d.personnes) {
            if (p.soldee() || n >= 6) continue;
            c.addView(personneRow(p, now), fill());
            n++;
        }
        long soldees = 0;
        for (Dettes.Personne p : d.personnes) if (p.soldee()) soldees++;
        c.addView(small("Toutes les personnes (" + d.personnes.size() + (soldees > 0 ? ", dont " + soldees + " soldée(s)" : "") + ")  ›", ACCENT,
                v -> ouvrirPersonne("")), fill());
        c.addView(divider());
        if (tr != null) {
            long net = tr.total + d.onMeDoit - d.jeDois;
            c.addView(kvSmall("Trésorerie + on me doit − je dois", (net < 0 ? "−" : "") + SmsParser.fmt(Math.abs(net)) + " FCFA"));
        }
        return c;
    }

    private View personneRow(Dettes.Personne p, long now) {
        LinearLayout r = column(2);
        r.setPadding(0, dp(7), 0, dp(7));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(p.nom, 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        long net = p.net();
        TextView v = text(p.soldee() ? "soldé" : (net > 0 ? "me doit " : "je dois ") + SmsParser.fmt(Math.abs(net)) + "  ›", 13,
                p.soldee() ? MUTED : net > 0 ? GOOD : BAD, true);
        top.addView(v);
        r.addView(top, fill());
        String info = "dernier mouvement " + when(p.derniere).substring(0, 10);
        if (p.echeance != null) info = (p.enRetard(now) ? "⚠ en retard depuis le " : "à rembourser avant le ") + when(p.echeance).substring(0, 10);
        r.addView(text(info, 12, p.enRetard(now) ? BAD : MUTED, p.enRetard(now)));
        r.setOnClickListener(x -> ouvrirPersonne(p.nom));
        return r;
    }

    private void personnePage() {
        long now = System.currentTimeMillis();
        Dettes.Bilan d = Dettes.calculer(engagements, now);
        page.addView(retour("Finances"));
        if (personneOuverte.isEmpty()) {
            pageTitle("Dettes et créances", "On me doit " + SmsParser.fmt(d.onMeDoit) + " · je dois " + SmsParser.fmt(d.jeDois));
            page.addView(button("+ Ajouter une dette ou une créance", true, v -> nouvelEngagement(null, 0)), fill());
            LinearLayout l = listCard();
            if (d.personnes.isEmpty()) l.addView(empty("Aucune dette ni créance."));
            for (Dettes.Personne p : d.personnes) { l.addView(personneRow(p, now), fill()); l.addView(divider()); }
            page.addView(l, fill());
            return;
        }
        Dettes.Personne p = d.personne(personneOuverte);
        if (p == null) { pageTitle(personneOuverte, null); page.addView(empty("Plus aucun mouvement avec cette personne.")); return; }
        pageTitle(p.nom, p.numero != null ? formatNumero(p.numero) : null);
        long net = p.net();
        LinearLayout h = column(4);
        h.setBackground(box(p.soldee() ? SURFACE : net > 0 ? GOOD_BG : BAD_BG, p.soldee() ? LINE : 0, 16));
        h.setPadding(dp(16), dp(14), dp(16), dp(14));
        h.addView(label(p.soldee() ? "Compte soldé" : net > 0 ? "Cette personne vous doit" : "Vous lui devez"));
        TextView big = text(SmsParser.fmt(Math.abs(net)) + " FCFA", 26, p.soldee() ? MUTED : net > 0 ? GOOD : BAD, true);
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        h.addView(big);
        if (p.echeance != null) h.addView(text((p.enRetard(now) ? "⚠  Remboursement prévu le " : "Remboursement prévu le ")
                + when(p.echeance).substring(0, 10), 13, p.enRetard(now) ? BAD : INK, p.enRetard(now)));
        page.addView(h, fill());

        LinearLayout act = new LinearLayout(this);
        act.addView(button(net >= 0 ? "Il/elle me rembourse" : "Je rembourse", true, v -> nouvelEngagement(p.nom, net >= 0 ? 3 : 5)), weight(false));
        act.addView(button("Autre mouvement", false, v -> nouvelEngagement(p.nom, net >= 0 ? 2 : 4)), weight(true));
        page.addView(act, fill());
        page.addView(text("Un remboursement par OM ou MoMo se fait en classant le SMS (« Remboursement reçu » ou « Remboursement de dette ») "
                + "avec le nom de cette personne. Les boutons ci-dessus servent aux espèces et aux dettes anciennes.", 12, MUTED, false));

        LinearLayout l = listCard();
        TextView t = text("Historique", 16, INK, true);
        t.setPadding(0, dp(10), 0, dp(4));
        l.addView(t);
        for (Dettes.Engagement e : p.mouvements) {
            LinearLayout r = column(2);
            r.setPadding(0, dp(8), 0, dp(8));
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(text(Dettes.libelle(e), 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            boolean augmente = e.sens > 0;
            boolean bonPourMoi = Dettes.CREANCE.equals(e.type) != augmente; // remboursement reçu ou dette qui baisse
            TextView m = text((augmente ? "+" : "−") + SmsParser.fmt(e.montant), 14,
                    bonPourMoi ? GOOD : INK, true);
            m.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            top.addView(m);
            r.addView(top, fill());
            String src = e.cle == null ? "saisie à la main" : e.cle.startsWith("S|") ? "espèces ou dépense suivie" : opShort(e.cle.substring(0, e.cle.indexOf('|')));
            r.addView(text(when(e.date) + " · " + src + (e.motif == null || e.motif.isEmpty() ? "" : " · " + e.motif)
                    + (e.echeance != null ? " · échéance " + when(e.echeance).substring(0, 10) : ""), 12, MUTED, false));
            if (e.cle == null) r.setOnClickListener(v -> supprimerEngagement(e));
            l.addView(r, fill());
            l.addView(divider());
        }
        page.addView(l, fill());
    }

    private void supprimerEngagement(Dettes.Engagement e) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer ce mouvement ?")
                .setMessage(Dettes.libelle(e) + " · " + SmsParser.fmt(e.montant) + " FCFA · " + e.tiers)
                .setPositiveButton("Supprimer", (d, w) -> io.execute(() -> {
                    e.annule = true;
                    Store.get(this).setEngagement(e);
                    SyncJob.soon(this);
                    runOnUiThread(() -> reload(false));
                }))
                .setNegativeButton("Garder", null)
                .show();
    }

    /**
     * Nouveau mouvement saisi à la main : dette ou créance ancienne (sans mouvement d'argent), ou prêt / remboursement
     * en espèces (enregistré aussi dans la caisse espèces).
     */
    private void nouvelEngagement(String nomParDefaut, int defaut) {
        if (!exiger(Modules.DETTES)) return;
        final String[][] choix = {
                {"Il/elle me doit déjà (dette ancienne)", Dettes.CREANCE, "1", null},
                {"Je lui dois déjà (dette ancienne)", Dettes.DETTE, "1", null},
                {"Je lui prête en espèces", Dettes.CREANCE, "1", Caisse.PRET_ACCORDE},
                {"Il/elle me rembourse en espèces", Dettes.CREANCE, "-1", Caisse.REMB_RECU},
                {"Il/elle me prête en espèces", Dettes.DETTE, "1", Caisse.PRET_RECU},
                {"Je le/la rembourse en espèces", Dettes.DETTE, "-1", Caisse.REMB_PRET},
        };
        LinearLayout form = column(8);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        ChampPersonne pers = new ChampPersonne(nomParDefaut, null, null);
        pers.titre.setText("Personne");
        form.addView(pers.box, fill());
        final int[] k = {defaut};
        LinearLayout opts = column(4);
        Runnable[] peindre = new Runnable[1];
        peindre[0] = () -> {
            opts.removeAllViews();
            for (int i = 0; i < choix.length; i++) {
                final int j = i;
                boolean on = i == k[0];
                TextView o = text((on ? "●  " : "○  ") + choix[i][0], 14, on ? ACCENT : INK, on);
                o.setPadding(dp(10), dp(8), dp(10), dp(8));
                o.setBackground(box(on ? GOOD_BG : SURFACE, on ? ACCENT : LINE, 10));
                o.setOnClickListener(v -> { k[0] = j; peindre[0].run(); });
                opts.addView(o, fill());
            }
            boolean hausse = "1".equals(choix[k[0]][2]);
            pers.echeanceTitre.setVisibility(hausse ? View.VISIBLE : View.GONE);
            pers.echeance.setVisibility(hausse ? View.VISIBLE : View.GONE);
        };
        peindre[0].run();
        form.addView(opts, fill());
        form.addView(text("Montant (FCFA)", 13, MUTED, false));
        EditText montant = amountField("ex. 20000");
        form.addView(montant, fill());
        EditText motif = new EditText(this);
        motif.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        motif.setHint("Motif (facultatif)");
        form.addView(motif, fill());
        ScrollView sv = new ScrollView(this);
        sv.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Dette ou créance")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String nom = pers.nom();
                    Long cents = SmsParser.money(montant.getText().toString());
                    if (nom.isEmpty()) { notice("Indiquez la personne.", 2); return; }
                    if (cents == null || cents <= 0) { notice("Montant invalide.", 2); return; }
                    Long ech = pers.echeance();
                    if (ech != null && ech < 0) { notice("Date de remboursement illisible : écrivez JJ/MM/AAAA.", 2); return; }
                    String[] c = choix[k[0]];
                    String m = motif.getText().toString().trim();
                    long now = System.currentTimeMillis();
                    io.execute(() -> {
                        Store st = Store.get(this);
                        if (c[3] == null) {
                            Dettes.Engagement e = new Dettes.Engagement();
                            e.id = java.util.UUID.randomUUID().toString();
                            e.tiers = nom;
                            e.type = c[1];
                            e.sens = 1;
                            e.montant = cents;
                            e.date = now;
                            e.echeance = ech;
                            e.motif = m;
                            st.setEngagement(e);
                        } else {
                            Caisse.Saisie s = new Caisse.Saisie();
                            s.date = now;
                            s.mode = Caisse.ESPECES;
                            s.sens = Caisse.sensDe(c[3]);
                            s.montant = cents;
                            s.categorie = c[3];
                            s.libelle = (m.isEmpty() ? Caisse.categorieNom(c[3]) : m) + " · " + nom;
                            st.addSaisie(s);
                            majEngagement(st, Suivi.cle(s), c[3], nom, null, cents, now, ech, m);
                        }
                        SyncJob.soon(this);
                        runOnUiThread(() -> { notice("Enregistré pour " + nom + ".", 1); personneOuverte = nom; reload(false); });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- Export Excel ----------

    private void lancerExport() {
        final boolean toutes = vueToutes && plusieursCaisses() && vuePrete();
        if (vueToutes && plusieursCaisses() && !toutes) { notice("Ouvrez d'abord « Toutes mes caisses » dans l'onglet Caisse.", 0); return; }
        if (!toutes && bilan == null) { notice("Rien à exporter pour l'instant.", 0); return; }
        final int nb = toutes ? nbToutes() : nbOperations(bilan);
        final String label = (toutes ? "Toutes mes caisses · " : "") + periodLabel();
        final long[] r = range();
        exportToutes = toutes;
        exportVue = toutes ? vueData : null;
        if (!Cloud.configured()) {
            preparerExport(nb, label, r, false);
            return;
        }
        if (!Cloud.loggedIn(this)) {
            notice("Créez votre compte pour exporter : votre premier export est offert.", 0);
            showPage(PAGE_COMPTE);
            return;
        }
        Cloud.Portefeuille w = Cloud.portefeuille(this);
        if (w.suspendu) { notice("Compte suspendu : contactez le service client.", 2); return; }
        Tarif.Decision d = w.decider(nb);
        if (!d.possible) {
            rechargeOuverte = true;
            showPage(PAGE_EXPORT);
            notice("Solde insuffisant : il manque " + Tarif.fcfa(d.manque) + ". Rechargez votre porte-monnaie.", 2);
            return;
        }
        String detail = d.inclus ? "Export inclus avec Finances & budgets : rien n'est débité."
                : d.offert ? "Cet export vous est offert."
                : d.prix == 0 ? "Export gratuit (aucune opération sur la période)."
                : Tarif.fcfa(d.prix) + " seront débités de votre porte-monnaie.\nSolde après l'export : " + Tarif.fcfa(d.soldeApres) + ".";
        new AlertDialog.Builder(this)
                .setTitle("Exporter en Excel")
                .setMessage(nb + " opération" + (nb > 1 ? "s" : "") + " · " + label + "\n\n" + detail
                        + "\n\nLe montant est débité au moment où le fichier est enregistré.")
                .setPositiveButton("Choisir où enregistrer", (x, y) -> preparerExport(nb, label, r, true))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void preparerExport(int nb, String label, long[] r, boolean payant) {
        exportNb = nb;
        exportLabel = label;
        exportRange = r;
        exportPayant = payant;
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        i.putExtra(Intent.EXTRA_TITLE, (exportToutes ? "toutes-mes-caisses-" : "caisse-mobile-money-") + day + ".xlsx");
        startActivityForResult(i, REQ_XLSX);
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code != REQ_XLSX || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final Analyzer.Result r = result;
        final long[] range = exportRange;
        final String label = exportLabel;
        final int nb = exportNb;
        final boolean payant = exportPayant;
        final boolean toutes = exportToutes;
        final org.json.JSONObject vue = exportVue;
        final Caisse.Bilan b = toutes ? null : computeBilan(range[0], range[1]);
        final long maintenant = System.currentTimeMillis();
        final Soldes.Releve rvExport = toutes ? null : computeSoldes(range[0], range[1]);
        final Analyse.Rapport anExport = toutes || !actif(Modules.ANALYSE) || result == null ? null : analyse();
        notice("Préparation du rapport Excel…", 0);
        io.execute(() -> {
            String msg;
            int niveau = 1;
            Cloud.Debit debit = null;
            try {
                // 1. Débit (en ligne, ou sur le dernier solde connu si pas de réseau).
                if (payant) debit = Cloud.payerExport(this, nb, label);
                // 2. Écriture du fichier.
                byte[] xlsx = toutes ? Exporter.consolide(vue, label, System.currentTimeMillis())
                        : Exporter.xlsx(r, b, range[0], range[1], label, maintenant,
                                Finances.tresorerie(r, rvExport.comptes.contains(Soldes.ESPECES) ? rvExport.cloture.get(Soldes.ESPECES) : null,
                                        Store.get(this).avoirs(), range[1]), Store.get(this).budgets(),
                                Dettes.calculer(Store.get(this).engagements(), maintenant), rvExport, anExport);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(xlsx);
                }
                msg = "Rapport Excel enregistré · " + label + ".";
                if (debit != null) {
                    if (debit.inclus) msg += " Inclus avec Finances & budgets.";
                    else if (debit.offert) msg += " Export offert.";
                    else if (debit.prix > 0) msg += " " + Tarif.fcfa(debit.prix) + " débités · solde " + Tarif.fcfa(debit.solde) + ".";
                    if (debit.horsLigne) msg += " Débit transmis au retour du réseau.";
                }
            } catch (Cloud.CloudException e) {
                // Rien n'a été débité : on retire le fichier vide créé par le sélecteur.
                try { DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) {}
                msg = e.getMessage();
                niveau = 2;
            } catch (Exception e) {
                msg = "Fichier non enregistré : " + e.getMessage()
                        + (debit != null && debit.prix > 0 ? " Le montant a été débité : contactez le service client avec l'heure de l'export." : "");
                niveau = 2;
            }
            final String m = msg;
            final int n = niveau;
            runOnUiThread(() -> { notice(m, n); render(); });
        });
    }
}
