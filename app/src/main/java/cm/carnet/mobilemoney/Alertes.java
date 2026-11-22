package cm.carnet.mobilemoney;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Alertes WhatsApp (version 0.12) : ce que le téléphone d'une caisse signale au patron.
 * Chaque alerte porte une clé unique (le serveur n'envoie jamais deux fois la même) et les paramètres du modèle
 * WhatsApp validé chez Meta :
 *  - bilan_du_soir   : {{1}} date, {{2}} caisse, {{3}} ventes, {{4}} dépenses, {{5}} résultat, {{6}} soldes, {{7}} à classer
 *  - ecart_caisse    : {{1}} caisse, {{2}} date, {{3}} compté, {{4}} attendu, {{5}} écart
 *  - alerte_compte   : {{1}} compte, {{2}} caisse, {{3}} détail (SMS manquant, solde bas)
 *  - echeances_dettes: {{1}} date, {{2}} liste des remboursements attendus ou à faire
 * Aucun nom de client ni texte de SMS n'est envoyé, sauf le nom des personnes des dettes (saisi par l'utilisateur).
 * Classe Java pure, testable sur ordinateur. Montants en centimes.
 */
public final class Alertes {
    private Alertes() {}

    public static final class Evenement {
        public String type, cle;
        public final List<String> params = new ArrayList<>();

        Evenement(String type, String cle, String... p) {
            this.type = type;
            this.cle = cle;
            params.addAll(Arrays.asList(p));
        }
    }

    /** Réglages (copie de alerte_pref). Seuils en FCFA. */
    public static final class Reglages {
        public boolean bilan = true, ecart = true, chaine = true, solde = true, dettes = true;
        public int bilanHeure = 20;
        public long seuilOm = 5000, seuilMomo = 5000;
    }

    static String fcfa(long centimes) {
        return SmsParser.fmt(centimes) + " FCFA";
    }

    static String signe(long centimes) {
        return (centimes > 0 ? "+" : centimes < 0 ? "−" : "") + SmsParser.fmt(Math.abs(centimes)) + " FCFA";
    }

