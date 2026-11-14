package cm.carnet.mobilemoney;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** Classeur de caisse : Synthèse, Journal de caisse, À classer, Espèces, À vérifier. */
    public static byte[] xlsx(Analyzer.Result r, Caisse.Bilan bilan, long from, long to, String periodLabel, long now) throws IOException {
        XlsxWriter w = new XlsxWriter();

        XlsxWriter.Sheet s = w.sheet("Synthèse", 28, 16, 16, 12, 16, 16, 14, 14, 14).noHeader();
        s.row("**Carnet Mobile Money : rapport de caisse");
        s.row("Période", periodLabel);
        s.row("Exporté le", new Date(now));
        if (r.ownerOm != null && !r.ownerOm.isEmpty()) s.row("Titulaire Orange Money", r.ownerOm);
        s.row();
        s.row("**Catégorie", "**Entrées (FCFA)", "**Sorties (FCFA)", "**Opérations");
        for (Map.Entry<String, Caisse.Total> e : bilan.totaux.entrySet()) {
            Caisse.Total t = e.getValue();
            if (t.count == 0 && e.getKey() == null) continue;
            s.row(Caisse.categorieNom(e.getKey()), fcfa(t.entrees), fcfa(t.sorties), t.count);
        }
        s.row("Frais opérateur", null, fcfa(bilan.frais));
        s.row("**Résultat (ventes − paiements − frais)", fcfa(bilan.resultat));
        if (!bilan.aClasser.isEmpty()) s.row("**Attention", bilan.aClasser.size() + " opération(s) encore à classer : le résultat est provisoire.");
        s.row();
        s.row("**Comptes", "**Solde d'ouverture", "**Entrées", "**Sorties", "**Frais", "**Solde théorique", "**Solde réel", "**Écart", "**À classer");
        for (Caisse.Compte c : bilan.comptes)
            s.row(Txn.opName(c.op) + (c.agent ? " (agent)" : ""), fcfa(c.ouverture), fcfa(c.entrees), fcfa(c.sorties), fcfa(c.frais),
                    fcfa(c.theo), fcfa(c.reel), fcfa(c.ecart), c.aClasser);
        Caisse.Especes e = bilan.especes;
        s.row("Espèces", fcfa(e.ouverture), fcfa(e.entrees + e.depotsClients), fcfa(e.sorties + e.retraitsClients), null,
                fcfa(e.theo), fcfa(e.compte), fcfa(e.ecart));
        if (!e.origineOuverture.isEmpty()) s.row("Ouverture des espèces", e.origineOuverture);
        s.row();
        s.row("**Comment lire");
        s.row("Montants", "Lus dans les SMS de l'opérateur, non modifiables. Seuls la catégorie et le motif sont déclarés par l'utilisateur.");
        s.row("Solde théorique", "Comptes OM et MoMo : ouverture + entrées − sorties − frais d'après les SMS. Un écart signale un SMS manquant.");
        s.row("Espèces", "Fond de caisse + entrées − sorties saisies (+ dépôts − retraits clients en mode agent), comparé au comptage.");

        XlsxWriter.Sheet j = w.sheet("Journal de caisse", 17, 14, 20, 9, 13, 11, 14, 24, 12, 24, 16, 30, 36);
        j.row("Date", "Compte", "Type", "Sens", "Montant (FCFA)", "Frais (FCFA)", "Solde après (FCFA)", "Contrepartie", "Numéro",
                "ID transaction", "Catégorie", "Motif", "Contrôle");
        List<Caisse.Ligne> asc = new ArrayList<>(bilan.lignes);
        Collections.reverse(asc);
        for (Caisse.Ligne l : asc) {
            Txn t = l.sms;
            j.row(new Date(t.date), Txn.opName(t.op), t.typeLabel(), sens(t), fcfa(t.montant), t.frais == 0 ? null : fcfa(t.frais),
                    fcfa(t.solde != null ? t.solde : t.apres), t.nom, t.numero, t.txnId,
                    Caisse.categorieNom(l.categorie) + (l.auto ? " (auto)" : ""), l.motif, controle(t));
        }

        XlsxWriter.Sheet c = w.sheet("À classer", 17, 14, 20, 9, 13, 24, 12, 24);
        c.row("Date", "Compte", "Type", "Sens", "Montant (FCFA)", "Contrepartie", "Numéro", "ID transaction");
        List<Caisse.Ligne> ac = new ArrayList<>(bilan.aClasser);
        Collections.reverse(ac);
        for (Caisse.Ligne l : ac) {
            Txn t = l.sms;
            c.row(new Date(t.date), Txn.opName(t.op), t.typeLabel(), sens(t), fcfa(t.montant), t.nom, t.numero, t.txnId);
        }

        XlsxWriter.Sheet es = w.sheet("Espèces", 17, 10, 13, 16, 30, 12);
        es.row("Date", "Sens", "Montant (FCFA)", "Catégorie", "Libellé", "Statut");
        List<Caisse.Saisie> me = new ArrayList<>(bilan.mouvementsEspeces);
        Collections.reverse(me);
        for (Caisse.Saisie x : me)
            es.row(new Date(x.date), x.sens > 0 ? "Entrée" : "Sortie", fcfa(x.montant), Caisse.categorieNom(x.categorie), x.libelle,
                    x.annule ? "Annulée" : "Valide");

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
