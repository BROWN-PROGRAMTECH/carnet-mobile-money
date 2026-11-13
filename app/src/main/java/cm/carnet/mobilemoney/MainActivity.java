package cm.carnet.mobilemoney;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Écran unique : autorisation, soldes par compte, journal, SMS à vérifier, export CSV. */
public class MainActivity extends Activity {
    private static final int REQ_PERM = 1, REQ_EXPORT = 2;
    private static final int MAX_ROWS = 400;

    // Couleurs (thème clair)
    private static final int BG = Color.parseColor("#F3F5F2"), SURFACE = Color.WHITE, INK = Color.parseColor("#17201D"),
            MUTED = Color.parseColor("#5B6662"), LINE = Color.parseColor("#D8DED9"), ACCENT = Color.parseColor("#0E6A5B"),
            OM = Color.parseColor("#D9650F"), MOMO = Color.parseColor("#B88A00"), GOOD = Color.parseColor("#1E7546"),
            WARN = Color.parseColor("#9A5600"), BAD = Color.parseColor("#B0261D"), GOOD_BG = Color.parseColor("#E3F1E8"),
            BAD_BG = Color.parseColor("#F8E1DF"), WARN_BG = Color.parseColor("#F8EBD8");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout root, permBox, accounts, list;
    private TextView status;
    private Button tabJournal, tabCheck;
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
        if (ok) reload(true);
        else {
            status.setText("En attente de l'autorisation de lire les SMS.");
            reload(false);
        }
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

    // ---------- Lecture des SMS ----------

