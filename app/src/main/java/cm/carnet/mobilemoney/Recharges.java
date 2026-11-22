package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recharge du porte-monnaie en un geste (version 0.10) :
 *  1. le client choisit l'opérateur et le montant, l'application ouvre le menu de l'opérateur (code USSD) ;
 *  2. le client paie avec son code secret, que l'application ne voit jamais ;
 *  3. le SMS de l'opérateur arrive : l'application y retrouve le transfert (même opérateur, même montant,
 *     vers le numéro marchand) et déclare seule son ID.
 * Côté marchand, le téléphone du superadmin relève les transferts reçus pour la validation automatique.
 * Classe Java pure, testable sur ordinateur. Montants des SMS en centimes, montants de recharge en FCFA.
 */
public final class Recharges {
    private Recharges() {}

    /** Une recharge payée (ou en cours de paiement) dont l'ID n'est pas encore déclaré. */
    public static final class Attente {
        public String op;
        /** FCFA. */
        public long montant;
        public String numero;
        /** Moment où le client a touché « Payer ». */
        public long depuis;

        public String encode() {
            return op + "|" + montant + "|" + numero + "|" + depuis;
        }

        public static Attente decode(String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|");
            if (p.length < 4) return null;
            try {
                Attente a = new Attente();
                a.op = p[0];
                a.montant = Long.parseLong(p[1]);
                a.numero = p[2];
                a.depuis = Long.parseLong(p[3]);
                return a;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Au-delà de 24 heures sans SMS, on n'attend plus : le client peut saisir l'ID à la main. */
        public boolean expiree(long now) {
            return now - depuis > 24L * 3600 * 1000;
        }
    }

    /** 9 derniers chiffres d'un numéro camerounais (sans 237 ni espaces). */
    public static String neuf(String numero) {
        if (numero == null) return "";
        String n = numero.replaceAll("\\D", "");
        return n.length() > 9 ? n.substring(n.length() - 9) : n;
    }

    /**
     * Le transfert qui correspond à la recharge en attente : même opérateur, sortie du montant exact, vers le numéro
     * marchand, au plus 10 minutes avant le « Payer » (horloge du téléphone), ID présent et pas encore déclaré.
     */
    public static Txn trouverEnvoi(List<Txn> tx, Attente a, Set<String> dejaDeclares) {
        if (a == null || tx == null) return null;
        String marchand = neuf(a.numero);
        Txn meilleur = null;
        for (Txn t : tx) {
            if (t.kind != Txn.TX || t.isBalanceCheck() || t.sens >= 0 || t.txnId == null || t.txnId.isEmpty()) continue;
            if (!a.op.equals(t.op) || t.montant != a.montant * 100) continue;
            if (t.date < a.depuis - 10L * 60 * 1000) continue;
            if (t.numero != null && !neuf(t.numero).equals(marchand)) continue;
            if (dejaDeclares != null && dejaDeclares.contains(t.txnId.toUpperCase(Locale.ROOT))) continue;
            // Le plus ancien après le « Payer » : un second paiement identique serait une seconde recharge.
            if (meilleur == null || t.date < meilleur.date) meilleur = t;
        }
        return meilleur;
    }

    /** Code à composer : {numero} et {montant} sont remplacés si le code réglé par le superadmin les contient. */
    public static String codeUssd(String modele, String numero, long montant) {
        String m = modele == null || modele.trim().isEmpty() ? "#150#" : modele.trim();
        return m.replace("{numero}", neuf(numero)).replace("{montant}", String.valueOf(montant));
    }

    /** Vrai si le code ouvre directement le transfert (le client n'a plus qu'à confirmer avec son code secret). */
    public static boolean codeComplet(String modele) {
        return modele != null && modele.contains("{numero}") && modele.contains("{montant}");
    }

    /** Un transfert reçu par le téléphone marchand, à transmettre pour la validation automatique. */
    public static final class Recu {
        public String op, reference, numero;
        /** FCFA. */
        public long montant;
        public long date;
    }

    /** Transferts reçus (OM et MoMo) depuis une date, d'au moins le montant minimum de recharge (FCFA). */
    public static List<Recu> recusMarchand(List<Txn> tx, long depuis, long minimum) {
        List<Recu> out = new ArrayList<>();
        if (tx == null) return out;
        for (Txn t : tx) {
            if (t.kind != Txn.TX || t.isBalanceCheck() || t.sens <= 0 || t.date < depuis) continue;
            if (!Caisse.OM.equals(t.op) && !Caisse.MOMO.equals(t.op)) continue;
            if (t.txnId == null || t.txnId.isEmpty() || t.montant < minimum * 100) continue;
            Recu r = new Recu();
            r.op = t.op;
            r.reference = t.txnId;
            r.montant = t.montant / 100;
            r.numero = t.numero;
            r.date = t.date;
            out.add(r);
        }
        return out;
    }
}
