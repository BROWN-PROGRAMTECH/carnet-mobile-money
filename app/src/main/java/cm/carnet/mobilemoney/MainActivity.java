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
    /** Recharge : 0 = instructions de paiement, 1 = déclaration (ID retrouvé dans les SMS). */
    private int rechargeEtape;
    private String rechargeOp = "OM";
    private final java.util.Set<String> comptesOuverts = new java.util.HashSet<>();
    private final Runnable hideNotice = () -> noticeText.setVisibility(View.GONE);

    // Champs de saisie conservés d'un affichage à l'autre (sinon un nouveau SMS effacerait la saisie en cours).
    private EditText rechargeMontant, rechargeRef, phoneField, secretField, ownerField, omField, momoField, banquesField;
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
        return r;
    }

    private void loadCaisse(Store store) {
        Caisse.definirPerso(store.categoriesPerso());
        Map<String, Caisse.Classement> cl = store.classements();
        Map<String, String> rg = store.regles();
        List<Caisse.Saisie> s = store.saisies();
        List<Caisse.Jour> j = store.jours();
        classements = cl;
        regles = rg;
        saisies = s;
        jours = j;
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
            case Caisse.PRET_RECU: case Caisse.REMB_PRET: return OM;
            case Caisse.DON: return PURPLE;
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
                sections.add(new String[] {"Entrées", Caisse.VENTE, Caisse.APPORT, Caisse.PRET_RECU});
            } else {
                sections.add(new String[] {"Sorties", Caisse.ACHAT, Caisse.RETRAIT_PERSO, Caisse.REMB_PRET});
                List<String> ch = new ArrayList<>();
                ch.add("Charges");
                for (String c : dispo) if (Caisse.estCharge(c)) ch.add(c);
                sections.add(ch.toArray(new String[0]));
            }
            List<String> mes = new ArrayList<>();
            mes.add("Mes catégories");
            for (String c : dispo) if (Caisse.estCree(c) && !Caisse.estCharge(c)) mes.add(c);
            sections.add(mes.toArray(new String[0]));
            sections.add(new String[] {"Entrée ou sortie", Caisse.DON, Caisse.TRANSFERT, Caisse.AUTRE});
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
            if (l.auto || l.categorie == null || l.sms == null || Caisse.AGENT.equals(l.categorie)) continue;
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
        int ac = bilan == null ? 0 : bilan.aClasser.size();
        classerBadge.setText(ac > 99 ? "99+" : String.valueOf(ac));
        classerBadge.setVisibility(ac > 0 ? View.VISIBLE : View.GONE);

        switch (pageIdx) {
            case PAGE_CLASSER: classerPage(); break;
            case PAGE_EXPORT: exportPage(); break;
            case PAGE_COMPTE: comptePage(); break;
            case PAGE_FINANCES: financesPage(); break;
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

    private View especesCard(Caisse.Especes e) {
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
        card.addView(actions, fill());
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

        Caisse.Bilan b = computeBilan(debut, fin);
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

        // Trésorerie (aujourd'hui).
        long debutJour = Caisse.debutJour(Caisse.jourDe(now));
        Finances.Tresorerie tr = Finances.tresorerie(result, computeBilan(debutJour, now).especes, Store.get(this).avoirs());
        page.addView(tresorerieCard(tr, now), fill());

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

    private View tresorerieCard(Finances.Tresorerie tr, long now) {
        LinearLayout c = column(6);
        c.setBackground(box(ACCENT, 0, 18));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView lab = text("TRÉSORERIE · AUJOURD'HUI", 11, ON_ACCENT_SOFT, true);
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
        // Évolution des soldes lus par SMS sur 30 jours (barres).
        Long[] ev = Finances.evolution(result, 30, now);
        long mn = Long.MAX_VALUE, mx = Long.MIN_VALUE;
        for (Long v : ev) if (v != null) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        if (mx != Long.MIN_VALUE && mx > 0) {
            c.addView(text("Soldes OM, MoMo et banques · 30 derniers jours", 11, ON_ACCENT_SOFT, false));
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
            TextView price = text(d.offert ? "Offert" : d.prix == 0 ? "Gratuit" : Tarif.fcfa(d.prix), 22, d.offert ? GOOD : INK, true);
            priceBox.addView(price);
            if (d.offert) priceBox.addView(text("Votre premier export est offert (au lieu de " + Tarif.fcfa(d.prixNormal) + ").", 13, MUTED, false));
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
        if (rechargeOuverte) rechargeForm(card, w);
        return card;
    }

    private View chipRow(String s, int fg, int bg) {
        LinearLayout row = new LinearLayout(this);
        row.addView(chip(s, fg, bg));
        return row;
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

        if (rechargeEtape == 0) {
            // Étape 1 : payer. Aucun ID à saisir ici.
            card.addView(label("Étape 1 sur 2 · Payer"));
            if (!w.numeroOm.isEmpty() && !w.numeroMomo.isEmpty()) {
                card.addView(segmented(new String[] {"Orange Money", "MTN MoMo"}, om ? 0 : 1, k -> { rechargeOp = k == 0 ? "OM" : "MOMO"; render(); }), fill());
            }
            card.addView(text("Envoyez le montant de votre choix par transfert " + (om ? "Orange Money" : "MTN MoMo") + " au", 14, INK, false));
            TextView num = text(formatNumero(numero), 22, om ? OM : MOMO, true);
            num.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            num.setTextIsSelectable(true);
            card.addView(num);
            if (!w.marchand.isEmpty()) card.addView(text("Le nom affiché par l'opérateur sera : " + w.marchand, 13, MUTED, false));
            card.addView(text("Minimum " + Tarif.fcfa(w.rechargeMin) + ". Exemple : 5 000 FCFA = 10 exports de 200 opérations au plus.", 12, MUTED, false));
            card.addView(button("J'ai envoyé l'argent", true, v -> {
                rechargeEtape = 1;
                reload(true); // lit tout de suite le SMS de confirmation de l'opérateur
            }), fill());
            return;
        }

        // Étape 2 : déclarer, de préférence à partir du SMS de confirmation retrouvé automatiquement.
        card.addView(label("Étape 2 sur 2 · Déclarer le paiement"));
        List<Txn> trouves = transfertsVers(w);
        if (!trouves.isEmpty()) {
            card.addView(text("Paiement trouvé dans vos SMS : vérifiez et déclarez-le.", 13, INK, false));
            for (Txn t : trouves) {
                LinearLayout k = column(4);
                k.setBackground(box(GOOD_BG, 0, 12));
                k.setPadding(dp(12), dp(10), dp(12), dp(10));
                k.addView(text("Transfert de " + SmsParser.fmt(t.montant) + " FCFA vers " + formatNumero(t.numero), 14, INK, true));
                k.addView(text(Txn.opName(t.op) + " · " + when(t.date) + " · ID " + t.txnId, 12, MUTED, false));
                final String op = "OM".equals(t.op) ? "OM" : "MOMO";
                final long fcfa = t.montant / 100;
                final String ref = t.txnId;
                k.addView(button("Déclarer cette recharge", true, v -> declarer(op, ref, fcfa)), fill());
                card.addView(k, fill());
            }
        } else {
            card.addView(text("Le SMS de confirmation n'est pas encore lu. Attendez qu'il arrive (quelques secondes), puis touchez « Rechercher le SMS ».", 13, INK, false));
            card.addView(button("Rechercher le SMS", true, v -> reload(true)), fill());
            card.addView(text("Si vous avez payé depuis un autre téléphone, saisissez l'ID ci-dessous.", 12, MUTED, false));
        }

        TextView manuel = text((rechargeManuel ? "▾" : "▸") + "  Saisir l'ID à la main", 14, ACCENT, true);
        manuel.setPadding(0, dp(8), 0, dp(4));
        manuel.setOnClickListener(v -> { rechargeManuel = !rechargeManuel; render(); });
        card.addView(manuel, fill());
        if (rechargeManuel) {
            if (!w.numeroOm.isEmpty() && !w.numeroMomo.isEmpty()) {
                card.addView(segmented(new String[] {"Orange Money", "MTN MoMo"}, om ? 0 : 1, k -> { rechargeOp = k == 0 ? "OM" : "MOMO"; render(); }), fill());
            }
            if (rechargeMontant == null) {
                rechargeMontant = amountField("Montant envoyé, en FCFA");
                rechargeMontant.setInputType(InputType.TYPE_CLASS_NUMBER);
            }
            card.addView(detach(rechargeMontant), fill());
            if (rechargeRef == null) {
                rechargeRef = new EditText(this);
                rechargeRef.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                rechargeRef.setSingleLine(true);
            }
            rechargeRef.setHint(om ? "ID reçu par SMS, ex. PP251114.1530.A12345" : "ID reçu par SMS, ex. 8472619305");
            card.addView(detach(rechargeRef), fill());
            card.addView(button("Déclarer la recharge", false, v -> declarerRecharge()), fill());
        }
        card.addView(small("◂ Revoir les instructions de paiement", MUTED, v -> { rechargeEtape = 0; render(); }), fill());
        card.addView(text("Votre solde est crédité dès que le paiement est vérifié.", 12, MUTED, false));
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
        if (Cloud.configured() && Cloud.loggedIn(this)) page.addView(caissesCard(), fill());
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
        row.addView(text(when(t.date) + (t.txnId != null ? " · ID " + t.txnId : "") + (t.frais > 0 ? " · frais " + SmsParser.fmt(t.frais) : "")
                + " · solde " + SmsParser.fmt(t.solde != null ? t.solde : t.apres), 12, MUTED, false));

        LinearLayout cat = new LinearLayout(this);
        cat.setGravity(Gravity.CENTER_VERTICAL);
        cat.addView(catChip(l.categorie, l.auto));
        if (!l.motif.isEmpty()) {
            TextView m = text("  " + l.motif, 13, INK, false);
            cat.addView(m, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        row.addView(cat, fill());
        for (String f : t.flags) if (f.startsWith("!")) row.addView(text(f.substring(1), 12, BAD, true));

        if (quick) {
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
        row.addView(text(when(s.date), 12, MUTED, false));
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

        final String[] choix = {l.categorie != null && !Caisse.AGENT.equals(l.categorie) ? l.categorie : null};
        form.addView(text(t.sens > 0 ? "Catégorie d'entrée" : "Catégorie de sortie", 13, MUTED, false));
        LinearLayout grille = column(6);
        TextView aide = text("", 12, MUTED, false);
        categorieGrid(grille, aide, t.sens, choix);
        form.addView(grille, fill());
        form.addView(aide);

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
        new AlertDialog.Builder(this)
                .setTitle("Classer l'opération")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
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
        form.addView(catTitre);
        form.addView(grille, fill());
        form.addView(aide);
        Runnable paint = () -> {
            select(in, sens[0] > 0);
            select(out, sens[0] < 0);
            catTitre.setText(sens[0] > 0 ? "Catégorie d'entrée" : "Catégorie de sortie");
            categorieGrid(grille, aide, sens[0], cat);
        };
        // En changeant de sens, une catégorie qui ne va pas avec le nouveau sens est remplacée par la plus courante.
        in.setOnClickListener(v -> { sens[0] = 1; if (!Caisse.compatible(cat[0], 1)) cat[0] = Caisse.VENTE; paint.run(); });
        out.setOnClickListener(v -> { sens[0] = -1; if (!Caisse.compatible(cat[0], -1)) cat[0] = Caisse.ACHAT; paint.run(); });
        paint.run();

        EditText libelle = new EditText(this);
        libelle.setInputType(InputType.TYPE_CLASS_TEXT);
        libelle.setHint("Motif (facultatif)");
        form.addView(libelle, fill());

        new AlertDialog.Builder(this)
                .setTitle("Mouvement d'espèces")
                .setView(form)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    Long cents = SmsParser.money(montant.getText().toString());
                    if (cents == null || cents <= 0) {
                        notice("Montant invalide : mouvement non enregistré.", 2);
                        return;
                    }
                    Caisse.Saisie s = new Caisse.Saisie();
                    s.date = System.currentTimeMillis();
                    s.mode = Caisse.ESPECES;
                    s.sens = sens[0];
                    s.montant = cents;
                    s.categorie = cat[0];
                    s.libelle = libelle.getText().toString().trim();
                    io.execute(() -> {
                        Store.get(this).addSaisie(s);
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
                    SyncJob.soon(this);
                    runOnUiThread(() -> reload(false));
                }))
                .setNegativeButton("Garder", null)
                .show();
    }

    // ---------- Fond de caisse et clôture ----------

    private void openFond() {
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
        String digits = rechargeMontant.getText().toString().replaceAll("\\D", "");
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
                String deja = Store.pref(this, "recharges_declarees", "");
                Store.savePref(this, "recharges_declarees", (deja.isEmpty() ? "" : deja + ",") + ref.toUpperCase(Locale.ROOT));
            } catch (Cloud.CloudException e) {
                msg = e.getMessage();
                niveau = 2;
                if (msg != null && msg.contains("déjà été déclaré")) {
                    String deja = Store.pref(this, "recharges_declarees", "");
                    Store.savePref(this, "recharges_declarees", (deja.isEmpty() ? "" : deja + ",") + ref.toUpperCase(Locale.ROOT));
                }
            }
            final String m = msg;
            final int n = niveau;
            runOnUiThread(() -> {
                if (n == 1) {
                    if (rechargeRef != null) rechargeRef.setText("");
                    if (rechargeMontant != null) rechargeMontant.setText("");
                    rechargeOuverte = false;
                    rechargeEtape = 0;
                    rechargeManuel = false;
                }
                notice(m, n);
                render();
            });
        });
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
        String detail = d.offert ? "Cet export vous est offert."
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
        final Caisse.Especes especesJour = toutes ? null : computeBilan(Caisse.debutJour(Caisse.jourDe(maintenant)), maintenant).especes;
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
                                Finances.tresorerie(r, especesJour, Store.get(this).avoirs()), Store.get(this).budgets());
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(xlsx);
                }
                msg = "Rapport Excel enregistré · " + label + ".";
                if (debit != null) {
                    if (debit.offert) msg += " Export offert.";
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