    /** importInbox : relit la messagerie du téléphone avant d'analyser. */
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
                List<SmsParser.Sms> stored = Store.get(this).all();
                Analyzer.Result r = Analyzer.analyse(stored, cfg);
                result = r;
                msg = r.scanned + " SMS mobile money · " + r.tx.size() + " transactions · " + r.unknown.size()
                        + " à vérifier · " + r.dup + " doublons ignorés" + (added > 0 ? " · " + added + " nouveaux" : "");
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

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? Color.WHITE : INK);
        b.setBackground(box(primary ? ACCENT : SURFACE, primary ? ACCENT : LINE, 10));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(dp(44));
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = column(14);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        root.addView(text("Carnet Mobile Money", 22, INK, true));
        root.addView(text("Lit vos SMS Orange Money et MTN MoMo, tient le journal et compare solde réel et solde théorique.", 14, MUTED, false));

        // Bloc d'autorisation
        permBox = column(10);
        permBox.setBackground(box(WARN_BG, 0, 14));
        permBox.setPadding(dp(16), dp(16), dp(16), dp(16));
        permBox.addView(text("Autorisation nécessaire", 16, INK, true));
        permBox.addView(text("L'application lit uniquement les SMS des expéditeurs Orange Money et MTN MoMo. Les autres SMS ne sont ni lus ni enregistrés, et rien n'est envoyé sur internet.", 14, INK, false));
        permBox.addView(button("Autoriser la lecture des SMS", true, v -> askPermissions()), fill());
        permBox.addView(text("Si l'autorisation est grisée ou refusée : ouvrez les paramètres de l'application, touchez le menu ⋮ en haut à droite, choisissez « Autoriser les paramètres restreints », puis activez SMS dans Autorisations.", 13, MUTED, false));
        permBox.addView(button("Ouvrir les paramètres de l'application", false, v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }), fill());
        root.addView(permBox, fill());

        status = text("", 13, MUTED, false);
        root.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        w.setMarginEnd(dp(8));
        LinearLayout.LayoutParams wLast = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        actions.addView(button("Actualiser", false, v -> reload(true)), w);
        actions.addView(button("Exporter", true, v -> startExport()), w);
        actions.addView(button("Réglages", false, v -> openSettings()), wLast);
        root.addView(actions, fill());

        root.addView(text("COMPTES", 12, MUTED, true));
        accounts = column(12);
        root.addView(accounts, fill());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabJournal = button("Journal", false, v -> { showCheck = false; render(); });
        tabCheck = button("À vérifier", false, v -> { showCheck = true; render(); });
        tabs.addView(tabJournal, w);
        tabs.addView(tabCheck, wLast);
        root.addView(tabs, fill());

        list = column(0);
        list.setBackground(box(SURFACE, LINE, 14));
        list.setPadding(dp(14), dp(4), dp(14), dp(4));
        root.addView(list, fill());

        root.addView(text("Prototype de test. Les règles de lecture sont génériques et s'affineront avec vos vrais SMS. Une « rupture de chaîne » signifie qu'un nouveau solde ne se déduit pas du précédent : SMS manquant ou montant mal lu.", 12, MUTED, false));
    }

    private void render() {
        accounts.removeAllViews();
        list.removeAllViews();
        Analyzer.Result r = result;
        if (r == null) return;

        if (r.accounts.isEmpty()) accounts.addView(text("Aucun compte détecté pour l'instant.", 14, MUTED, false));
        for (Analyzer.Account a : r.accounts) accounts.addView(accountCard(a, r.accounts), fill());

        tabJournal.setText("Journal (" + r.tx.size() + ")");
        tabCheck.setText("À vérifier (" + r.unknown.size() + ")");
        tabJournal.setBackground(box(showCheck ? SURFACE : INK, showCheck ? LINE : INK, 10));
        tabJournal.setTextColor(showCheck ? INK : Color.WHITE);
        tabCheck.setBackground(box(showCheck ? INK : SURFACE, showCheck ? INK : LINE, 10));
        tabCheck.setTextColor(showCheck ? Color.WHITE : INK);

        if (!showCheck) {
            if (r.tx.isEmpty()) list.addView(empty("Aucune transaction."));
            int n = 0;
            for (Txn t : r.tx) {
                if (n++ >= MAX_ROWS) { list.addView(empty("Seules les " + MAX_ROWS + " dernières opérations sont affichées. L'export contient tout.")); break; }
                list.addView(txRow(t, r.accounts), fill());
            }
        } else {
            if (r.unknown.isEmpty()) list.addView(empty("Rien à vérifier."));
            for (Txn u : r.unknown) list.addView(unknownRow(u), fill());
        }
    }

    private TextView empty(String s) {
        TextView t = text(s, 14, MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(20), 0, dp(20));
        return t;
    }

    private View accountCard(Analyzer.Account a, List<Analyzer.Account> all) {
        int opColor = "OM".equals(a.op) ? OM : MOMO;
        LinearLayout card = column(8);
        GradientDrawable bg = box(SURFACE, LINE, 14);
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(box(opColor, 0, 6));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.setMarginEnd(dp(8));
        head.addView(dot, dl);
        TextView name = text(Analyzer.accountLabel(a, all), 16, INK, true);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(a.count + " opér.", 12, MUTED, false));
        card.addView(head, fill());

        card.addView(text("SOLDE RÉEL (DERNIER SMS)", 11, MUTED, true));
        TextView big = text(SmsParser.fmt(a.reel) + " FCFA", 26, INK, true);
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(big);

        card.addView(kv("Solde de départ", SmsParser.fmt(a.start)));
        card.addView(kv("Entrées", "+" + SmsParser.fmt(a.entrees)));
        card.addView(kv("Sorties", "−" + SmsParser.fmt(a.sorties)));
        card.addView(kv("Frais et taxes", "−" + SmsParser.fmt(a.frais)));
        card.addView(kv("Solde théorique", SmsParser.fmt(a.theo)));

        TextView chip;
        if (a.ecart == null) chip = chip("Écart non calculable", MUTED, BG);
        else if (Math.abs(a.ecart) <= 1) chip = chip("Écart nul", GOOD, GOOD_BG);
        else chip = chip("Écart " + (a.ecart > 0 ? "+" : "") + SmsParser.fmt(a.ecart) + " FCFA", BAD, BAD_BG);
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(chip);
        TextView chain = text(a.breaks == 0 ? "  Chaîne des soldes complète" : "  " + a.breaks + " rupture(s) de chaîne", 12, a.breaks == 0 ? MUTED : BAD, false);
        foot.addView(chain);
        card.addView(foot);
        card.addView(text("Du " + when(a.from) + " au " + when(a.to), 12, MUTED, false));
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

    private View txRow(Txn t, List<Analyzer.Account> all) {
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
        TextView amt = text((t.sens > 0 ? "+" : "−") + SmsParser.fmt(t.montant), 15, t.sens > 0 ? GOOD : INK, true);
        amt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(amt);
        row.addView(top, fill());

        String who = (t.nom != null ? t.nom : "") + (t.numero != null ? (t.nom != null ? " · " : "") + t.numero : "");
        row.addView(text(who.isEmpty() ? "Contrepartie non indiquée" : who, 13, MUTED, false));
        String meta = when(t.date) + (t.txnId != null ? " · ID " + t.txnId : " · sans ID")
                + (t.frais + t.taxe > 0 ? " · frais " + SmsParser.fmt(t.frais + t.taxe) : "")
                + " · solde " + SmsParser.fmt(t.solde);
        row.addView(text(meta, 12, MUTED, false));
        for (String f : t.flags) {
            boolean bad = f.startsWith("!");
            row.addView(text(f.substring(1), 12, bad ? BAD : WARN, true));
        }
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

    // ---------- Réglages ----------

    private void openSettings() {
        LinearLayout form = column(10);
        form.setPadding(dp(20), dp(8), dp(20), dp(0));
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
        auto.setText("Repérer aussi les autres expéditeurs dont les SMS parlent d'Orange Money ou de MoMo");
        auto.setChecked(Store.prefBool(this, "auto", true));
        form.addView(auto, fill());

        new AlertDialog.Builder(this)
                .setTitle("Réglages")
                .setView(form)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    Store.saveConfig(this, om.getText().toString(), momo.getText().toString(), auto.isChecked());
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

    // ---------- Export CSV ----------

    private void startExport() {
        if (result == null || result.tx.isEmpty()) {
            Toast.makeText(this, "Aucune transaction à exporter.", Toast.LENGTH_SHORT).show();
            return;
        }
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "journal-mobile-money-" + day + ".csv");
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        final Analyzer.Result r = result;
        io.execute(() -> {
            String msg;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(csv(r).getBytes(StandardCharsets.UTF_8));
                msg = "Journal exporté. Ouvrez-le avec Excel ou Google Sheets.";
            } catch (Exception e) {
                msg = "Export impossible : " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }

    private static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String csv(Analyzer.Result r) {
        StringBuilder b = new StringBuilder("﻿");
        b.append("Date;Compte;Type;Sens;Montant (FCFA);Frais et taxes (FCFA);Contrepartie;Numéro;ID transaction;Solde après (FCFA);Motif;Contrôle\r\n");
        List<Txn> rows = new ArrayList<>(r.tx);
        Collections.reverse(rows);
        for (Txn t : rows) {
            StringBuilder flags = new StringBuilder();
            for (String f : t.flags) flags.append(flags.length() > 0 ? " ; " : "").append(f.substring(1));
            b.append(cell(when(t.date))).append(';')
                    .append(cell(Txn.opName(t.op) + (t.sub.isEmpty() ? "" : " SIM " + t.sub))).append(';')
                    .append(cell(t.typeLabel())).append(';')
                    .append(t.sens > 0 ? "Entrée" : "Sortie").append(';')
                    .append(t.montant).append(';')
                    .append(t.frais + t.taxe).append(';')
                    .append(cell(t.nom)).append(';')
                    .append(cell(t.numero)).append(';')
                    .append(cell(t.txnId)).append(';')
                    .append(t.solde == null ? "" : String.valueOf(t.solde)).append(';')
                    .append(cell(t.motif)).append(';')
                    .append(cell(flags.length() == 0 ? "OK" : flags.toString())).append("\r\n");
        }
        b.append("\r\nSynthèse\r\nCompte;Opérations;Solde de départ;Entrées;Sorties;Frais et taxes;Solde théorique;Solde réel;Écart;Ruptures de chaîne\r\n");
        for (Analyzer.Account a : r.accounts) {
            b.append(cell(Analyzer.accountLabel(a, r.accounts))).append(';').append(a.count).append(';')
                    .append(a.start == null ? "" : a.start).append(';').append(a.entrees).append(';')
                    .append(a.sorties).append(';').append(a.frais).append(';')
                    .append(a.theo == null ? "" : a.theo).append(';').append(a.reel == null ? "" : a.reel).append(';')
                    .append(a.ecart == null ? "" : a.ecart).append(';').append(a.breaks).append("\r\n");
        }
        return b.toString();
    }
}
