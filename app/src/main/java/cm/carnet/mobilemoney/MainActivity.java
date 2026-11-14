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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final int REQ_PERM = 1, REQ_XLSX = 2, REQ_CSV = 3, REQ_SMS = 4;
    private static final int MAX_ROWS = 400;
    private static final String[] PERIODS = {"Aujourd'hui", "7 jours", "Ce mois", "Tout"};
    private static final int CUSTOM = 4;
    private static final int TAB_JOURNAL = 0, TAB_CLASSER = 1, TAB_ESPECES = 2, TAB_CHECK = 3;
    private static final String[] COURT = {"Vente", "Paiement", "Don", "Perso", "Autre"};

    private static final int BG = Color.parseColor("#F3F5F2"), SURFACE = Color.WHITE, INK = Color.parseColor("#17201D"),
            MUTED = Color.parseColor("#5B6662"), LINE = Color.parseColor("#D8DED9"), ACCENT = Color.parseColor("#0E6A5B"),
            OM = Color.parseColor("#D9650F"), MOMO = Color.parseColor("#B88A00"), CASH = Color.parseColor("#3B5BA5"),
            GOOD = Color.parseColor("#1E7546"), WARN = Color.parseColor("#9A5600"), BAD = Color.parseColor("#B0261D"),
            GOOD_BG = Color.parseColor("#E3F1E8"), BAD_BG = Color.parseColor("#F8E1DF"), WARN_BG = Color.parseColor("#F8EBD8"),
            PURPLE = Color.parseColor("#6B4FA0");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout permBox, cards, list;
    private TextView status, cloudLine;
    private final Button[] tabs = new Button[4];
    private final Button[] periodButtons = new Button[PERIODS.length];
    private Button customButton;
    private int period = 0, tab = TAB_JOURNAL;
    private long customFrom, customTo;
    private boolean loading;

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
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        permBox.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) status.setText("En attente de l'autorisation de lire les SMS.");
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
        if (result == null) status.setText("Ouverture de votre caisse…");
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
                runOnUiThread(() -> { status.setText(early); render(); });

                // 2. Lire seulement les nouveaux SMS.
                int added = 0;
                if (importInbox && hasSmsPermission()) {
                    added = importInbox(cfg);
                    if (added > 0) r = analyseStored(store, Store.config(this));
                }
                msg = r.tx.size() + " opérations" + (added > 0 ? " · " + added + " nouveau(x) SMS" : " · à jour");
                if (r.scanned == 0 && !hasSmsPermission()) msg = "Autorisez la lecture des SMS pour commencer.";
            } catch (Exception e) {
                msg = "Erreur de lecture : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                loading = false;
                status.setText(m);
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
        String key = cfg.om + "|" + cfg.momo + "|" + cfg.auto + "|" + cfg.ownerOm + "|" + store.count();
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
            for (String x : cfg.om) keys.add(SmsParser.senderKey(x));
            for (String x : cfg.momo) keys.add(SmsParser.senderKey(x));
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
        return Caisse.OM.equals(mode) ? OM : Caisse.MOMO.equals(mode) ? MOMO : CASH;
    }

    private static String opShort(String mode) {
        return Caisse.OM.equals(mode) ? "OM" : Caisse.MOMO.equals(mode) ? "MoMo" : "Espèces";
    }

    private static int catColor(String c) {
        if (c == null) return BAD;
        switch (c) {
            case Caisse.VENTE: return GOOD;
            case Caisse.PAIEMENT: return INK;
            case Caisse.DON: return PURPLE;
            case Caisse.PERSONNEL: return WARN;
            case Caisse.AGENT: return CASH;
            default: return MUTED;
        }
    }

    private TextView catChip(String c, boolean auto) {
        int fg = catColor(c);
        TextView t = chip(Caisse.categorieNom(c) + (auto ? " · auto" : ""), fg, c == null ? BAD_BG : BG);
        return t;
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = column(12);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        root.addView(text("Carnet Mobile Money", 22, INK, true));
        root.addView(text("Votre caisse se remplit toute seule avec les SMS Orange Money et MTN MoMo. Vous indiquez seulement s'il s'agit d'une vente, d'un paiement, d'un don ou d'un mouvement personnel.", 14, MUTED, false));

        permBox = column(10);
        permBox.setBackground(box(WARN_BG, 0, 14));
        permBox.setPadding(dp(16), dp(16), dp(16), dp(16));
        permBox.addView(text("Autorisation nécessaire", 16, INK, true));
        permBox.addView(text("L'application lit uniquement les SMS des expéditeurs Orange Money et MTN MoMo. Les autres SMS ne sont ni lus ni enregistrés. Sans compte, rien n'est envoyé sur internet.", 14, INK, false));
        permBox.addView(button("Autoriser la lecture des SMS", true, v -> askPermissions()), fill());
        permBox.addView(text("Si l'autorisation est grisée ou refusée : ouvrez les paramètres de l'application, touchez le menu ⋮ en haut à droite, choisissez « Autoriser les paramètres restreints », puis activez SMS dans Autorisations.", 13, MUTED, false));
        permBox.addView(button("Ouvrir les paramètres de l'application", false, v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))), fill());
        root.addView(permBox, fill());

        LinearLayout periods = new LinearLayout(this);
        for (int i = 0; i < PERIODS.length; i++) {
            final int p = i;
            periodButtons[i] = button(PERIODS[i], false, v -> setPeriod(p));
            periodButtons[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            periods.addView(periodButtons[i], weight(i == PERIODS.length - 1));
        }
        root.addView(periods, fill());
        customButton = button("Période précise…", false, v -> pickRange());
        customButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(customButton, fill());

        status = text("", 13, MUTED, false);
        root.addView(status);
        cloudLine = text("", 13, INK, false);
        cloudLine.setPadding(dp(12), dp(10), dp(12), dp(10));
        cloudLine.setOnClickListener(v -> openCompte());
        root.addView(cloudLine, fill());

        cards = column(12);
        root.addView(cards, fill());

        LinearLayout day = new LinearLayout(this);
        day.addView(button("Fond de caisse", false, v -> openFond()), weight(false));
        day.addView(button("Espèces + / −", false, v -> openEspeces()), weight(false));
        day.addView(button("Clôturer", false, v -> openCloture()), weight(true));
        root.addView(day, fill());

        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("Actualiser", false, v -> reload(true)), weight(false));
        actions.addView(button("Exporter Excel", true, v -> startExport(REQ_XLSX)), weight(false));
        actions.addView(button("Plus…", false, v -> openMore()), weight(true));
        root.addView(actions, fill());

        String[] names = {"Journal", "À classer", "Espèces", "À vérifier"};
        LinearLayout row1 = new LinearLayout(this), row2 = new LinearLayout(this);
        for (int i = 0; i < tabs.length; i++) {
            final int t = i;
            tabs[i] = button(names[i], false, v -> { tab = t; render(); });
            tabs[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            (i < 2 ? row1 : row2).addView(tabs[i], weight(i % 2 == 1));
        }
        root.addView(row1, fill());
        root.addView(row2, fill());

        list = column(0);
        list.setBackground(box(SURFACE, LINE, 14));
        list.setPadding(dp(14), dp(4), dp(14), dp(4));
        root.addView(list, fill());

        root.addView(text("Les montants viennent des SMS de l'opérateur et ne se modifient pas. Touchez une opération pour changer sa catégorie ou son motif. Version 0.4.1.", 12, MUTED, false));
    }

    // ---------- Affichage ----------

    private void render() {
        renderCloud();
        for (int i = 0; i < periodButtons.length; i++) select(periodButtons[i], i == period);
        select(customButton, period == CUSTOM);
        customButton.setText(period == CUSTOM ? "Période : " + periodLabel().toLowerCase(Locale.FRANCE) + " (modifier)" : "Période précise…");
        cards.removeAllViews();
        list.removeAllViews();
        if (result == null) return;
        long[] range = range();
        bilan = computeBilan(range[0], range[1]);

        cards.addView(resultatCard(bilan), fill());
        for (Caisse.Compte c : bilan.comptes) cards.addView(compteCard(c), fill());
        cards.addView(especesCard(bilan.especes), fill());

        List<Txn> unknown = new ArrayList<>();
        for (Txn u : result.unknown) if (u.smsDate >= range[0] && u.smsDate <= range[1]) unknown.add(u);

        tabs[TAB_JOURNAL].setText("Journal (" + bilan.lignes.size() + ")");
        tabs[TAB_CLASSER].setText("À classer (" + bilan.aClasser.size() + ")");
        tabs[TAB_ESPECES].setText("Espèces (" + bilan.mouvementsEspeces.size() + ")");
        tabs[TAB_CHECK].setText("À vérifier (" + unknown.size() + ")");
        for (int i = 0; i < tabs.length; i++) select(tabs[i], i == tab);
        if (tab != TAB_CLASSER && !bilan.aClasser.isEmpty()) tabs[TAB_CLASSER].setTextColor(BAD);

        switch (tab) {
            case TAB_JOURNAL: {
                if (bilan.lignes.isEmpty()) list.addView(empty("Aucune opération sur la période."));
                int n = 0;
                for (Caisse.Ligne l : bilan.lignes) {
                    if (n++ >= MAX_ROWS) { list.addView(empty("Seules les " + MAX_ROWS + " dernières opérations sont affichées. L'export Excel contient tout.")); break; }
                    list.addView(ligneRow(l, false), fill());
                }
                break;
            }
            case TAB_CLASSER: {
                if (bilan.aClasser.isEmpty()) list.addView(empty("Toutes les opérations de la période sont classées."));
                else list.addView(text("Un toucher suffit : choisissez la catégorie sous chaque opération.", 12, MUTED, false));
                int n = 0;
                for (Caisse.Ligne l : bilan.aClasser) {
                    if (n++ >= MAX_ROWS) break;
                    list.addView(ligneRow(l, true), fill());
                }
                break;
            }
            case TAB_ESPECES:
                if (bilan.mouvementsEspeces.isEmpty()) list.addView(empty("Aucun mouvement d'espèces. Touchez « Espèces + / − » pour en enregistrer un."));
                for (Caisse.Saisie s : bilan.mouvementsEspeces) list.addView(especeRow(s), fill());
                break;
            default:
                if (unknown.isEmpty()) list.addView(empty("Rien à vérifier sur la période."));
                for (Txn u : unknown) list.addView(unknownRow(u), fill());
        }
    }

    private LinearLayout cardShell(String title, String subtitle, int color) {
        LinearLayout card = column(8);
        card.setBackground(box(SURFACE, LINE, 14));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(box(color, 0, 6));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.setMarginEnd(dp(8));
        head.addView(dot, dl);
        head.addView(text(title, 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(subtitle, 12, MUTED, false));
        card.addView(head, fill());
        return card;
    }

    private View resultatCard(Caisse.Bilan b) {
        LinearLayout card = cardShell("Caisse de la période", periodLabel(), ACCENT);
        card.addView(text("RÉSULTAT (VENTES − PAIEMENTS − FRAIS)", 11, MUTED, true));
        TextView res = big((b.resultat >= 0 ? "" : "−") + SmsParser.fmt(Math.abs(b.resultat)) + " FCFA");
        res.setTextColor(b.resultat >= 0 ? GOOD : BAD);
        card.addView(res);
        Caisse.Total v = b.total(Caisse.VENTE), p = b.total(Caisse.PAIEMENT), d = b.total(Caisse.DON), pe = b.total(Caisse.PERSONNEL), a = b.total(Caisse.AUTRE);
        card.addView(kv("Ventes", "+" + SmsParser.fmt(v.entrees - v.sorties), GOOD, false));
        card.addView(kv("Paiements", "−" + SmsParser.fmt(p.sorties - p.entrees)));
        card.addView(kv("Frais opérateur", "−" + SmsParser.fmt(b.frais)));
        card.addView(divider());
        card.addView(kv("Dons reçus / donnés", "+" + SmsParser.fmt(d.entrees) + " / −" + SmsParser.fmt(d.sorties), PURPLE, false));
        card.addView(kv("Personnel entrées / sorties", "+" + SmsParser.fmt(pe.entrees) + " / −" + SmsParser.fmt(pe.sorties), WARN, false));
        if (a.count > 0) card.addView(kv("Autre entrées / sorties", "+" + SmsParser.fmt(a.entrees) + " / −" + SmsParser.fmt(a.sorties)));
        if (!b.aClasser.isEmpty()) {
            Caisse.Total x = b.total(null);
            LinearLayout warn = new LinearLayout(this);
            warn.setGravity(Gravity.CENTER_VERTICAL);
            warn.addView(chip(b.aClasser.size() + " à classer : +" + SmsParser.fmt(x.entrees) + " / −" + SmsParser.fmt(x.sorties), BAD, BAD_BG));
            card.addView(warn);
            card.addView(text("Le résultat est provisoire tant que des opérations restent à classer.", 12, BAD, false));
        } else if (!b.lignes.isEmpty()) {
            LinearLayout ok = new LinearLayout(this);
            ok.addView(chip("Tout est classé", GOOD, GOOD_BG));
            card.addView(ok);
        }
        return card;
    }

    private View compteCard(Caisse.Compte c) {
        LinearLayout card = cardShell(Caisse.modeNom(c.op), c.agent ? "compte agent" : (c.sms != null ? c.sms.count + " opér." : ""), opColor(c.op));
        card.addView(text("SOLDE RÉEL (DERNIER SMS)", 11, MUTED, true));
        card.addView(big(SmsParser.fmt(c.reel) + " FCFA"));
        card.addView(kv("Solde d'ouverture", SmsParser.fmt(c.ouverture)));
        card.addView(kv("Entrées", "+" + SmsParser.fmt(c.entrees)));
        card.addView(kv("Sorties", "−" + SmsParser.fmt(c.sorties)));
        card.addView(kv("Frais", "−" + SmsParser.fmt(c.frais)));
        card.addView(kv("Solde théorique", SmsParser.fmt(c.theo)));
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(ecartChip(c.ecart));
        card.addView(foot);
        if (c.alertesSms > 0) card.addView(text(c.alertesSms + " SMS manquant(s) : voir les alertes dans le journal.", 12, BAD, true));
        if (c.aClasser > 0) card.addView(text(c.aClasser + " opération(s) à classer.", 12, BAD, false));
        return card;
    }

    private View especesCard(Caisse.Especes e) {
        LinearLayout card = cardShell("Espèces", e.compte != null ? "clôturée" : "en cours", CASH);
        card.addView(text(e.compte != null ? "MONTANT COMPTÉ" : "MONTANT THÉORIQUE", 11, MUTED, true));
        card.addView(big(SmsParser.fmt(e.compte != null ? e.compte : e.theo) + " FCFA"));
        card.addView(kv("Ouverture", SmsParser.fmt(e.ouverture)));
        if (!e.origineOuverture.isEmpty()) card.addView(text(e.origineOuverture, 12, MUTED, false));
        card.addView(kv("Entrées saisies", "+" + SmsParser.fmt(e.entrees)));
        card.addView(kv("Sorties saisies", "−" + SmsParser.fmt(e.sorties)));
        if (agent(Caisse.OM) || agent(Caisse.MOMO)) {
            card.addView(kv("Dépôts clients (agent)", "+" + SmsParser.fmt(e.depotsClients)));
            card.addView(kv("Retraits clients (agent)", "−" + SmsParser.fmt(e.retraitsClients)));
        }
        card.addView(kv("Théorique", SmsParser.fmt(e.theo)));
        card.addView(kv("Compté", SmsParser.fmt(e.compte)));
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        if (e.ouverture == null) foot.addView(chip("Fond de caisse non saisi", WARN, WARN_BG));
        else if (e.compte == null) foot.addView(chip("Pas encore clôturée", WARN, WARN_BG));
        else foot.addView(ecartChip(e.ecart));
        card.addView(foot);
        return card;
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
            for (int i = 0; i < Caisse.CATEGORIES.length; i++) {
                final String c = Caisse.CATEGORIES[i];
                q.addView(small(COURT[i], catColor(c), v -> classer(t, c, l.motif, false)), weight(i == Caisse.CATEGORIES.length - 1));
            }
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
        chipBox.addView(s.annule ? chip("Annulée", MUTED, BG) : catChip(s.categorie, false));
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
        io.execute(() -> {
            Store store = Store.get(this);
            store.classer(key, categorie, motif);
            if (toujours && numero != null) store.regle(numero, categorie);
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
        form.addView(text("Catégorie", 13, MUTED, false));
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        Button[] cb = new Button[Caisse.CATEGORIES.length];
        for (int i = 0; i < cb.length; i++) {
            cb[i] = button(COURT[i], false, null);
            cb[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            (i < 3 ? r1 : r2).addView(cb[i], weight(i == 2 || i == cb.length - 1));
        }
        Runnable paint = () -> { for (int i = 0; i < cb.length; i++) select(cb[i], Caisse.CATEGORIES[i].equals(choix[0])); };
        for (int i = 0; i < cb.length; i++) {
            final String c = Caisse.CATEGORIES[i];
            cb[i].setOnClickListener(v -> { choix[0] = c; paint.run(); });
        }
        paint.run();
        form.addView(r1, fill());
        form.addView(r2, fill());

        form.addView(text("Motif", 13, MUTED, false));
        EditText motif = new EditText(this);
        motif.setInputType(InputType.TYPE_CLASS_TEXT);
        motif.setHint("ex. Vente de 2 sacs de riz");
        motif.setText(l.motif);
        form.addView(motif, fill());

        CheckBox toujours = new CheckBox(this);
        if (t.numero != null) {
            String who = t.nom != null ? t.nom : t.numero;
            toujours.setText("Toujours classer " + who + " dans cette catégorie");
            toujours.setChecked(regles.containsKey(t.numero));
            form.addView(toujours, fill());
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Classer l'opération")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    boolean always = t.numero != null && toujours.isChecked();
                    if (!always && t.numero != null && regles.containsKey(t.numero)) {
                        final String numero = t.numero;
                        io.execute(() -> { Store.get(this).regle(numero, null); SyncJob.soon(this); });
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

        LinearLayout sensRow = new LinearLayout(this);
        Button in = button("Entrée", false, null), out = button("Sortie", false, null);
        sensRow.addView(in, weight(false));
        sensRow.addView(out, weight(true));
        form.addView(sensRow, fill());

        form.addView(text("Montant (FCFA)", 13, MUTED, false));
        EditText montant = amountField("ex. 15000");
        form.addView(montant, fill());

        form.addView(text("Catégorie", 13, MUTED, false));
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        Button[] cb = new Button[Caisse.CATEGORIES.length];
        for (int i = 0; i < cb.length; i++) {
            cb[i] = button(COURT[i], false, null);
            cb[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            (i < 3 ? r1 : r2).addView(cb[i], weight(i == 2 || i == cb.length - 1));
        }
        Runnable paint = () -> {
            select(in, sens[0] > 0);
            select(out, sens[0] < 0);
            for (int i = 0; i < cb.length; i++) select(cb[i], Caisse.CATEGORIES[i].equals(cat[0]));
        };
        in.setOnClickListener(v -> { sens[0] = 1; if (Caisse.PAIEMENT.equals(cat[0])) cat[0] = Caisse.VENTE; paint.run(); });
        out.setOnClickListener(v -> { sens[0] = -1; if (Caisse.VENTE.equals(cat[0])) cat[0] = Caisse.PAIEMENT; paint.run(); });
        for (int i = 0; i < cb.length; i++) {
            final String c = Caisse.CATEGORIES[i];
            cb[i].setOnClickListener(v -> { cat[0] = c; paint.run(); });
        }
        paint.run();
        form.addView(r1, fill());
        form.addView(r2, fill());

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
                        Toast.makeText(this, "Montant invalide : mouvement non enregistré.", Toast.LENGTH_LONG).show();
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
                            Toast.makeText(this, (s.sens > 0 ? "Entrée" : "Sortie") + " d'espèces de " + SmsParser.fmt(cents) + " FCFA enregistrée.", Toast.LENGTH_SHORT).show();
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
                    if (cents == null) { Toast.makeText(this, "Montant invalide.", Toast.LENGTH_LONG).show(); return; }
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
                    if (cents == null) { Toast.makeText(this, "Montant compté invalide : clôture non enregistrée.", Toast.LENGTH_LONG).show(); return; }
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
                                    .setPositiveButton("Exporter", (d2, w2) -> startExport(REQ_XLSX))
                                    .setNegativeButton("Plus tard", null)
                                    .show();
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- Compte et sauvegarde ----------

    private void renderCloud() {
        if (!Cloud.configured()) {
            cloudLine.setText("☁  Sauvegarde en ligne : non configurée dans cette version.");
            cloudLine.setTextColor(MUTED);
            cloudLine.setBackground(box(BG, LINE, 10));
            return;
        }
        if (!Cloud.loggedIn(this)) {
            cloudLine.setText("☁  Vos données ne sont que sur ce téléphone. Touchez ici pour créer un compte et les sauvegarder.");
            cloudLine.setTextColor(WARN);
            cloudLine.setBackground(box(WARN_BG, 0, 10));
            return;
        }
        String err = Cloud.lastError(this);
        long last = Cloud.lastSync(this);
        String phone = Cloud.phone(this);
        String who = phone.length() == 9 ? phone.substring(0, 3) + " " + phone.substring(3, 5) + " " + phone.substring(5, 7) + " " + phone.substring(7) : phone;
        if (!err.isEmpty()) {
            cloudLine.setText("☁  " + who + " · dernière sauvegarde : " + (last > 0 ? when(last) : "jamais") + ". Problème : " + err);
            cloudLine.setTextColor(BAD);
            cloudLine.setBackground(box(BAD_BG, 0, 10));
        } else {
            cloudLine.setText("☁  Sauvegardé · " + who + (last > 0 ? " · " + when(last) : ""));
            cloudLine.setTextColor(GOOD);
            cloudLine.setBackground(box(GOOD_BG, 0, 10));
        }
    }

    /** Sauvegarde à l'ouverture si la dernière date de plus de 15 minutes. */
    private void autoSync() {
        if (!Cloud.configured() || !Cloud.loggedIn(this)) return;
        SyncJob.schedule(this);
        if (System.currentTimeMillis() - Cloud.lastSync(this) < 15 * 60000L) return;
        io.execute(() -> {
            try { Cloud.sync(this); } catch (Exception ignored) {}
            runOnUiThread(() -> reload(false));
        });
    }

    private void syncNow() {
        cloudLine.setText("☁  Sauvegarde en cours…");
        io.execute(() -> {
            String msg;
            try {
                msg = "Sauvegarde terminée : " + Cloud.sync(this);
            } catch (Cloud.CloudException e) {
                msg = e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                reload(false);
            });
        });
    }

    private void openCompte() {
        if (!Cloud.configured()) {
            new AlertDialog.Builder(this)
                    .setTitle("Sauvegarde en ligne")
                    .setMessage("Cette version de l'application n'est pas encore reliée à un serveur. Renseignez l'adresse et la clé de votre projet Supabase dans le fichier CloudConfig.java, puis recompilez (voir le guide LISEZMOI).")
                    .setPositiveButton("Compris", null)
                    .show();
            return;
        }
        if (Cloud.loggedIn(this)) {
            long last = Cloud.lastSync(this);
            String err = Cloud.lastError(this);
            new AlertDialog.Builder(this)
                    .setTitle("Compte et sauvegarde")
                    .setMessage("Connecté avec le numéro " + Cloud.phone(this) + ".\nDernière sauvegarde : " + (last > 0 ? when(last) : "jamais") + "."
                            + (err.isEmpty() ? "" : "\nDernier problème : " + err)
                            + "\n\nLa sauvegarde se fait toute seule dès qu'il y a du réseau. Sur un nouveau téléphone, connectez-vous avec le même numéro et le même code secret pour tout retrouver.")
                    .setPositiveButton("Sauvegarder maintenant", (d, w) -> syncNow())
                    .setNeutralButton("Se déconnecter", (d, w) -> confirmSignOut())
                    .setNegativeButton("Fermer", null)
                    .show();
            return;
        }
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(text("Un compte sauvegarde vos opérations, catégories, motifs et espèces. Si vous perdez ou changez de téléphone, connectez-vous pour tout retrouver.", 13, MUTED, false));
        form.addView(text("Numéro de téléphone", 13, MUTED, false));
        EditText phone = new EditText(this);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        phone.setHint("6XXXXXXXX");
        String guess = Store.pref(this, "ownerOmAuto", "");
        if (!guess.isEmpty()) phone.setText(guess);
        form.addView(phone, fill());
        form.addView(text("Code secret (6 caractères minimum)", 13, MUTED, false));
        EditText secret = new EditText(this);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(secret, fill());
        form.addView(text("N'utilisez jamais votre code Orange Money ou MoMo. Choisissez un code propre à cette application et notez-le : sans lui, la sauvegarde ne peut pas être récupérée.", 13, BAD, true));
        new AlertDialog.Builder(this)
                .setTitle("Compte et sauvegarde")
                .setView(form)
                .setPositiveButton("Créer mon compte", (d, w) -> doAuth(true, phone.getText().toString(), secret.getText().toString()))
                .setNeutralButton("Me connecter", (d, w) -> doAuth(false, phone.getText().toString(), secret.getText().toString()))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void doAuth(boolean create, String phoneText, String secret) {
        String p9 = Cloud.phone9(phoneText);
        if (p9 == null) { Toast.makeText(this, "Numéro invalide : saisissez un numéro camerounais à 9 chiffres (6XXXXXXXX).", Toast.LENGTH_LONG).show(); return; }
        if (secret.length() < 6) { Toast.makeText(this, "Le code secret doit faire au moins 6 caractères.", Toast.LENGTH_LONG).show(); return; }
        cloudLine.setText(create ? "☁  Création du compte…" : "☁  Connexion et récupération de vos données…");
        io.execute(() -> {
            String title, msg;
            try {
                if (create) Cloud.signUp(this, p9, secret); else Cloud.signIn(this, p9, secret);
                String r = Cloud.sync(this);
                SyncJob.schedule(this);
                title = create ? "Compte créé" : "Connecté";
                msg = (create ? "Vos données sont maintenant sauvegardées. " : "Vos données ont été récupérées et fusionnées avec celles de ce téléphone. ") + r;
            } catch (Cloud.CloudException e) {
                title = "Impossible pour l'instant";
                msg = e.getMessage();
            }
            final String t = title, m = msg;
            runOnUiThread(() -> {
                reload(false);
                new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show();
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
                    runOnUiThread(this::render);
                }))
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

    // ---------- Plus, réglages ----------

    private void openMore() {
        String[] items = {"Compte et sauvegarde", "Exporter en CSV", "Exporter les SMS anonymisés (pour améliorer la lecture)", "Réglages"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) openCompte();
                    else if (which == 1) startExport(REQ_CSV);
                    else if (which == 2) startExport(REQ_SMS);
                    else openSettings();
                })
                .show();
    }

    private void openSettings() {
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        form.addView(text("Type de compte", 14, INK, true));
        CheckBox agentOm = new CheckBox(this);
        agentOm.setText("Orange Money : compte agent (dépôts et retraits clients)");
        agentOm.setChecked(agent(Caisse.OM));
        form.addView(agentOm, fill());
        CheckBox agentMomo = new CheckBox(this);
        agentMomo.setText("MTN MoMo : compte agent (dépôts et retraits clients)");
        agentMomo.setChecked(agent(Caisse.MOMO));
        form.addView(agentMomo, fill());
        form.addView(text("En mode agent, les dépôts et retraits clients sont classés automatiquement et font bouger la caisse espèces.", 12, MUTED, false));

        String detected = Store.pref(this, "ownerOmAuto", "");
        form.addView(text("Mon numéro Orange Money (sert à savoir si un transfert est reçu ou envoyé)" + (detected.isEmpty() ? "" : ". Détecté : " + detected), 13, MUTED, false));
        EditText owner = new EditText(this);
        owner.setInputType(InputType.TYPE_CLASS_PHONE);
        owner.setHint(detected.isEmpty() ? "6XXXXXXXX" : detected);
        owner.setText(Store.pref(this, "ownerOm", ""));
        form.addView(owner, fill());

        form.addView(text("Expéditeurs Orange Money (séparés par des virgules)", 13, MUTED, false));
        EditText om = new EditText(this);
        om.setInputType(InputType.TYPE_CLASS_TEXT);
        om.setText(Store.pref(this, "om", SmsParser.DEFAULT_OM));
        form.addView(om, fill());
        form.addView(text("Expéditeurs MTN MoMo (séparés par des virgules)", 13, MUTED, false));
        EditText momo = new EditText(this);
        momo.setInputType(InputType.TYPE_CLASS_TEXT);
        momo.setText(Store.pref(this, "momo", SmsParser.DEFAULT_MOMO));
        form.addView(momo, fill());
        CheckBox auto = new CheckBox(this);
        auto.setText("Lire aussi les SMS d'autres expéditeurs qui parlent d'Orange Money ou de MoMo");
        auto.setChecked(Store.prefBool(this, "auto", false));
        form.addView(auto, fill());
        form.addView(text(regles.size() + " contact(s) classé(s) automatiquement. Pour retirer une règle, ouvrez une opération du contact et décochez « Toujours classer ».", 12, MUTED, false));

        ScrollView sv = new ScrollView(this);
        sv.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Réglages")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    Store.saveConfig(this, om.getText().toString(), momo.getText().toString(), auto.isChecked(), owner.getText().toString().trim());
                    Store.saveFlag(this, "agent" + Caisse.OM, agentOm.isChecked());
                    Store.saveFlag(this, "agent" + Caisse.MOMO, agentMomo.isChecked());
                    resetInbox();
                    reload(true);
                })
                .setNeutralButton("Relire / effacer…", (d, w) -> confirmClear())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmClear() {
        String[] items = {"Relire toute la messagerie (si des SMS anciens manquent)", "Effacer la copie des SMS et tout relire"};
        new AlertDialog.Builder(this)
                .setTitle("Lecture des SMS")
                .setItems(items, (d, which) -> {
                    if (which == 0) { resetInbox(); reload(true); }
                    else confirmErase();
                })
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

    // ---------- Exports ----------

    private void startExport(int kind) {
        if (result == null) {
            Toast.makeText(this, "Rien à exporter pour l'instant.", Toast.LENGTH_SHORT).show();
            return;
        }
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (kind == REQ_XLSX) {
            i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            i.putExtra(Intent.EXTRA_TITLE, "caisse-mobile-money-" + day + ".xlsx");
        } else if (kind == REQ_CSV) {
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_TITLE, "journal-mobile-money-" + day + ".csv");
        } else {
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "sms-mobile-money-anonymises-" + day + ".txt");
        }
        startActivityForResult(i, kind);
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (code != REQ_XLSX && code != REQ_CSV && code != REQ_SMS) return;
        final Uri uri = data.getData();
        final Analyzer.Result r = result;
        final long[] range = range();
        final String label = periodLabel();
        final Caisse.Bilan b = computeBilan(range[0], range[1]);
        io.execute(() -> {
            String msg;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (code == REQ_XLSX) {
                    os.write(Exporter.xlsx(r, b, range[0], range[1], label, System.currentTimeMillis()));
                    msg = "Classeur Excel enregistré : " + label + ".";
                } else if (code == REQ_CSV) {
                    os.write(Exporter.csv(r, range[0], range[1]).getBytes(StandardCharsets.UTF_8));
                    msg = "Journal CSV enregistré : " + label + ".";
                } else {
                    os.write(Exporter.anonymizedSms(Store.get(this).all(), Store.config(this)).getBytes(StandardCharsets.UTF_8));
                    msg = "SMS anonymisés enregistrés. Vérifiez le fichier avant de le partager.";
                }
            } catch (Exception e) {
                msg = "Export impossible : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }
}
