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
    private static final int PAGE_CAISSE = 0, PAGE_CLASSER = 1, PAGE_EXPORT = 2, PAGE_COMPTE = 3;
    private static final int LISTE_JOURNAL = 0, LISTE_ESPECES = 1, LISTE_CHECK = 2;
    private static final String[] COURT = {"Vente", "Paiement", "Don", "Perso", "Autre"};

    private static final int BG = Color.parseColor("#F3F5F2"), SURFACE = Color.WHITE, INK = Color.parseColor("#17201D"),
            MUTED = Color.parseColor("#5B6662"), LINE = Color.parseColor("#D8DED9"), ACCENT = Color.parseColor("#0E6A5B"),
            OM = Color.parseColor("#D9650F"), MOMO = Color.parseColor("#B88A00"), CASH = Color.parseColor("#3B5BA5"),
            GOOD = Color.parseColor("#1E7546"), WARN = Color.parseColor("#9A5600"), BAD = Color.parseColor("#B0261D"),
            GOOD_BG = Color.parseColor("#E3F1E8"), BAD_BG = Color.parseColor("#F8E1DF"), WARN_BG = Color.parseColor("#F8EBD8"),
            PURPLE = Color.parseColor("#6B4FA0"), ON_ACCENT_SOFT = Color.parseColor("#CFE6DF");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout permBox, page, periodBar;
    private HorizontalScrollView periodScroll;
    private ScrollView scroll;
    private TextView status, noticeText, classerBadge;
    private final TextView[] periodChips = new TextView[PERIODS.length + 1];
    private final LinearLayout[] navItems = new LinearLayout[4];
    private final FrameLayout[] navIconBoxes = new FrameLayout[4];
    private final ImageView[] navIcons = new ImageView[4];
    private final TextView[] navLabels = new TextView[4];
    private int period = 0, pageIdx = PAGE_CAISSE, liste = LISTE_JOURNAL;
    private long customFrom, customTo;
    private boolean loading, rechargeOuverte, avanceOuvert, uiPrete;
    private String rechargeOp = "OM";
    private final java.util.Set<String> comptesOuverts = new java.util.HashSet<>();
    private final Runnable hideNotice = () -> noticeText.setVisibility(View.GONE);

    // Champs de saisie conservés d'un affichage à l'autre (sinon un nouveau SMS effacerait la saisie en cours).
    private EditText rechargeMontant, rechargeRef, phoneField, secretField, ownerField, omField, momoField;
    private CheckBox agentOmBox, agentMomoBox, autoBox;

    // Export en cours : figé au moment de la confirmation.
    private int exportNb;
    private String exportLabel = "";
    private long[] exportRange = {0, Long.MAX_VALUE};
    private boolean exportPayant;

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
        String[] labels = {"Caisse", "À classer", "Exporter", "Compte"};
        int[] icons = {R.drawable.ic_nav_caisse, R.drawable.ic_nav_classer, R.drawable.ic_nav_export, R.drawable.ic_nav_compte};
        for (int i = 0; i < 4; i++) {
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
        periodScroll.setVisibility(pageIdx == PAGE_COMPTE ? View.GONE : View.VISIBLE);
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
        for (int i = 0; i < 4; i++) {
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
        card.addView(text("ventes − paiements − frais opérateur", 12, ON_ACCENT_SOFT, false));

        Caisse.Total v = b.total(Caisse.VENTE), p = b.total(Caisse.PAIEMENT), d = b.total(Caisse.DON), pe = b.total(Caisse.PERSONNEL);
        LinearLayout stats = new LinearLayout(this);
        stats.setPadding(0, dp(8), 0, dp(2));
        String[][] cols = {{"Ventes", "+" + SmsParser.fmt(v.entrees - v.sorties)}, {"Paiements", "−" + SmsParser.fmt(p.sorties - p.entrees)}, {"Frais", "−" + SmsParser.fmt(b.frais)}};
        for (int i = 0; i < cols.length; i++) {
            LinearLayout c = column(1);
            c.addView(text(cols[i][0], 12, ON_ACCENT_SOFT, false));
            TextView val = text(cols[i][1], 15, Color.WHITE, true);
            val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            c.addView(val);
            stats.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        card.addView(stats, fill());
        if (d.count > 0 || pe.count > 0) {
            card.addView(text("Dons +" + SmsParser.fmt(d.entrees) + " / −" + SmsParser.fmt(d.sorties)
                    + "   ·   Personnel +" + SmsParser.fmt(pe.entrees) + " / −" + SmsParser.fmt(pe.sorties), 12, ON_ACCENT_SOFT, false));
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
        if (bilan == null) { page.addView(empty("Ouverture de votre caisse…")); return; }
        int nb = nbOperations(bilan);
        int especes = nb - bilan.lignes.size();

        LinearLayout rep = card();
        rep.addView(label("Rapport Excel · " + periodLabel()));
        TextView total = text(nb + " opération" + (nb > 1 ? "s" : ""), 24, INK, true);
        rep.addView(total);
        rep.addView(text(bilan.lignes.size() + " mobile money · " + especes + " espèces · résultat "
                + (bilan.resultat >= 0 ? "" : "−") + SmsParser.fmt(Math.abs(bilan.resultat)) + " FCFA", 13, MUTED, false));
        if (!bilan.aClasser.isEmpty()) {
            TextView w = text("⚠  " + bilan.aClasser.size() + " opération(s) non classée(s) : classez-les d'abord pour un rapport complet  ›", 13, WARN, true);
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
                    + ". Le classeur contient la synthèse, le journal de caisse, les opérations à classer, les espèces et les SMS à vérifier.", 12, MUTED, false));
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
        card.addView(text("1. Choisissez l'opérateur", 14, INK, true));
        boolean om = "OM".equals(rechargeOp);
        if (!w.numeroOm.isEmpty() && !w.numeroMomo.isEmpty()) {
            card.addView(segmented(new String[] {"Orange Money", "MTN MoMo"}, om ? 0 : 1, k -> { rechargeOp = k == 0 ? "OM" : "MOMO"; render(); }), fill());
        }
        String numero = om ? w.numeroOm : w.numeroMomo;
        card.addView(text("2. Envoyez le montant par transfert au", 14, INK, true));
        TextView num = text(formatNumero(numero), 22, om ? OM : MOMO, true);
        num.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        num.setTextIsSelectable(true);
        card.addView(num);
        if (!w.marchand.isEmpty()) card.addView(text("au nom de " + w.marchand, 13, MUTED, false));

        if (rechargeMontant == null) {
            rechargeMontant = amountField("Montant en FCFA");
            rechargeMontant.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        LinearLayout amounts = new LinearLayout(this);
        long[] presets = {1000, 2000, 5000, 10000};
        for (int i = 0; i < presets.length; i++) {
            final long a = presets[i];
            amounts.addView(small(SmsParser.fmt(a * 100), INK, v -> rechargeMontant.setText(String.valueOf(a))), weight(i == presets.length - 1));
        }
        card.addView(amounts, fill());
        card.addView(detach(rechargeMontant), fill());
        card.addView(text("Minimum " + Tarif.fcfa(w.rechargeMin) + ". Exemple : 5 000 FCFA = 10 exports de 200 opérations au plus.", 12, MUTED, false));

        card.addView(text("3. Recopiez l'ID de transaction reçu par SMS", 14, INK, true));
        if (rechargeRef == null) {
            rechargeRef = new EditText(this);
            rechargeRef.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            rechargeRef.setSingleLine(true);
        }
        rechargeRef.setHint(om ? "ex. PP251114.1530.A12345" : "ex. 8472619305");
        card.addView(detach(rechargeRef), fill());
        card.addView(button("Déclarer la recharge", true, v -> declarerRecharge()), fill());
        card.addView(text("Votre solde est crédité dès la vérification du paiement.", 12, MUTED, false));
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
        boolean oldAuto = Store.prefBool(this, "auto", false);
        String om = omField.getText().toString(), momo = momoField.getText().toString();
        boolean auto = autoBox.isChecked();
        Store.saveConfig(this, om, momo, auto, ownerField.getText().toString().trim());
        Store.saveFlag(this, "agent" + Caisse.OM, agentOmBox.isChecked());
        Store.saveFlag(this, "agent" + Caisse.MOMO, agentMomoBox.isChecked());
        boolean expediteurs = !om.equals(oldOm) || !momo.equals(oldMomo) || auto != oldAuto;
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
                msg = "Sauvegarde terminée : " + Cloud.sync(this);
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
                msg = (create ? "Compte créé : vos données sont sauvegardées et votre premier export est offert. "
                        : "Connecté : vos données ont été récupérées et fusionnées avec celles de ce téléphone. ") + r;
                niveau = 1;
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
                    runOnUiThread(() -> { notice("Déconnecté.", 0); render(); });
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

    private void declarerRecharge() {
        Cloud.Portefeuille w = Cloud.portefeuille(this);
        String digits = rechargeMontant.getText().toString().replaceAll("\\D", "");
        long montant = digits.isEmpty() || digits.length() > 7 ? 0 : Long.parseLong(digits);
        String ref = rechargeRef.getText().toString().trim();
        if (montant < w.rechargeMin) { notice("Indiquez le montant envoyé (minimum " + Tarif.fcfa(w.rechargeMin) + ").", 2); return; }
        if (ref.replaceAll("[^A-Za-z0-9]", "").length() < 6) { notice("Recopiez l'ID de transaction complet reçu par SMS après le transfert.", 2); return; }
        final String op = rechargeOp;
        notice("Envoi de la déclaration…", 0);
        io.execute(() -> {
            String msg;
            int niveau;
            try {
                msg = Cloud.declarerRecharge(this, op, ref, montant);
                niveau = 1;
            } catch (Cloud.CloudException e) {
                msg = e.getMessage();
                niveau = 2;
            }
            final String m = msg;
            final int n = niveau;
            runOnUiThread(() -> {
                if (n == 1) {
                    rechargeRef.setText("");
                    rechargeMontant.setText("");
                    rechargeOuverte = false;
                }
                notice(m, n);
                render();
            });
        });
    }

    // ---------- Export Excel ----------

    private void lancerExport() {
        if (bilan == null) { notice("Rien à exporter pour l'instant.", 0); return; }
        final int nb = nbOperations(bilan);
        final String label = periodLabel();
        final long[] r = range();
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
        i.putExtra(Intent.EXTRA_TITLE, "caisse-mobile-money-" + day + ".xlsx");
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
        final Caisse.Bilan b = computeBilan(range[0], range[1]);
        notice("Préparation du rapport Excel…", 0);
        io.execute(() -> {
            String msg;
            int niveau = 1;
            Cloud.Debit debit = null;
            try {
                // 1. Débit (en ligne, ou sur le dernier solde connu si pas de réseau).
                if (payant) debit = Cloud.payerExport(this, nb, label);
                // 2. Écriture du fichier.
                byte[] xlsx = Exporter.xlsx(r, b, range[0], range[1], label, System.currentTimeMillis());
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
