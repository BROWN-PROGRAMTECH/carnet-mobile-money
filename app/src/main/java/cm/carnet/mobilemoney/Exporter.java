package cm.carnet.mobilemoney;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Contenu des exports : classeur Excel (par défaut), CSV, SMS anonymisés. Classe Java pure.
 * Classeur : Tableau de bord (résultat, catégories × comptes, rapprochement), Opérations (un seul journal OM, MoMo et espèces,
 * avec filtres), Par jour (état de caisse journalier) et Anomalies. Les totaux sont des formules : une catégorie corrigée
 * dans Excel les met à jour.
 */
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

    // ======================================================================
    // Classeur de caisse : Tableau de bord · Opérations · Par jour · Anomalies
    // ======================================================================

    static final String TDB = "Tableau de bord", OPS = "Opérations", JOURS = "Par jour", ANOM = "Anomalies";
    static final String A_CLASSER = "À classer", A_VERIFIER = "À vérifier", ANNULEE = "Annulée", OK = "OK";
    static final String ESPECES = "Espèces";

    // Couleurs
    static final String GREEN = "0F6B4F", GREEN_BG = "E3F1E8", INK = "1D2321", MUTED = "66706B", LINE = "D9D9D9",
            ORANGE = "8A5300", ORANGE_BG = "FFF2CC", RED = "A3322A", RED_BG = "FCE4E4", GREY_BG = "F3F3F1";
    static final String MONEY = "#,##0;-#,##0;0", MONEY_RED = "#,##0;[Red]-#,##0;0", DAY = "dd/mm/yyyy", TIME = "hh:mm";

    /** Une ligne de l'onglet Opérations (SMS Orange Money, SMS MoMo ou mouvement d'espèces). */
    static final class Op {
        long millis;
        String compte, type, categorie, motif, contrepartie, numero, ref, remarque = "", classe = "";
        Double entree, sortie, frais, solde;
        boolean annule, verifier;
        /** Effet sur les espèces d'une opération client en mode agent : +1 dépôt, −1 retrait, 0 aucun. */
        int effetEspeces;
        double day() { return XlsxWriter.serialDay(millis); }
        String statut() { return annule ? ANNULEE : A_CLASSER.equals(categorie) ? A_CLASSER : verifier ? A_VERIFIER : OK; }
        boolean compte() { return !annule; }
        double in() { return entree == null ? 0 : entree; }
        double out() { return sortie == null ? 0 : sortie; }
        double fr() { return frais == null ? 0 : frais; }
    }

    static double d(Long cents) { return cents == null ? 0 : cents / 100.0; }

    static String nonVide(String s) { return s == null ? "" : s; }

    /** Toutes les opérations de la période, de la plus ancienne à la plus récente. */
    static List<Op> operations(Caisse.Bilan bilan) {
        List<Op> ops = new ArrayList<>();
        for (Caisse.Ligne l : bilan.lignes) {
            Txn t = l.sms;
            Op o = new Op();
            o.millis = t.date;
            o.compte = Txn.opName(t.op);
            o.type = t.typeLabel();
            o.categorie = Caisse.categorieNom(l.categorie);
            o.motif = nonVide(l.motif);
            o.contrepartie = nonVide(t.nom);
            o.numero = nonVide(t.numero);
            o.ref = nonVide(t.txnId);
            if (t.sens > 0) o.entree = t.montant / 100.0; else o.sortie = t.montant / 100.0;
            if (t.frais != 0) o.frais = t.frais / 100.0;
            Long solde = t.solde != null ? t.solde : t.apres;
            if (solde != null) o.solde = solde / 100.0;
            StringBuilder rem = new StringBuilder();
            for (String f : t.flags) {
                if (f.startsWith("!")) o.verifier = true;
                rem.append(rem.length() > 0 ? " ; " : "").append(f.substring(1));
            }
            o.remarque = rem.toString();
            o.classe = l.categorie == null ? "" : l.auto ? "Auto" : "Manuel";
            if (Caisse.AGENT.equals(l.categorie)) o.effetEspeces = Caisse.effetEspecesAgent(t);
            ops.add(o);
        }
        for (Caisse.Saisie x : bilan.mouvementsEspeces) {
            Op o = new Op();
            o.millis = x.date;
            o.compte = ESPECES;
            o.type = x.sens > 0 ? "Entrée espèces" : "Sortie espèces";
            o.categorie = Caisse.categorieNom(x.categorie);
            o.motif = nonVide(x.libelle);
            o.contrepartie = "";
            o.numero = "";
            o.ref = nonVide(x.ref);
            if (x.sens > 0) o.entree = x.montant / 100.0; else o.sortie = x.montant / 100.0;
            o.annule = x.annule;
            o.classe = x.categorie == null ? "" : "Manuel";
            if (x.annule) o.remarque = "Mouvement annulé : ne compte pas dans les totaux";
            ops.add(o);
        }
        ops.sort((a, b) -> Long.compare(a.millis, b.millis));
        return ops;
    }

    /** Somme sur les opérations comptées (non annulées) qui vérifient le filtre. */
    interface Filtre { boolean ok(Op o); }

    static double somme(List<Op> ops, Filtre f, int quoi) {
        double s = 0;
        for (Op o : ops) if (o.compte() && f.ok(o)) s += quoi == 0 ? o.in() : quoi == 1 ? o.out() : o.fr();
        return s;
    }

    static int nombre(List<Op> ops, Filtre f) {
        int n = 0;
        for (Op o : ops) if (f.ok(o)) n++;
        return n;
    }

    /** Formules SUMIFS / COUNTIFS sur l'onglet Opérations (colonnes entières : suivent les lignes ajoutées). */
    static String col(String c) { return XlsxWriter.quote(OPS) + "!$" + c + ":$" + c; }

    static String sumifs(String c, String... crit) {
        StringBuilder b = new StringBuilder("SUMIFS(").append(col(c));
        for (int i = 0; i < crit.length; i += 2) b.append(",").append(col(crit[i])).append(",").append(crit[i + 1]);
        return b.append(",").append(col("M")).append(",\"<>").append(ANNULEE).append("\")").toString();
    }

    static String countifs(String... crit) {
        StringBuilder b = new StringBuilder("COUNTIFS(");
        for (int i = 0; i < crit.length; i += 2) b.append(i > 0 ? "," : "").append(col(crit[i])).append(",").append(crit[i + 1]);
        return b.append(")").toString();
    }

    static String q(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }

    /** Classeur de caisse : Tableau de bord, Opérations, Par jour, Anomalies. */
    public static byte[] xlsx(Analyzer.Result r, Caisse.Bilan bilan, long from, long to, String periodLabel, long now) throws IOException {
        XlsxWriter w = new XlsxWriter();
        List<Op> ops = operations(bilan);

        // ---------- Styles ----------
        XlsxWriter.Style title = new XlsxWriter.Style().bold().size(16).color(GREEN).middle();
        XlsxWriter.Style sub = new XlsxWriter.Style().color(MUTED);
        XlsxWriter.Style section = new XlsxWriter.Style().bold().size(12).color(GREEN).bottomLine(GREEN);
        XlsxWriter.Style head = new XlsxWriter.Style().bold().color("FFFFFF").fill(GREEN).border(GREEN).middle().wrap();
        XlsxWriter.Style headNum = head.copy().align("right");
        XlsxWriter.Style label = new XlsxWriter.Style().border(LINE).indent(1);
        XlsxWriter.Style labelBold = new XlsxWriter.Style().bold().fill(GREY_BG).border(LINE).indent(1);
        XlsxWriter.Style money = new XlsxWriter.Style().fmt(MONEY_RED).border(LINE);
        XlsxWriter.Style moneyBold = money.copy().bold().fill(GREY_BG);
        XlsxWriter.Style count = new XlsxWriter.Style().fmt("0").border(LINE).align("right");
        XlsxWriter.Style countBold = count.copy().bold().fill(GREY_BG);
        XlsxWriter.Style resultLabel = new XlsxWriter.Style().bold().size(13).fill(GREEN_BG).border(GREEN).indent(1).middle();
        XlsxWriter.Style resultValue = new XlsxWriter.Style().bold().size(13).fmt(MONEY_RED).fill(GREEN_BG).border(GREEN).middle();
        XlsxWriter.Style note = new XlsxWriter.Style().color(MUTED).italic().wrap().top();
        XlsxWriter.Style alert = new XlsxWriter.Style().bold().color(ORANGE);
        XlsxWriter.Style text = new XlsxWriter.Style();
        XlsxWriter.Style day = new XlsxWriter.Style().fmt(DAY).align("left");
        XlsxWriter.Style time = new XlsxWriter.Style().fmt(TIME).align("left");
        XlsxWriter.Style amount = new XlsxWriter.Style().fmt(MONEY);
        XlsxWriter.Style wrapTop = new XlsxWriter.Style().wrap().top();
        XlsxWriter.Style topText = new XlsxWriter.Style().top();
        XlsxWriter.Style dayTop = day.copy().top(), timeTop = time.copy().top();
        XlsxWriter.Style dayBold = new XlsxWriter.Style().fmt(DAY).align("left").border(LINE);
        XlsxWriter.Style totalLabel = new XlsxWriter.Style().bold().fill(GREY_BG).border(LINE);

        XlsxWriter.Style warnFill = new XlsxWriter.Style().fill(ORANGE_BG).color(ORANGE);
        XlsxWriter.Style badFill = new XlsxWriter.Style().fill(RED_BG).color(RED);
        XlsxWriter.Style goodFill = new XlsxWriter.Style().fill(GREEN_BG).color(GREEN);
        XlsxWriter.Style greyed = new XlsxWriter.Style().color("9A9A9A").strike();

        // Les quatre onglets, dans l'ordre d'affichage.
        XlsxWriter.Sheet tb = w.sheet(TDB, 36, 15, 15, 15, 15, 15, 15, 15);
        XlsxWriter.Sheet op = w.sheet(OPS, 11, 7, 13, 19, 16, 28, 22, 12, 12, 12, 9, 13, 11, 8, 20, 40);
        XlsxWriter.Sheet pj = w.sheet(JOURS, 12, 13, 13, 10, 13, 13, 13, 13, 13, 13, 13, 13, 12, 10);
        XlsxWriter.Sheet an = w.sheet(ANOM, 11, 7, 13, 18, 42, 90);

        // ---------- Opérations ----------
        op.tab(GREEN).freeze(1, 0).landscape().fitWidth().printTitles(1);
        String[] entetes = {"Date", "Heure", "Compte", "Type", "Catégorie", "Motif", "Contrepartie", "Numéro", "Entrée", "Sortie", "Frais",
                "Solde après", "Statut", "Classé", "Réf.", "Remarque"};
        Object[] ligneEntete = new Object[entetes.length];
        for (int k = 0; k < entetes.length; k++) ligneEntete[k] = XlsxWriter.c(entetes[k], k >= 8 && k <= 11 ? headNum : head);
        op.row(ligneEntete);
        op.height(1, 20);
        for (Op o : ops) {
            int n = op.next();
            Object statut = o.annule ? ANNULEE
                    : XlsxWriter.f("IF($E" + n + "=" + q(A_CLASSER) + "," + q(A_CLASSER) + "," + q(o.verifier ? A_VERIFIER : OK) + ")", o.statut(), text);
            op.row(XlsxWriter.c(o.day(), day), XlsxWriter.c(XlsxWriter.serialTime(o.millis), time), o.compte, o.type, o.categorie, o.motif,
                    o.contrepartie, o.numero, o.entree == null ? null : XlsxWriter.c(o.entree, amount),
                    o.sortie == null ? null : XlsxWriter.c(o.sortie, amount), o.frais == null ? null : XlsxWriter.c(o.frais, amount),
                    o.solde == null ? null : XlsxWriter.c(o.solde, amount), statut, o.classe, o.ref, o.remarque.isEmpty() ? null : XlsxWriter.c(o.remarque, wrapTop));
        }
        int lastOp = Math.max(2, op.next() - 1);
        op.table("Operations", 1, 16);
        String zone = "A2:P" + lastOp;
        op.whenTrue(zone, "$M2=" + q(ANNULEE), greyed);
        op.whenTrue(zone, "$M2=" + q(A_VERIFIER), badFill);
        op.whenTrue(zone, "$M2=" + q(A_CLASSER), warnFill);
        boolean agent = false;
        for (Op o : ops) if (Caisse.categorieNom(Caisse.AGENT).equals(o.categorie)) agent = true;
        List<String> cats = new ArrayList<>();
        for (String c : Caisse.CATEGORIES) cats.add(Caisse.categorieNom(c));
        if (agent) cats.add(Caisse.categorieNom(Caisse.AGENT));
        cats.add(A_CLASSER);
        op.list("E2:E" + (lastOp + 500), cats.toArray(new String[0]));

        // ---------- Tableau de bord ----------
        SimpleDateFormat when = new SimpleDateFormat("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE);
        tb.tab(GREEN).noGridlines().fitWidth();
        int n = tb.row(XlsxWriter.c("Carnet Mobile Money · État de caisse", title));
        tb.merge("A1:H1").height(n, 28);
        n = tb.row(XlsxWriter.c("Période : " + periodLabel + "   ·   Exporté le " + when.format(new Date(now))
                + (r.ownerOm != null && !r.ownerOm.isEmpty() ? "   ·   Titulaire Orange Money : " + r.ownerOm : ""), sub));
        tb.merge("A" + n + ":H" + n);
        tb.row();

        // Résultat
        sectionRow(tb, "Résultat de la période", section);
        String vente = q(Caisse.categorieNom(Caisse.VENTE)), paie = q(Caisse.categorieNom(Caisse.PAIEMENT));
        double venteNet = somme(ops, o -> "Vente".equals(o.categorie), 0) - somme(ops, o -> "Vente".equals(o.categorie), 1);
        double paieNet = somme(ops, o -> "Paiement".equals(o.categorie), 1) - somme(ops, o -> "Paiement".equals(o.categorie), 0);
        double frais = somme(ops, o -> true, 2);
        int rV = tb.row(XlsxWriter.c("Ventes (encaissements nets)", label),
                XlsxWriter.f(sumifs("I", "E", vente) + "-" + sumifs("J", "E", vente), venteNet, money));
        int rP = tb.row(XlsxWriter.c("Paiements (achats, charges, nets)", label),
                XlsxWriter.f(sumifs("J", "E", paie) + "-" + sumifs("I", "E", paie), paieNet, money));
        int rF = tb.row(XlsxWriter.c("Frais opérateur", label), XlsxWriter.f(sumifs("K"), frais, money));
        int rR = tb.row(XlsxWriter.c("RÉSULTAT", resultLabel), XlsxWriter.f("B" + rV + "-B" + rP + "-B" + rF, venteNet - paieNet - frais, resultValue),
                XlsxWriter.c("Ventes − paiements − frais. Les dons, mouvements personnels et opérations clients n'entrent pas dans le résultat.", note));
        tb.height(rR, 30).merge("C" + rR + ":H" + rR);
        int nbAClasser = nombre(ops, o -> A_CLASSER.equals(o.statut()));
        String cA = "COUNTIF(" + col("M") + "," + q(A_CLASSER) + ")";
        n = tb.row(XlsxWriter.f("IF(" + cA + ">0," + cA + "&\" opération(s) encore à classer : résultat provisoire.\",\"Toutes les opérations sont classées.\")",
                nbAClasser > 0 ? nbAClasser + " opération(s) encore à classer : résultat provisoire." : "Toutes les opérations sont classées.", alert));
        tb.merge("A" + n + ":H" + n);
        tb.row();

        // Par catégorie et par compte
        sectionRow(tb, "Mouvements par catégorie et par compte (entrées − sorties, FCFA)", section);
        String[] comptes = {"Orange Money", "MTN MoMo", ESPECES};
        int rH = tb.row(XlsxWriter.c("Catégorie", head), XlsxWriter.c(comptes[0], headNum), XlsxWriter.c(comptes[1], headNum),
                XlsxWriter.c(comptes[2], headNum), XlsxWriter.c("Total", headNum), XlsxWriter.c("Opérations", headNum));
        int first = tb.next();
        double[] totCol = new double[4];
        for (String cat : cats) {
            int row = tb.next();
            Object[] cells = new Object[6];
            cells[0] = XlsxWriter.c(cat, label);
            double tot = 0;
            for (int k = 0; k < 3; k++) {
                final String cpt = comptes[k];
                double v = somme(ops, o -> cat.equals(o.categorie) && cpt.equals(o.compte), 0) - somme(ops, o -> cat.equals(o.categorie) && cpt.equals(o.compte), 1);
                String c = XlsxWriter.col(k + 1);
                cells[k + 1] = XlsxWriter.f(sumifs("I", "E", "$A" + row, "C", c + "$" + rH) + "-" + sumifs("J", "E", "$A" + row, "C", c + "$" + rH), v, money);
                tot += v;
                totCol[k] += v;
            }
            cells[4] = XlsxWriter.f("SUM(B" + row + ":D" + row + ")", tot, moneyBold);
            cells[5] = XlsxWriter.f(countifs("E", "$A" + row, "M", "\"<>" + ANNULEE + "\""), nombre(ops, o -> o.compte() && cat.equals(o.categorie)), count);
            tb.row(cells);
        }
        int rFr = tb.next();
        Object[] fr = new Object[6];
        fr[0] = XlsxWriter.c("Frais opérateur", label);
        double totFr = 0;
        for (int k = 0; k < 3; k++) {
            final String cpt = comptes[k];
            double v = -somme(ops, o -> cpt.equals(o.compte), 2);
            fr[k + 1] = XlsxWriter.f("-" + sumifs("K", "C", XlsxWriter.col(k + 1) + "$" + rH), v, money);
            totFr += v;
            totCol[k] += v;
        }
        fr[4] = XlsxWriter.f("SUM(B" + rFr + ":D" + rFr + ")", totFr, moneyBold);
        fr[5] = XlsxWriter.c(null, count);
        tb.row(fr);
        int rT = tb.next();
        tb.row(XlsxWriter.c("Mouvement net du compte", labelBold),
                XlsxWriter.f("SUM(B" + first + ":B" + rFr + ")", totCol[0], moneyBold),
                XlsxWriter.f("SUM(C" + first + ":C" + rFr + ")", totCol[1], moneyBold),
                XlsxWriter.f("SUM(D" + first + ":D" + rFr + ")", totCol[2], moneyBold),
                XlsxWriter.f("SUM(E" + first + ":E" + rFr + ")", totCol[0] + totCol[1] + totCol[2], moneyBold),
                XlsxWriter.f("SUM(F" + first + ":F" + (rFr - 1) + ")", nombre(ops, Op::compte), countBold));
        tb.whenTrue("A" + (rFr - 1) + ":F" + (rFr - 1), "$F" + (rFr - 1) + ">0", warnFill);
        tb.row();

        // Rapprochement des soldes
        sectionRow(tb, "Rapprochement des soldes", section);
        tb.row(XlsxWriter.c("Compte", head), XlsxWriter.c("Solde d'ouverture", headNum), XlsxWriter.c("Entrées", headNum),
                XlsxWriter.c("Sorties", headNum), XlsxWriter.c("Frais", headNum), XlsxWriter.c("Solde théorique", headNum),
                XlsxWriter.c("Solde réel", headNum), XlsxWriter.c("Écart", headNum));
        int firstC = tb.next();
        for (Caisse.Compte c : bilan.comptes)
            soldeRow(tb, Txn.opName(c.op) + (c.agent ? " (agent)" : ""), c.ouverture, d(c.entrees), d(c.sorties), d(c.frais), c.reel,
                    label, money);
        Caisse.Especes e = bilan.especes;
        soldeRow(tb, ESPECES, e.ouverture, d(e.entrees + e.depotsClients), d(e.sorties + e.retraitsClients), 0, e.compte, label, money);
        int lastC = tb.next() - 1;
        String ec = "H" + firstC + ":H" + lastC;
        tb.whenTrue(ec, "AND(ISNUMBER(H" + firstC + "),ABS(H" + firstC + ")>=1)", badFill);
        tb.whenTrue(ec, "AND(ISNUMBER(H" + firstC + "),ABS(H" + firstC + ")<1)", goodFill);
        String noteEsp = e.ouverture == null
                ? "Espèces : saisissez le fond de caisse et le comptage de clôture dans l'application pour contrôler la caisse."
                : "Espèces : ouverture = " + e.origineOuverture + " ; solde réel = comptage à la clôture"
                + (e.depotsClients + e.retraitsClients > 0 ? " ; entrées et sorties comprennent les dépôts et retraits clients (mode agent)." : ".");
        n = tb.row(XlsxWriter.c("Orange Money et MoMo : un écart signale un SMS manquant ou mal lu (voir l'onglet Anomalies). " + noteEsp, note));
        tb.merge("A" + n + ":H" + n).height(n, 32);
        tb.row();

        // À contrôler
        sectionRow(tb, "À contrôler", section);
        int nbVerif = nombre(ops, o -> A_VERIFIER.equals(o.statut()));
        List<Object[]> anomalies = anomalies(r, from, to);
        int c1 = tb.row(XlsxWriter.c("Opérations à classer", label), XlsxWriter.f(cA, nbAClasser, count),
                XlsxWriter.c("Filtrez la colonne Statut de l'onglet Opérations sur « À classer ».", sub));
        int c2 = tb.row(XlsxWriter.c("Opérations à vérifier (solde incohérent)", label),
                XlsxWriter.f("COUNTIF(" + col("M") + "," + q(A_VERIFIER) + ")", nbVerif, count),
                XlsxWriter.c("Filtrez la colonne Statut sur « À vérifier ».", sub));
        int c3 = tb.row(XlsxWriter.c("Anomalies (SMS non compris, ruptures)", label),
                XlsxWriter.f("MAX(0,COUNTIF(" + XlsxWriter.quote(ANOM) + "!$D:$D,\"?*\")-1)", anomalies.size(), count),
                XlsxWriter.c("Détail dans l'onglet Anomalies.", sub));
        tb.whenValue("B" + c1 + ":B" + c3, "greaterThan", "0", warnFill);
        tb.row();

        // Mode d'emploi
        sectionRow(tb, "Comment utiliser ce fichier", section);
        String[] aide = {
                "Opérations : toutes les opérations (Orange Money, MoMo, espèces) dans un seul tableau. Utilisez les flèches des en-têtes pour filtrer par compte, catégorie, statut ou date.",
                "Corriger une catégorie : choisissez-la dans la liste de la colonne Catégorie. Ce tableau de bord et l'onglet Par jour se recalculent.",
                "Couleurs : orange = à classer, rouge = à vérifier, barré = mouvement d'espèces annulé (ne compte pas).",
                "Les montants viennent des SMS de l'opérateur : ne les modifiez pas. Par jour : l'état de caisse journée par journée.",
        };
        for (String a : aide) {
            n = tb.row(XlsxWriter.c("•  " + a, note.copy().italic()));
            tb.merge("A" + n + ":H" + n).height(n, 30);
        }

        // ---------- Par jour ----------
        parJour(pj, r, bilan, ops, from, to, head, headNum, dayBold, money, moneyBold, count, countBold, totalLabel, badFill, warnFill);

        // ---------- Anomalies ----------
        an.tab("C0504D").freeze(1, 0).landscape().fitWidth().printTitles(1);
        an.row(XlsxWriter.c("Date", head), XlsxWriter.c("Heure", head), XlsxWriter.c("Compte", head), XlsxWriter.c("Problème", head),
                XlsxWriter.c("Détail", head), XlsxWriter.c("SMS", head));
        if (anomalies.isEmpty()) {
            an.row(XlsxWriter.c("Aucune anomalie sur la période.", sub));
        } else {
            for (Object[] a : anomalies) {
                long ms = (Long) a[0];
                an.row(XlsxWriter.c(XlsxWriter.serialDay(ms), dayTop), XlsxWriter.c(XlsxWriter.serialTime(ms), timeTop),
                        XlsxWriter.c(a[1], topText), XlsxWriter.c(a[2], topText), XlsxWriter.c(a[3], wrapTop), XlsxWriter.c(a[4], wrapTop));
            }
            an.autoFilter();
        }
        return w.bytes();
    }

    static void sectionRow(XlsxWriter.Sheet s, String titre, XlsxWriter.Style section) {
        Object[] row = new Object[8];
        row[0] = XlsxWriter.c(titre.toUpperCase(Locale.FRANCE), section);
        for (int i = 1; i < 8; i++) row[i] = XlsxWriter.c(null, section);
        int n = s.row(row);
        s.height(n, 22);
    }

    static void soldeRow(XlsxWriter.Sheet s, String nom, Long ouverture, double entrees, double sorties, double frais, Long reel,
                         XlsxWriter.Style label, XlsxWriter.Style money) {
        int n = s.next();
        Double theo = ouverture == null ? null : d(ouverture) + entrees - sorties - frais;
        Double ecart = theo != null && reel != null ? d(reel) - theo : null;
        s.row(XlsxWriter.c(nom, label),
                XlsxWriter.c(ouverture == null ? null : d(ouverture), money),
                XlsxWriter.c(entrees, money), XlsxWriter.c(sorties, money), XlsxWriter.c(frais, money),
                XlsxWriter.f("IF(B" + n + "=\"\",\"\",B" + n + "+C" + n + "-D" + n + "-E" + n + ")", theo == null ? "" : theo, money),
                XlsxWriter.c(reel == null ? null : d(reel), money),
                XlsxWriter.f("IF(OR(F" + n + "=\"\",G" + n + "=\"\"),\"\",G" + n + "-F" + n + ")", ecart == null ? "" : ecart, money.copy().bold()));
    }

    /** SMS non compris et ruptures de la chaîne des soldes : [date, compte, problème, détail, SMS], du plus ancien au plus récent. */
    static List<Object[]> anomalies(Analyzer.Result r, long from, long to) {
        List<Object[]> out = new ArrayList<>();
        for (Txn u : r.unknown)
            if (u.smsDate >= from && u.smsDate <= to)
                out.add(new Object[] {u.smsDate, Txn.opName(u.op), "SMS non compris", nonVide(u.reason), nonVide(u.raw)});
        for (Txn t : r.tx) {
            if (t.date < from || t.date > to) continue;
            StringBuilder b = new StringBuilder();
            for (String f : t.flags) if (f.startsWith("!")) b.append(b.length() > 0 ? " ; " : "").append(f.substring(1));
            if (b.length() > 0)
                out.add(new Object[] {t.date, Txn.opName(t.op), t.isBalanceCheck() ? "Écart à la consultation" : "Solde incohérent",
                        b.toString(), nonVide(t.raw)});
        }
        out.sort((a, b) -> Long.compare((Long) a[0], (Long) b[0]));
        return out;
    }

    /** État de caisse journée par journée. */
    static void parJour(XlsxWriter.Sheet pj, Analyzer.Result r, Caisse.Bilan bilan, List<Op> ops, long from, long to,
                        XlsxWriter.Style head, XlsxWriter.Style headNum, XlsxWriter.Style day, XlsxWriter.Style money,
                        XlsxWriter.Style moneyBold, XlsxWriter.Style count, XlsxWriter.Style countBold, XlsxWriter.Style totalLabel,
                        XlsxWriter.Style badFill, XlsxWriter.Style warnFill) {
        pj.tab("4F81BD").freeze(1, 1).landscape().fitWidth().printTitles(1);
        pj.height(1, 32);
        pj.row(XlsxWriter.c("Jour", head), XlsxWriter.c("Ventes", headNum), XlsxWriter.c("Paiements", headNum), XlsxWriter.c("Frais", headNum),
                XlsxWriter.c("Résultat", headNum), XlsxWriter.c("Total entrées", headNum), XlsxWriter.c("Total sorties", headNum),
                XlsxWriter.c("Solde fin OM", headNum), XlsxWriter.c("Solde fin MoMo", headNum), XlsxWriter.c("Ouverture espèces", headNum),
                XlsxWriter.c("Espèces théoriques", headNum), XlsxWriter.c("Espèces comptées", headNum), XlsxWriter.c("Écart espèces", headNum),
                XlsxWriter.c("À classer", headNum));

        // Jours à afficher : ceux qui ont des opérations ou une journée de caisse dans la période.
        TreeMap<Double, Caisse.Jour> jours = new TreeMap<>();
        String jFrom = from <= 0 ? "" : Caisse.jourDe(from), jTo = to == Long.MAX_VALUE ? "9999" : Caisse.jourDe(to);
        for (Caisse.Jour j : bilan.jours)
            if (j.jour.compareTo(jFrom) >= 0 && j.jour.compareTo(jTo) <= 0) jours.put(XlsxWriter.serialDay(Caisse.debutJour(j.jour)), j);
        TreeSet<Double> days = new TreeSet<>(jours.keySet());
        for (Op o : ops) days.add(o.day());

        // Dernier solde connu de chaque compte à la fin de chaque jour (SMS de toutes dates, consultations comprises).
        Map<String, TreeMap<Double, Double>> soldes = new LinkedHashMap<>();
        for (String opName : new String[] {"OM", "MOMO"}) soldes.put(opName, new TreeMap<>());
        List<Txn> asc = new ArrayList<>(r.tx);
        Collections.reverse(asc);
        for (Txn t : asc) {
            Long s = t.solde != null ? t.solde : t.apres;
            TreeMap<Double, Double> m = soldes.get(t.op);
            if (s != null && m != null) m.put(XlsxWriter.serialDay(t.date), s / 100.0);
        }
        // Comptages antérieurs à la période : ouverture des espèces du premier jour sans fond.
        Double dernierComptage = null;
        for (Caisse.Jour j : bilan.jours) if (j.jour.compareTo(jFrom) < 0 && j.compte != null) dernierComptage = j.compte / 100.0;

        int first = pj.next();
        for (double dd : days) {
            int n = pj.next();
            String a = "$A" + n;
            Filtre ce = o -> o.day() == dd;
            double ventes = somme(ops, o -> ce.ok(o) && "Vente".equals(o.categorie), 0) - somme(ops, o -> ce.ok(o) && "Vente".equals(o.categorie), 1);
            double paie = somme(ops, o -> ce.ok(o) && "Paiement".equals(o.categorie), 1) - somme(ops, o -> ce.ok(o) && "Paiement".equals(o.categorie), 0);
            double frais = somme(ops, ce, 2);

            Caisse.Jour j = jours.get(dd);
            Double ouverture = j != null && j.fond != null ? Double.valueOf(j.fond / 100.0) : dernierComptage;
            Double compte = j != null && j.compte != null ? Double.valueOf(j.compte / 100.0) : null;
            Double theo = null;
            if (ouverture != null) {
                double t = ouverture + somme(ops, o -> ce.ok(o) && ESPECES.equals(o.compte), 0) - somme(ops, o -> ce.ok(o) && ESPECES.equals(o.compte), 1);
                for (Op o : ops) if (ce.ok(o) && o.effetEspeces != 0) t += o.effetEspeces * (o.in() + o.out());
                theo = t;
            }
            if (compte != null) dernierComptage = compte;
            Double ecart = theo != null && compte != null ? compte - theo : null;
            Map.Entry<Double, Double> om = soldes.get("OM").floorEntry(dd), momo = soldes.get("MOMO").floorEntry(dd);
            String vente = q("Vente"), pai = q("Paiement");

            pj.row(XlsxWriter.c(dd, day),
                    XlsxWriter.f(sumifs("I", "E", vente, "A", a) + "-" + sumifs("J", "E", vente, "A", a), ventes, money),
                    XlsxWriter.f(sumifs("J", "E", pai, "A", a) + "-" + sumifs("I", "E", pai, "A", a), paie, money),
                    XlsxWriter.f(sumifs("K", "A", a), frais, money),
                    XlsxWriter.f("B" + n + "-C" + n + "-D" + n, ventes - paie - frais, money.copy().bold()),
                    XlsxWriter.f(sumifs("I", "A", a), somme(ops, ce, 0), money),
                    XlsxWriter.f(sumifs("J", "A", a), somme(ops, ce, 1), money),
                    XlsxWriter.c(om == null ? null : om.getValue(), money),
                    XlsxWriter.c(momo == null ? null : momo.getValue(), money),
                    XlsxWriter.c(ouverture, money),
                    XlsxWriter.c(theo, money),
                    XlsxWriter.c(compte, money),
                    XlsxWriter.f("IF(OR(K" + n + "=\"\",L" + n + "=\"\"),\"\",L" + n + "-K" + n + ")", ecart == null ? "" : ecart, money.copy().bold()),
                    XlsxWriter.f(countifs("A", a, "M", q(A_CLASSER)), nombre(ops, o -> ce.ok(o) && A_CLASSER.equals(o.statut())), count));
        }
        int last = pj.next() - 1;
        if (days.isEmpty()) {
            pj.row(XlsxWriter.c("Aucune opération sur la période.", count.copy().align("left")));
            return;
        }
        int t = pj.next();
        Object[] tot = new Object[14];
        tot[0] = XlsxWriter.c("Total", totalLabel);
        for (int k = 1; k < 14; k++) {
            String c = XlsxWriter.col(k);
            boolean somme = k <= 6 || k == 13;
            Object cached = null;
            if (somme) {
                double s = 0;
                for (Op o : ops) {
                    if (k == 1 && "Vente".equals(o.categorie) && o.compte()) s += o.in() - o.out();
                    if (k == 2 && "Paiement".equals(o.categorie) && o.compte()) s += o.out() - o.in();
                    if (k == 3 && o.compte()) s += o.fr();
                    if (k == 5 && o.compte()) s += o.in();
                    if (k == 6 && o.compte()) s += o.out();
                    if (k == 13 && A_CLASSER.equals(o.statut())) s += 1;
                }
                if (k == 4) s = somme(ops, o -> "Vente".equals(o.categorie), 0) - somme(ops, o -> "Vente".equals(o.categorie), 1)
                        - (somme(ops, o -> "Paiement".equals(o.categorie), 1) - somme(ops, o -> "Paiement".equals(o.categorie), 0))
                        - somme(ops, o -> true, 2);
                cached = s;
            }
            tot[k] = somme ? XlsxWriter.f("SUM(" + c + first + ":" + c + last + ")", cached, k == 13 ? countBold : moneyBold)
                    : XlsxWriter.c(null, moneyBold);
        }
        pj.row(tot);
        pj.whenTrue("M" + first + ":M" + last, "AND(ISNUMBER(M" + first + "),ABS(M" + first + ")>=1)", badFill);
        pj.whenValue("N" + first + ":N" + last, "greaterThan", "0", warnFill);
        if (t > 0) pj.height(t, 18);
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