    static String date(long t) {
        return new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date(t));
    }

    /** Bilan du jour d'une caisse (à envoyer une fois, après l'heure choisie). */
    public static Evenement bilan(String caisseId, String caisseNom, String jour, Caisse.Bilan b) {
        long depenses = b.total(Caisse.ACHAT).sorties + b.charges();
        StringBuilder soldes = new StringBuilder();
        for (Caisse.Compte c : b.comptes) {
            if (c.reel == null) continue;
            if (soldes.length() > 0) soldes.append(" · ");
            soldes.append(Txn.opName(c.op)).append(" ").append(SmsParser.fmt(c.reel));
        }
        Long esp = Finances.especes(b.especes);
        if (esp != null) soldes.append(soldes.length() > 0 ? " · " : "").append("Espèces ").append(SmsParser.fmt(esp));
        if (soldes.length() == 0) soldes.append("non lus");
        int aClasser = b.aClasser.size();
        return new Evenement("bilan", "bilan|" + caisseId + "|" + jour,
                jourTexte(jour), caisseNom, fcfa(b.recettes()), fcfa(depenses), signe(b.resultat), soldes.toString(),
                aClasser == 0 ? "aucune" : aClasser + " opération" + (aClasser > 1 ? "s" : ""));
    }

    /** Écart constaté à la clôture des espèces (rien si la caisse n'est pas clôturée ou tombe juste). */
    public static Evenement ecart(String caisseId, String caisseNom, String jour, Caisse.Especes e) {
        if (e == null || e.compte == null || e.ecart == null || e.ecart == 0) return null;
        return new Evenement("ecart", "ecart|" + caisseId + "|" + jour + "|" + e.compte,
                caisseNom, jourTexte(jour), fcfa(e.compte), fcfa(e.theo), signe(e.ecart));
    }

    /** SMS manquant ou faux : rupture de la chaîne des soldes dans les opérations depuis une date. */
    public static List<Evenement> chaine(String caisseId, String caisseNom, List<Txn> tx, long depuis) {
        List<Evenement> out = new ArrayList<>();
        for (Txn t : tx) {
            if (t.date < depuis) continue;
            for (String f : t.flags) {
                if (!f.startsWith("!") || !(f.contains("SMS manquant"))) continue;
                out.add(new Evenement("chaine", "chaine|" + caisseId + "|" + Caisse.cle(t), Txn.opName(t.op), caisseNom,
                        f.substring(1) + " Vérifiez l'historique " + Txn.opName(t.op) + " du " + date(t.date) + "."));
                break;
            }
        }
        return out;
    }

    /** Dernier solde lu sous le seuil (une alerte par compte et par jour). */
    public static List<Evenement> soldesBas(String caisseId, String caisseNom, String jour, Analyzer.Result r, Reglages g) {
        List<Evenement> out = new ArrayList<>();
        for (Analyzer.Account a : Analyzer.summary(r, 0, Long.MAX_VALUE)) {
            if (a.reel == null) continue;
            long seuil = Caisse.OM.equals(a.op) ? g.seuilOm : Caisse.MOMO.equals(a.op) ? g.seuilMomo : -1;
            if (seuil <= 0 || a.reel >= seuil * 100) continue;
            out.add(new Evenement("solde", "solde|" + caisseId + "|" + a.op + "|" + jour, Txn.opName(a.op), caisseNom,
                    "Solde bas : " + fcfa(a.reel) + " (seuil " + SmsParser.fmt(seuil * 100) + " FCFA). Pensez à recharger."));
        }
        return out;
    }

    /** Remboursements attendus ou à faire aujourd'hui et en retard (une alerte par jour, 5 personnes au plus). */
    public static Evenement dettes(String jour, Dettes.Bilan d, long now) {
        long fin = finJour(now);
        List<String> lignes = new ArrayList<>();
        int reste = 0;
        for (Dettes.Personne p : d.personnes) {
            if (p.soldee() || p.echeance == null || p.echeance > fin) continue;
            if (lignes.size() >= 5) { reste++; continue; }
            boolean retard = p.echeance < debutJour(now);
            String quand = retard ? "en retard depuis le " + date(p.echeance) : "aujourd'hui";
            lignes.add(p.net() > 0 ? p.nom + " vous doit " + fcfa(p.net()) + " (" + quand + ")"
                    : "Vous devez " + fcfa(-p.net()) + " à " + p.nom + " (" + quand + ")");
        }
        if (lignes.isEmpty()) return null;
        String txt = String.join(" ; ", lignes) + (reste > 0 ? " ; et " + reste + " autre(s)" : "") + ".";
        return new Evenement("dettes", "dettes|" + jour, jourTexte(jour), txt);
    }

    /** Vrai quand l'heure du bilan du soir est passée aujourd'hui. */
    public static boolean heureBilan(long now, int heure) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        return c.get(Calendar.HOUR_OF_DAY) >= heure;
    }

    static long debutJour(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long finJour(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(debutJour(now));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis() - 1;
    }

    /** « 2026-11-22 » → « 22/11/2026 ». */
    static String jourTexte(String jour) {
        if (jour == null || jour.length() != 10) return jour;
        return jour.substring(8, 10) + "/" + jour.substring(5, 7) + "/" + jour.substring(0, 4);
    }

    /**
     * Toutes les alertes à proposer maintenant pour cette caisse. Le serveur ignore celles déjà envoyées (même clé)
     * et celles désactivées dans les réglages.
     */
    public static List<Evenement> calculer(String caisseId, String caisseNom, Analyzer.Result r, Caisse.Bilan aujourdhui,
                                           Dettes.Bilan dettes, Reglages g, long now, boolean forcerBilan) {
        List<Evenement> out = new ArrayList<>();
        String jour = Caisse.jourDe(now);
        if (forcerBilan || (g.bilan && heureBilan(now, g.bilanHeure))) {
            Evenement b = bilan(caisseId, caisseNom, jour, aujourdhui);
            if (forcerBilan) b.cle += "|essai|" + now; // envoi à la demande : ne bloque pas le bilan du soir
            out.add(b);
        }
        if (g.ecart) { Evenement e = ecart(caisseId, caisseNom, jour, aujourdhui.especes); if (e != null) out.add(e); }
        // SMS manquants des dernières 48 heures seulement : pas d'alerte sur l'historique à l'activation.
        if (g.chaine) out.addAll(chaine(caisseId, caisseNom, r.tx, now - 48L * 3600 * 1000));
        if (g.solde) out.addAll(soldesBas(caisseId, caisseNom, jour, r, g));
        if (g.dettes && dettes != null) { Evenement e = dettes(jour, dettes, now); if (e != null) out.add(e); }
        return out;
    }
}
