package cm.carnet.mobilemoney;

import java.util.List;

/**
 * Float d'un agent mobile money (version 0.17).
 * Un dépôt client consomme le float électronique (l'agent envoie de l'argent OM/MoMo et reçoit des espèces) ;
 * un retrait client consomme les espèces (l'agent reçoit de l'argent électronique et donne des espèces).
 * Seuil d'alerte : celui choisi par l'agent, sinon une journée moyenne (7 derniers jours).
 * Montants en centimes. Classe Java pure, testable sur ordinateur.
 */
public final class Flotte {
    private Flotte() {}

    static final long JOUR = 86400000L;

    public static final class Etat {
        /** Code du compte (OM, MOMO) ou Caisse.ESPECES. */
        public String compte;
        public Long solde;
        /** Moyenne par jour sur 7 jours des opérations qui consomment ce float. */
        public long parJour, moyenne;
        public int nombre;
        /** Jours que le float peut tenir à ce rythme (null : rythme nul ou solde inconnu). */
        public Double joursRestants;
        public long seuil;
        public boolean manuel, bas;

        public String nom() {
            return Caisse.ESPECES.equals(compte) ? "Espèces" : Txn.opName(compte);
        }
    }

    /** Float électronique d'un compte agent. */
    public static Etat electronique(Analyzer.Result r, String op, long seuilManuel, long now) {
        Etat e = new Etat();
        e.compte = op;
        for (Analyzer.Account a : Analyzer.summary(r, 0, now)) if (a.op.equals(op)) e.solde = a.reel;
        long total = 0;
        for (Txn t : r.tx) {
            if (!op.equals(t.op) || t.date < now - 7 * JOUR || t.date > now) continue;
            if (Caisse.effetEspecesAgent(t) > 0) { total += t.montant; e.nombre++; }
        }
        finir(e, total, seuilManuel);
        return e;
    }

    /** Espèces de l'agent face aux retraits clients des comptes agents. */
    public static Etat especes(Analyzer.Result r, List<String> comptesAgent, Long especes, long seuilManuel, long now) {
        Etat e = new Etat();
        e.compte = Caisse.ESPECES;
        e.solde = especes;
        long total = 0;
        for (Txn t : r.tx) {
            if (!comptesAgent.contains(t.op) || t.date < now - 7 * JOUR || t.date > now) continue;
            if (Caisse.effetEspecesAgent(t) < 0) { total += t.montant; e.nombre++; }
        }
        finir(e, total, seuilManuel);
        return e;
    }

    public static void finir(Etat e, long total7, long seuilManuel) {
        e.parJour = total7 / 7;
        e.moyenne = e.nombre > 0 ? total7 / e.nombre : 0;
        e.manuel = seuilManuel > 0;
        e.seuil = e.manuel ? seuilManuel : e.parJour;
        e.bas = e.solde != null && e.seuil > 0 && e.solde < e.seuil;
        e.joursRestants = e.solde != null && e.parJour > 0 ? Math.max(0, e.solde) / (double) e.parJour : null;
    }

    /** Phrase d'état : « Float OM : 45 000 FCFA, environ 1,5 jour de dépôts (30 000/jour). » */
    public static String texte(Etat e) {
        if (e.solde == null) return e.nom() + " : solde inconnu.";
        String usage = Caisse.ESPECES.equals(e.compte) ? "retraits clients" : "dépôts clients";
        StringBuilder b = new StringBuilder();
        if (e.parJour > 0) {
            b.append("≈ ").append(duree(e.joursRestants)).append(" de ").append(usage).append(" (")
                    .append(SmsParser.fmt(e.parJour)).append(" FCFA par jour en moyenne sur 7 jours)");
        } else {
            b.append("aucun ").append(usage.substring(0, usage.length() - 1)).append(" sur 7 jours");
        }
        return b.toString();
    }

    public static String duree(Double jours) {
        if (jours == null) return "—";
        if (jours < 1) {
            long h = Math.max(1, Math.round(jours * 24));
            return h + " h";
        }
        double j = Math.round(jours * 10) / 10.0;
        String s = (j == Math.floor(j) ? String.valueOf((long) j) : String.valueOf(j).replace('.', ','));
        return s + (j >= 2 ? " jours" : " jour");
    }

    /** Message d'alerte si le float est bas, sinon null. */
    public static String alerte(Etat e) {
        if (!e.bas) return null;
        String action = Caisse.ESPECES.equals(e.compte) ? "Prévoyez des espèces pour les retraits clients."
                : "Rechargez votre compte agent " + Txn.opName(e.compte) + " pour servir les dépôts.";
        return "Float " + e.nom() + " bas : " + SmsParser.fmt(e.solde) + " FCFA (seuil " + SmsParser.fmt(e.seuil) + "). " + action;
    }
}
