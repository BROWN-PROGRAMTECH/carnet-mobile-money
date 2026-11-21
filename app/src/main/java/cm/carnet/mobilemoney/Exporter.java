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
        return xlsx(r, bilan, from, to, periodLabel, now, null, null);
    }

    /** Classeur complet ; tresorerie et budgets (facultatifs) ajoutent la feuille « Finances ». */
    public static byte[] xlsx(Analyzer.Result r, Caisse.Bilan bilan, long from, long to, String periodLabel, long now,
                              Finances.Tresorerie tresorerie, Map<String, Long> budgets) throws IOException {
        XlsxWriter w = new XlsxWriter();

        XlsxWriter.Sheet s = w.sheet("Synthèse", 28, 16, 16, 12, 16, 16, 14, 14, 14).noHeader();
        s.row("**Carnet Mobile Money : rapport de caisse");
        s.row("Période", periodLabel);
        s.row("Exporté le", new Date(now));
        if (r.ownerOm != null && !r.ownerOm.isEmpty()) s.row("Titulaire Orange Money", r.ownerOm);
        s.row();
        s.row("**Catégorie", "**Entrées (FCFA)", "**Sorties (FCFA)", "**Opérations", "**Type", "**Dans le résultat");
        for (Map.Entry<String, Caisse.Total> e : bilan.totaux.entrySet()) {
            Caisse.Total t = e.getValue();
            if (t.count == 0 && (e.getKey() == null || Caisse.AGENT.equals(e.getKey()))) continue;
            int effet = Caisse.effetResultat(e.getKey());
            s.row(Caisse.categorieNom(e.getKey()), fcfa(t.entrees), fcfa(t.sorties), t.count,
                    e.getKey() == null ? "" : Caisse.typeNom(e.getKey()), e.getKey() == null ? "à classer" : effet > 0 ? "+" : effet < 0 ? "−" : "non");
        }
        s.row("Frais opérateur", null, fcfa(bilan.frais), null, "Sortie", "−");
        s.row("**Résultat (ventes − achats − charges − frais)", fcfa(bilan.resultat));
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
            es.row(new Date(x.date), x.sens > 0 ? "Entrée" : "Sortie", fcfa(x.montant), Caisse.categorieNom(Caisse.normaliserSaisie(x.categorie, x.sens)), x.libelle,
                    x.annule ? "Annulée" : "Valide");

        XlsxWriter.Sheet v = w.sheet("À vérifier", 17, 14, 40, 100);
        v.row("Date", "Compte", "Raison", "SMS");
        for (Txn u : r.unknown)
            if (u.smsDate >= from && u.smsDate <= to) v.row(new Date(u.smsDate), Txn.opName(u.op), u.reason, u.raw);
        if (tresorerie != null || budgets != null) {
            XlsxWriter.Sheet f = w.sheet("Finances", 32, 18, 18, 14).noHeader();
            if (tresorerie != null) {
                f.row("**Trésorerie au moment de l'export", new Date(now));
                f.row("**Poste", "**Montant (FCFA)", "**Information du");
                for (Finances.Poste p : tresorerie.postes) {
                    f.row(p.nom + (p.manuel ? " (saisi)" : ""), fcfa(p.montant), p.date > 0 ? new Date(p.date) : null);
                }
                f.row("**Total", fcfa(tresorerie.total));
                f.row();
            }
            Finances.Mois m = Finances.mois(bilan);
            f.row("**Dépenses par poste · " + periodLabel);
            f.row("**Poste", "**Dépensé (FCFA)", "**Budget mensuel (FCFA)", "**% du budget");
            Map<String, Long> bud = budgets != null ? budgets : new java.util.HashMap<>();
            for (Finances.Budget b : Finances.budgets(m, bud)) {
                f.row(Caisse.categorieNom(b.poste), fcfa(b.depense), b.budget > 0 ? fcfa(b.budget) : null, b.budget > 0 ? (Object) (long) b.pourcent : null);
            }
            f.row();
            f.row("**Ventes par moyen de paiement");
            for (Map.Entry<String, Long> vm : m.ventesParMoyen.entrySet()) f.row(vm.getKey(), fcfa(vm.getValue()));
            if (!m.meilleursClients.isEmpty()) {
                f.row();
                f.row("**Meilleurs clients", "**Montant (FCFA)", "**Achats");
                for (Finances.Client cl : m.meilleursClients) {
                    f.row(cl.nom != null && !cl.nom.trim().isEmpty() ? cl.nom.trim() : cl.numero, fcfa(cl.montant), (long) cl.operations);
                }
            }
        }
        return w.bytes();
    }

    /**
     * Classeur « Toutes mes caisses » : une ligne par caisse puis le détail par jour, à partir des résumés envoyés
     * par chaque téléphone (montants en centimes dans la réponse du serveur).
     */
    public static byte[] consolide(org.json.JSONObject vue, String periodLabel, long now) throws IOException {
        XlsxWriter w = new XlsxWriter();
        XlsxWriter.Sheet s = w.sheet("Toutes les caisses", 26, 12, 15, 15, 15, 13, 15, 14, 14, 11, 15, 15, 16).noHeader();
        s.row("**Carnet Mobile Money : toutes mes caisses");
        s.row("Période", periodLabel);
        s.row("Exporté le", new Date(now));
        s.row();
        s.row("**Caisse", "**Opérations", "**Ventes (FCFA)", "**Achats (FCFA)", "**Charges (FCFA)", "**Frais (FCFA)", "**Résultat (FCFA)",
                "**Apports perso", "**Retraits perso", "**À classer", "**Solde OM", "**Solde MoMo", "**Trésorerie");
        org.json.JSONArray cs = vue.optJSONArray("caisses");
        long[] tot = new long[9];
        if (cs != null) for (int i = 0; i < cs.length(); i++) {
            org.json.JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            long[] v = {c.optLong("nb_operations"), c.optLong("ventes"), c.optLong("achats"), c.optLong("charges"), c.optLong("frais"),
                    c.optLong("resultat"), c.optLong("apports"), c.optLong("retraits"), c.optLong("a_classer")};
            for (int k = 0; k < v.length; k++) tot[k] += v[k];
            s.row(c.optString("nom", ""), v[0], fcfa(v[1]), fcfa(v[2]), fcfa(v[3]), fcfa(v[4]), fcfa(v[5]), fcfa(v[6]), fcfa(v[7]), v[8],
                    c.isNull("solde_om") ? null : fcfa(c.optLong("solde_om")), c.isNull("solde_momo") ? null : fcfa(c.optLong("solde_momo")),
                    c.isNull("tresorerie") ? null : fcfa(c.optLong("tresorerie")));
        }
        s.row("**Total", tot[0], fcfa(tot[1]), fcfa(tot[2]), fcfa(tot[3]), fcfa(tot[4]), fcfa(tot[5]), fcfa(tot[6]), fcfa(tot[7]), tot[8]);
        s.row();
        s.row("**Comment lire");
        s.row("Résultat", "Ventes − achats − charges − frais opérateur, d'après le classement fait sur chaque téléphone.");
        s.row("Soldes", "Dernier solde lu dans les SMS de chaque caisse, jusqu'à la fin de la période.");
        s.row("Mise à jour", "Chaque téléphone envoie ses chiffres à chaque sauvegarde : un téléphone resté sans réseau peut être en retard.");

        XlsxWriter.Sheet j = w.sheet("Par jour", 12, 26, 12, 15, 15, 15, 13, 15, 11, 15, 15, 15);
        j.row("Jour", "Caisse", "Opérations", "Ventes (FCFA)", "Achats (FCFA)", "Charges (FCFA)", "Frais (FCFA)", "Résultat (FCFA)",
                "À classer", "Solde OM", "Solde MoMo", "Espèces comptées");
        org.json.JSONArray js = vue.optJSONArray("jours");
        if (js != null) for (int i = 0; i < js.length(); i++) {
            org.json.JSONObject x = js.optJSONObject(i);
            if (x == null) continue;
            j.row(x.optString("jour", ""), x.optString("caisse", ""), x.optLong("nb_operations"), fcfa(x.optLong("ventes")),
                    fcfa(x.optLong("achats")), fcfa(x.optLong("charges")), fcfa(x.optLong("frais")), fcfa(x.optLong("resultat")),
                    x.optLong("a_classer"), x.isNull("solde_om") ? null : fcfa(x.optLong("solde_om")),
                    x.isNull("solde_momo") ? null : fcfa(x.optLong("solde_momo")),
                    x.isNull("especes_compte") ? null : fcfa(x.optLong("especes_compte")));
        }
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
