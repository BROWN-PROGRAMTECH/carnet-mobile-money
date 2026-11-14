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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Écran unique : autorisation, période, soldes par opérateur, journal, à vérifier, exports. */
public class MainActivity extends Activity {
    private static final int REQ_PERM = 1, REQ_XLSX = 2, REQ_CSV = 3, REQ_SMS = 4;
    private static final int MAX_ROWS = 400;
    private static final String[] PERIODS = {"Aujourd'hui", "7 jours", "Ce mois", "Tout"};

    private static final int BG = Color.parseColor("#F3F5F2"), SURFACE = Color.WHITE, INK = Color.parseColor("#17201D"),
            MUTED = Color.parseColor("#5B6662"), LINE = Color.parseColor("#D8DED9"), ACCENT = Color.parseColor("#0E6A5B"),
            OM = Color.parseColor("#D9650F"), MOMO = Color.parseColor("#B88A00"), GOOD = Color.parseColor("#1E7546"),
            WARN = Color.parseColor("#9A5600"), BAD = Color.parseColor("#B0261D"), GOOD_BG = Color.parseColor("#E3F1E8"),
            BAD_BG = Color.parseColor("#F8E1DF"), WARN_BG = Color.parseColor("#F8EBD8");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout permBox, accounts, list;
    private TextView status;
    private Button tabJournal, tabCheck;
    private final Button[] periodButtons = new Button[PERIODS.length];
    private Button customButton;
    /** Période précise choisie par l'utilisateur (index 4). */
    private long customFrom, customTo;
    private int period = 2;
    private boolean showCheck;
    private Analyzer.Result result;
    private boolean loading;

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
        try { period = Integer.parseInt(Store.pref(this, "periode", "2")); } catch (Exception ignored) {}
        try {
            customFrom = Long.parseLong(Store.pref(this, "debut", "0"));
            customTo = Long.parseLong(Store.pref(this, "fin", "0"));
        } catch (Exception ignored) {}
        if (period < 0 || period > PERIODS.length || (period == PERIODS.length && customTo <= 0)) period = 2;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(SmsReceiver.ACTION_NEW);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(onNewSms, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(onNewSms, f);
        refreshPermissionState();
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

    // ---------- Période ----------

    /** {début, fin} en millisecondes pour la période choisie. */
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
            case 4: return new long[] {customFrom, customTo};
            default: return new long[] {0, Long.MAX_VALUE};
        }
        return new long[] {c.getTimeInMillis(), Long.MAX_VALUE};
    }

    private String periodLabel() {
        long[] r = range();
        SimpleDateFormat d = new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE);
        if (period == PERIODS.length) return "Du " + d.format(new Date(r[0])) + " au " + d.format(new Date(r[1]));
        if (r[0] == 0) return "Tout l'historique";
        return PERIODS[period] + " (depuis le " + new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date(r[0])) + ")";
    }

    // ---------- Lecture des SMS ----------

    private void reload(boolean importInbox) {
        if (loading) return;
        loading = true;
        if (importInbox) status.setText("Lecture de vos SMS…");
        io.execute(() -> {
            String msg;
            int added = 0;
            try {
                SmsParser.Config cfg = Store.config(this);
                if (importInbox && hasSmsPermission()) added = importInbox(cfg);
                Analyzer.Result r = Analyzer.analyse(Store.get(this).all(), cfg);
                if (!r.ownerOm.isEmpty()) Store.savePref(this, "ownerOmAuto", r.ownerOm);
                result = r;
                msg = r.tx.size() + " opérations · " + r.unknown.size() + " à vérifier · " + r.dup + " doublons ignorés"
                        + (added > 0 ? " · " + added + " nouveaux SMS" : "");
                if (r.scanned == 0) msg = hasSmsPermission()
                        ? "Aucun SMS Orange Money ou MoMo trouvé. Vérifiez les noms d'expéditeur dans Réglages."
                        : "Autorisez la lecture des SMS pour commencer.";
            } catch (Exception e) {
                msg = "Erreur de lecture : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                loading = false;
                status.setText(m);
                render();
            });
        });
    }

    private int importInbox(SmsParser.Config cfg) {
        Cursor c;
        boolean withSub = true;
        try {
            c = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, new String[] {"address", "body", "date", "sub_id"}, null, null, "date DESC");
        } catch (Exception e) {
            withSub = false;
            c = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, new String[] {"address", "body", "date"}, null, null, "date DESC");
        }
        if (c == null) return 0;
        int added = 0, n = 0;
        Store store = Store.get(this);
        try {
            while (c.moveToNext() && n++ < 30000) {
                String address = c.getString(0), body = c.getString(1);
                if (body == null) continue;
                if (SmsParser.detectOp(address, body, cfg) == null) continue;
                String sub = withSub && !c.isNull(3) ? c.getString(3) : "";
                if ("-1".equals(sub)) sub = "";
                if (store.insert(new SmsParser.Sms(address, body, c.getLong(2), sub))) added++;
            }
        } finally {
            c.close();
        }
        return added;
    }

    // ---------- Interface ----------

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
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinHeight(dp(44));
        b.setOnClickListener(l);
        return b;
    }

    private void select(Button b, boolean on) {
        b.setBackground(box(on ? INK : SURFACE, on ? INK : LINE, 10));
        b.setTextColor(on ? Color.WHITE : INK);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = column(14);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        root.addView(text("Carnet Mobile Money", 22, INK, true));
        root.addView(text("Vos SMS Orange Money et MTN MoMo, en journal de caisse : solde réel, solde théorique et écart.", 14, MUTED, false));

        permBox = column(10);
        permBox.setBackground(box(WARN_BG, 0, 14));
        permBox.setPadding(dp(16), dp(16), dp(16), dp(16));
        permBox.addView(text("Autorisation nécessaire", 16, INK, true));
        permBox.addView(text("L'application lit uniquement les SMS des expéditeurs Orange Money et MTN MoMo. Les autres SMS ne sont ni lus ni enregistrés, et rien n'est envoyé sur internet.", 14, INK, false));
        permBox.addView(button("Autoriser la lecture des SMS", true, v -> askPermissions()), fill());
        permBox.addView(text("Si l'autorisation est grisée ou refusée : ouvrez les paramètres de l'application, touchez le menu ⋮ en haut à droite, choisissez « Autoriser les paramètres restreints », puis activez SMS dans Autorisations.", 13, MUTED, false));
        permBox.addView(button("Ouvrir les paramètres de l'application", false, v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))), fill());
        root.addView(permBox, fill());

        LinearLayout periods = new LinearLayout(this);
        for (int i = 0; i < PERIODS.length; i++) {
            final int p = i;
            periodButtons[i] = button(PERIODS[i], false, v -> {
                period = p;
                Store.savePref(this, "periode", String.valueOf(p));
                render();
            });
            periodButtons[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            periods.addView(periodButtons[i], weight(i == PERIODS.length - 1));
        }
        root.addView(periods, fill());
        customButton = button("Période précise…", false, v -> pickRange());
        customButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(customButton, fill());

        status = text("", 13, MUTED, false);
        root.addView(status);

        accounts = column(12);
        root.addView(accounts, fill());

        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("Actualiser", false, v -> reload(true)), weight(false));
        actions.addView(button("Exporter Excel", true, v -> startExport(REQ_XLSX)), weight(false));
        actions.addView(button("Plus…", false, v -> openMore()), weight(true));
        root.addView(actions, fill());

        LinearLayout tabs = new LinearLayout(this);
        tabJournal = button("Journal", false, v -> { showCheck = false; render(); });
        tabCheck = button("À vérifier", false, v -> { showCheck = true; render(); });
        tabs.addView(tabJournal, weight(false));
        tabs.addView(tabCheck, weight(true));
        root.addView(tabs, fill());

        list = column(0);
        list.setBackground(box(SURFACE, LINE, 14));
        list.setPadding(dp(14), dp(4), dp(14), dp(4));
        root.addView(list, fill());

        root.addView(text("Un compte par opérateur. Une « rupture de chaîne » signifie qu'un solde ne se déduit pas du précédent : un SMS manque ou a été mal lu. Version 0.2.", 12, MUTED, false));
    }

    private void render() {
        for (int i = 0; i < periodButtons.length; i++) select(periodButtons[i], i == period);
        select(customButton, period == PERIODS.length);
        customButton.setText(period == PERIODS.length ? "Période : " + periodLabel().toLowerCase(Locale.FRANCE) + " (modifier)" : "Période précise…");
        accounts.removeAllViews();
        list.removeAllViews();
        Analyzer.Result r = result;
        if (r == null) return;
        long[] range = range();

        List<Analyzer.Account> acc = Analyzer.summary(r, range[0], range[1]);
        if (acc.isEmpty()) accounts.addView(text("Aucun compte détecté pour l'instant.", 14, MUTED, false));
        for (Analyzer.Account a : acc) accounts.addView(accountCard(a), fill());

        List<Txn> rows = new ArrayList<>();
        for (Txn t : r.tx) if (t.date >= range[0] && t.date <= range[1]) rows.add(t);
        List<Txn> unknown = new ArrayList<>();
        for (Txn u : r.unknown) if (u.smsDate >= range[0] && u.smsDate <= range[1]) unknown.add(u);

        tabJournal.setText("Journal (" + rows.size() + ")");
        tabCheck.setText("À vérifier (" + unknown.size() + ")");
        select(tabJournal, !showCheck);
        select(tabCheck, showCheck);

        if (!showCheck) {
            if (rows.isEmpty()) list.addView(empty("Aucune opération sur la période."));
            int n = 0;
            for (Txn t : rows) {
                if (n++ >= MAX_ROWS) { list.addView(empty("Seules les " + MAX_ROWS + " dernières opérations sont affichées. L'export Excel contient tout.")); break; }
                list.addView(txRow(t), fill());
            }
        } else {
            if (unknown.isEmpty()) list.addView(empty("Rien à vérifier sur la période."));
            for (Txn u : unknown) list.addView(unknownRow(u), fill());
        }
    }

    private TextView empty(String s) {
        TextView t = text(s, 14, MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(20), 0, dp(20));
        return t;
    }

    private View accountCard(Analyzer.Account a) {
        int opColor = "OM".equals(a.op) ? OM : MOMO;
        LinearLayout card = column(8);
        card.setBackground(box(SURFACE, LINE, 14));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(box(opColor, 0, 6));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.setMarginEnd(dp(8));
        head.addView(dot, dl);
        head.addView(text(Txn.opName(a.op), 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(a.count + " opér.", 12, MUTED, false));
        card.addView(head, fill());

        card.addView(text("SOLDE RÉEL (DERNIER SMS)", 11, MUTED, true));
        TextView big = text(SmsParser.fmt(a.reel) + " FCFA", 26, INK, true);
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(big);

        card.addView(kv("Solde d'ouverture", SmsParser.fmt(a.ouverture)));
        card.addView(kv("Entrées", "+" + SmsParser.fmt(a.entrees)));
        card.addView(kv("Sorties", "−" + SmsParser.fmt(a.sorties)));
        card.addView(kv("Frais et taxes", "−" + SmsParser.fmt(a.frais)));
        card.addView(kv("Solde théorique", SmsParser.fmt(a.theo)));

        TextView chip;
        if (a.ecart == null) chip = chip("Écart non calculable", MUTED, BG);
        else if (Math.abs(a.ecart) <= Analyzer.TOL) chip = chip("Écart nul", GOOD, GOOD_BG);
        else chip = chip("Écart " + (a.ecart > 0 ? "+" : "") + SmsParser.fmt(a.ecart) + " FCFA", BAD, BAD_BG);
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(chip);
        foot.addView(text(a.breaks == 0 ? "  Chaîne des soldes complète" : "  " + a.breaks + " alerte(s) : voir le journal", 12, a.breaks == 0 ? MUTED : BAD, false));
        card.addView(foot);
        card.addView(text(a.count == 0 ? "Aucune opération sur la période." : "Du " + when(a.from) + " au " + when(a.to), 12, MUTED, false));
        return card;
    }

    private View kv(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.addView(text(k, 14, MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView val = text(v, 14, INK, false);
        val.setTypeface(Typeface.MONOSPACE);
        row.addView(val);
        return row;
    }

    private TextView chip(String s, int fg, int bg) {
        TextView t = text(s, 12, fg, true);
        t.setBackground(box(bg, 0, 20));
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        return t;
    }

    private View txRow(Txn t) {
        LinearLayout row = column(3);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView tag = text("OM".equals(t.op) ? "OM" : "MoMo", 11, Color.WHITE, true);
        tag.setBackground(box("OM".equals(t.op) ? OM : MOMO, 0, 5));
        tag.setPadding(dp(6), dp(1), dp(6), dp(1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginEnd(dp(8));
        top.addView(tag, tl);
        top.addView(text(t.typeLabel(), 15, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String amount = t.isBalanceCheck() ? SmsParser.fmt(t.solde) : (t.sens > 0 ? "+" : "−") + SmsParser.fmt(t.montant);
        TextView amt = text(amount, 15, t.isBalanceCheck() ? MUTED : (t.sens > 0 ? GOOD : INK), true);
        amt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(amt);
        row.addView(top, fill());

        if (!t.isBalanceCheck()) {
            String who = (t.nom != null ? t.nom : "") + (t.numero != null ? (t.nom != null ? " · " : "") + t.numero : "");
            if (!who.isEmpty()) row.addView(text(who, 13, MUTED, false));
        }
        String meta = when(t.date) + (t.txnId != null ? " · ID " + t.txnId : "")
                + (t.frais > 0 ? " · frais " + SmsParser.fmt(t.frais) : "")
                + (t.isBalanceCheck() ? "" : " · solde " + SmsParser.fmt(t.solde != null ? t.solde : t.apres));
        row.addView(text(meta, 12, MUTED, false));
        if (t.motif != null) row.addView(text("Motif : " + t.motif, 12, MUTED, false));
        for (String f : t.flags) row.addView(text(f.substring(1), 12, f.startsWith("!") ? BAD : WARN, true));
        View line = new View(this);
        line.setBackgroundColor(LINE);
        row.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
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

    private static String when(long ts) {
        if (ts <= 0) return "date inconnue";
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date(ts));
    }

    // ---------- Période précise ----------

    /** Demande la date de début, puis la date de fin. */
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
                period = PERIODS.length;
                Store.savePref(this, "debut", String.valueOf(customFrom));
                Store.savePref(this, "fin", String.valueOf(customTo));
                Store.savePref(this, "periode", String.valueOf(period));
                render();
            }, end.get(Calendar.YEAR), end.get(Calendar.MONTH), end.get(Calendar.DAY_OF_MONTH));
            second.setTitle("Date de fin");
            second.show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH));
        first.setTitle("Date de début");
        first.show();
    }

    // ---------- Plus, réglages ----------

    private void openMore() {
        String[] items = {"Exporter en CSV", "Exporter les SMS anonymisés (pour améliorer la lecture)", "Réglages"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) startExport(REQ_CSV);
                    else if (which == 1) startExport(REQ_SMS);
                    else openSettings();
                })
                .show();
    }

    private void openSettings() {
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), 0);
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

        new AlertDialog.Builder(this)
                .setTitle("Réglages")
                .setView(form)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    Store.saveConfig(this, om.getText().toString(), momo.getText().toString(), auto.isChecked(), owner.getText().toString().trim());
                    reload(true);
                })
                .setNeutralButton("Effacer l'historique", (d, w) -> confirmClear())
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Effacer l'historique ?")
                .setMessage("La copie locale des SMS mobile money sera effacée, puis relue depuis votre messagerie. Les SMS déjà supprimés de la messagerie seront perdus.")
                .setPositiveButton("Effacer", (d, w) -> io.execute(() -> {
                    Store.get(this).clear();
                    runOnUiThread(() -> reload(true));
                }))
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- Exports ----------

    private void startExport(int kind) {
        if (result == null || (kind != REQ_SMS && result.tx.isEmpty())) {
            Toast.makeText(this, "Rien à exporter pour l'instant.", Toast.LENGTH_SHORT).show();
            return;
        }
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (kind == REQ_XLSX) {
            i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            i.putExtra(Intent.EXTRA_TITLE, "carnet-mobile-money-" + day + ".xlsx");
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
        io.execute(() -> {
            String msg;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (code == REQ_XLSX) {
                    os.write(Exporter.xlsx(r, range[0], range[1], label));
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
