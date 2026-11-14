package cm.carnet.mobilemoney;

/**
 * Prix d'un export Excel, et décision prise sur le téléphone avant de l'envoyer au serveur.
 * Le serveur refait le même calcul et reste seul juge en ligne ; hors ligne, ce calcul permet d'exporter
 * sur le dernier solde connu. Classe Java pure, testable sur ordinateur.
 */
public final class Tarif {
    private Tarif() {}

    public static final int PETIT = 500, GRAND = 1000, SEUIL = 200;

    /** 0 opération : gratuit ; 1 à seuil : petit prix ; au-delà : grand prix (FCFA). */
    public static int prix(int nb, int petit, int grand, int seuil) {
        if (nb <= 0) return 0;
        return nb <= seuil ? petit : grand;
    }

    public static final class Decision {
        /** Prix réellement débité (0 si offert ou vide). */
        public int prix;
        /** Prix normal, même quand l'export est offert. */
        public int prixNormal;
        public boolean offert, possible;
        /** Solde après l'export. */
        public long soldeApres;
        /** Ce qui manque pour pouvoir exporter (0 si possible). */
        public long manque;
    }

    public static Decision decider(int nb, long solde, int offertsRestants, int petit, int grand, int seuil) {
        Decision d = new Decision();
        d.prixNormal = prix(nb, petit, grand, seuil);
        d.offert = d.prixNormal > 0 && offertsRestants > 0;
        d.prix = d.offert ? 0 : d.prixNormal;
        d.possible = d.prix == 0 || solde >= d.prix;
        d.manque = d.possible ? 0 : d.prix - solde;
        d.soldeApres = solde - d.prix;
        return d;
    }

    /** "1 000 FCFA" (montants entiers en FCFA). */
    public static String fcfa(long montant) {
        return SmsParser.fmt(montant * 100) + " FCFA";
    }
}
