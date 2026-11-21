package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Résumé par jour d'une caisse (ventes, achats, charges, frais, résultat, soldes), envoyé au serveur pour la vue
 * « Toutes mes caisses ». Aucun SMS ni aucun nom de contact n'y figure. Classe Java pure, testable sur ordinateur.
 */
public final class Resumes {
    private Resumes() {}

    /** Une journée résumée ; montants en centimes. */
    public static final class Jour {
        public String jour;
        public int nbOperations, aClasser;
        public long ventes, achats, charges, frais, resultat, apports, retraits;
        public Long soldeOm, soldeMomo, especesCompte, ecartOm, ecartMomo, ecartEspeces;
        /** Trésorerie en fin de journée : soldes lus par SMS (OM, MoMo, banques) + espèces. */
        public Long tresorerie;

        /** Contenu comparable, pour n'envoyer que les journées qui ont changé. */
        public String signature() {
            return jour + "|" + nbOperations + "|" + aClasser + "|" + ventes + "|" + achats + "|" + charges + "|" + frais + "|" + resultat
                    + "|" + apports + "|" + retraits + "|" + soldeOm + "|" + soldeMomo + "|" + especesCompte + "|" + ecartOm + "|" + ecartMomo
                    + "|" + ecartEspeces + "|" + tresorerie;
        }
    }

    /** Calcule un résumé pour chaque jour où il s'est passé quelque chose (opération SMS, espèces, journée de caisse). */
    public static List<Jour> calculer(Analyzer.Result r, Map<String, Caisse.Classement> classements, Map<String, String> regles,
                                      List<Caisse.Saisie> saisies, List<Caisse.Jour> jours, boolean agentOm, boolean agentMomo) {
        TreeSet<String> dates = new TreeSet<>();
        for (Txn t : r.tx) if (!t.isBalanceCheck()) dates.add(Caisse.jourDe(t.date));
        for (Caisse.Saisie s : saisies) if (Caisse.ESPECES.equals(s.mode)) dates.add(Caisse.jourDe(s.date));
        for (Caisse.Jour j : jours) if (j.jour != null && j.jour.length() == 10) dates.add(j.jour);

        List<Jour> out = new ArrayList<>();
        for (String d : dates) {
            long from = Caisse.debutJour(d);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(from);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            long to = cal.getTimeInMillis() - 1;
            Caisse.Bilan b = Caisse.bilan(r, classements, regles, saisies, jours, from, to, agentOm, agentMomo);
            Jour j = new Jour();
            j.jour = d;
            j.nbOperations = b.lignes.size();
            for (Caisse.Saisie s : b.mouvementsEspeces) if (!s.annule) j.nbOperations++;
            j.aClasser = b.aClasser.size();
            j.ventes = b.recettes();
            j.achats = b.total(Caisse.ACHAT).sorties;
            j.charges = b.charges();
            j.frais = b.frais;
            j.resultat = b.resultat;
            j.apports = b.total(Caisse.APPORT).entrees;
            j.retraits = b.total(Caisse.RETRAIT_PERSO).sorties;
            for (Caisse.Compte c : b.comptes) {
                if (c.sms == null || c.sms.count == 0) continue; // pas d'opération ce jour-là sur ce compte
                if (Caisse.OM.equals(c.op)) { j.soldeOm = c.reel; j.ecartOm = c.ecart; }
                if (Caisse.MOMO.equals(c.op)) { j.soldeMomo = c.reel; j.ecartMomo = c.ecart; }
            }
            j.especesCompte = b.especes.compte;
            j.ecartEspeces = b.especes.ecart;
            Long esp = Finances.especes(b.especes);
            j.tresorerie = Finances.soldesA(r, to) + (esp != null ? esp : 0);
            out.add(j);
        }
        return out;
    }

    /** Journées dont le contenu a changé depuis le dernier envoi (signatures déjà envoyées : jour → signature). */
    public static List<Jour> aEnvoyer(List<Jour> tous, Map<String, String> envoyes) {
        List<Jour> out = new ArrayList<>();
        for (Jour j : tous) if (!j.signature().equals(envoyes.get(j.jour))) out.add(j);
        return out;
    }

    public static Map<String, String> signatures(List<Jour> jours) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Jour j : jours) m.put(j.jour, j.signature());
        return m;
    }
}
