package cm.carnet.mobilemoney;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Contenu des exports : classeur Excel (par défaut), CSV, SMS anonymisés. Classe Java pure. */
public final class Exporter {
    private Exporter() {}

    static Double fcfa(Long cents) { return cents == null ? null : cents / 100.0; }

    static String sens(Txn t) {
        return t.isBalanceCheck() ? "Contrôle" : (t.sens > 0 ? "Entrée" : "Sortie");
    }

    static String controle(Txn t) {
        if (t.flags.isEmpty()) return "OK";
        StringBuilder b = new StringBuilder();
        for (String f : t.flags) b.append(b.length() > 0 ? " ; " : "").append(f.substring(1));
        return b.toString();
    }

    /** Opérations de la période, de la plus ancienne à la plus récente. */
    static List<Txn> inPeriod(Analyzer.Result r, long from, long to) {
        List<Txn> rows = new ArrayList<>();
        for (Txn t : r.tx) if (t.date >= from && t.date <= to) rows.add(t);
        Collections.reverse(rows);
        return rows;
    }

    public static byte[] xlsx(Analyzer.Result r, long from, long to, String periodLabel) throws IOException {
        List<Analyzer.Account> acc = Analyzer.summary(r, from, to);
        XlsxWriter w = new XlsxWriter();

        XlsxWriter.Sheet s = w.sheet("Synthèse", 22, 14, 14, 14, 14, 14, 16, 16, 14, 12).noHeader();
        s.row("**Carnet Mobile Money : synthèse");
        s.row("Période", periodLabel);
        s.row("Exporté le", new Date());
        if (r.ownerOm != null && !r.ownerOm.isEmpty()) s.row("Titulaire Orange Money", r.ownerOm);
        s.row();
        s.row("**Compte", "**Opérations", "**Solde d'ouverture", "**Entrées", "**Sorties", "**Frais et taxes",
                "**Solde théorique", "**Solde réel (SMS)", "**Écart", "**Alertes");
        for (Analyzer.Account a : acc)
            s.row(Txn.opName(a.op), a.count, fcfa(a.ouverture), fcfa(a.entrees), fcfa(a.sorties), fcfa(a.frais),
                    fcfa(a.theo), fcfa(a.reel), fcfa(a.ecart), a.breaks);
        s.row();
        s.row("**Comment lire");
        s.row("Solde d'ouverture", "Solde du compte juste avant la période, d'après le dernier SMS connu.");
        s.row("Solde théorique", "Ouverture + entrées − sorties − frais, d'après les SMS de la période.");
        s.row("Solde réel", "Dernier solde annoncé par l'opérateur dans la période.");
        s.row("Écart", "Réel − théorique. Un écart non nul signale un SMS manquant ou mal lu : voir la colonne Contrôle du journal.");

        XlsxWriter.Sheet j = w.sheet("Journal", 17, 14, 20, 9, 13, 11, 14, 14, 24, 12, 24, 18, 40);
        j.row("Date", "Compte", "Type", "Sens", "Montant (FCFA)", "Frais (FCFA)", "Solde avant (FCFA)", "Solde après (FCFA)",
                "Contrepartie", "Numéro", "ID transaction", "Motif", "Contrôle");
        for (Txn t : inPeriod(r, from, to))
            j.row(new Date(t.date), Txn.opName(t.op), t.typeLabel(), sens(t), t.isBalanceCheck() ? null : fcfa(t.montant),
                    t.frais == 0 ? null : fcfa(t.frais), fcfa(t.avant), fcfa(t.solde != null ? t.solde : t.apres),
                    t.nom, t.numero, t.txnId, t.motif, controle(t));

        XlsxWriter.Sheet v = w.sheet("À vérifier", 17, 14, 40, 100);
        v.row("Date", "Compte", "Raison", "SMS");
        for (Txn u : r.unknown)
            if (u.smsDate >= from && u.smsDate <= to) v.row(new Date(u.smsDate), Txn.opName(u.op), u.reason, u.raw);
        return w.bytes();
    }

    static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    static String dec(Long cents) {
        return cents == null ? "" : SmsParser.plain(cents).replace('.', ',');
    }

    public static String csv(Analyzer.Result r, long from, long to) {
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        StringBuilder b = new StringBuilder("﻿");
        b.append("Date;Compte;Type;Sens;Montant (FCFA);Frais (FCFA);Solde avant (FCFA);Solde après (FCFA);Contrepartie;Numéro;ID transaction;Motif;Contrôle\r\n");
        for (Txn t : inPeriod(r, from, to)) {
            b.append(df.format(new Date(t.date))).append(';').append(Txn.opName(t.op)).append(';').append(t.typeLabel()).append(';')
                    .append(sens(t)).append(';').append(t.isBalanceCheck() ? "" : dec(t.montant)).append(';')
                    .append(t.frais == 0 ? "" : dec(t.frais)).append(';').append(dec(t.avant)).append(';')
                    .append(dec(t.solde != null ? t.solde : t.apres)).append(';').append(cell(t.nom)).append(';')
                    .append(cell(t.numero)).append(';').append(cell(t.txnId)).append(';').append(cell(t.motif)).append(';')
                    .append(cell(controle(t))).append("\r\n");
        }
        return b.toString();
    }

    /** SMS mobile money avec numéros et noms masqués, pour améliorer les règles de lecture. */
    public static String anonymizedSms(List<SmsParser.Sms> stored, SmsParser.Config cfg) {
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE);
        StringBuilder b = new StringBuilder("SMS Orange Money et MTN MoMo anonymisés (numéros et noms masqués).\n\n");
        for (SmsParser.Sms m : stored) {
            String op = SmsParser.detectOp(m.address, m.body, cfg);
            if (op == null) continue;
            m.op = op;
            Txn t = SmsParser.parse(m, cfg);
            b.append("[").append(m.address).append(" | ").append(df.format(new Date(m.date))).append("]\n")
                    .append(anonymize(m.body, t.nom)).append("\n\n");
        }
        return b.toString();
    }

    public static String anonymize(String body, String knownName) {
        String s = body == null ? "" : body;
        if (knownName != null && knownName.length() > 2) s = s.replace(knownName, "<NOM>");
        s = s.replaceAll("(?<!\\d)(?:\\+?237)?6\\d{8}(?!\\d)", "6XXXXXXXX");
        s = s.replaceAll("\\b[A-ZÀ-Ý][A-ZÀ-Ý'\\-]+(?:\\s+[A-ZÀ-Ý][A-ZÀ-Ý'\\-]+)+\\b", "<NOM>");
        s = s.replaceAll("(?i)(code\\s*:?\\s*)\\d{4,6}", "$1XXXX");
        return s;
    }
}
