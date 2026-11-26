package cm.carnet.mobilemoney;

import java.util.Locale;

/**
 * Détection des faux SMS de paiement (version 0.13), sur le téléphone, sans réseau ni IA. Gratuite pour tous.
 * Un vrai SMS Orange Money ou MoMo vient d'un expéditeur officiel (« OrangeMoney », « MobileMoney »…), jamais d'un
 * numéro ordinaire. Un SMS qui annonce un paiement reçu, avec un montant, mais envoyé par un numéro de téléphone est
 * presque toujours une arnaque : le client montre le SMS et repart avec la marchandise.
 * Classe Java pure, testable sur ordinateur.
 */
public final class FauxSms {
    private FauxSms() {}

    /** Expéditeur qui ressemble à un numéro de téléphone (et pas à un nom d'opérateur). */
    static boolean numeroOrdinaire(String expediteur) {
        if (expediteur == null) return false;
        String d = expediteur.replaceAll("[\\s+\\-.]", "");
        return d.matches("\\d{8,15}");
    }

    /**
     * Raison du soupçon, ou null si le SMS n'a rien d'un faux paiement.
     * @param officiel vrai si l'expéditeur est un expéditeur suivi (OM, MoMo, banque)
     */
    public static String suspect(String expediteur, String corps, boolean officiel) {
        if (officiel || corps == null) return null;
        String t = SmsParser.norm(corps).toLowerCase(Locale.ROOT);
        boolean argent = t.matches("(?s).*\\d[\\d .,]*\\s*(?:fcfa|xaf|f cfa|cfa)\\b.*") || t.matches("(?s).*\\b(?:fcfa|xaf)\\s*\\d.*");
        boolean recu = t.matches("(?s).*\\b(?:vous avez recu|avez recu|transfert de|depot de|depot effectue|credite|nouveau solde|transaction id|id de transaction|paiement recu)\\b.*");
        boolean marque = t.matches("(?s).*\\b(?:orange money|mobile money|momo|mtn|om)\\b.*");
        if (!argent || !recu) return null;
        if (numeroOrdinaire(expediteur)) {
            return "Ce SMS de paiement vient d'un numéro ordinaire (" + expediteur + ") : un vrai SMS "
                    + (t.contains("mtn") || t.contains("momo") || t.contains("mobile money") ? "MoMo" : "Orange Money")
                    + " ne vient jamais d'un numéro. Ne livrez pas : vérifiez votre solde (#150# ou *126#).";
        }
        if (marque) {
            return "SMS de paiement d'un expéditeur inconnu (« " + expediteur + " ») : vérifiez votre solde (#150# ou *126#) avant de livrer.";
        }
        return null;
    }

    /**
     * Niveau 2 (version 0.17) : paiement reçu d'un expéditeur officiel (OM ou MoMo) qui ne suit pas la chaîne des soldes.
     * Un faux SMS (expéditeur imité) annonce un solde que l'escroc ne connaît pas ; un SMS renvoyé réutilise un ID déjà vu.
     * Renvoie la raison du doute, ou null si le paiement est cohérent (ou invérifiable faute de solde précédent).
     * @param soldePrecedent dernier solde connu de ce compte (centimes), ou null
     * @param idsVus         ID de transaction déjà reçus sur ce compte
     */
    public static String chaine(Txn t, Long soldePrecedent, java.util.Collection<String> idsVus) {
        if (t == null || t.kind != Txn.TX || t.sens <= 0 || t.isBalanceCheck() || t.montant <= 0) return null;
        if (!Caisse.OM.equals(t.op) && !Caisse.MOMO.equals(t.op)) return null;
        String ussd = Caisse.MOMO.equals(t.op) ? "*126#" : "#150#";
        String montant = SmsParser.fmt(t.montant) + " FCFA";
        if (t.txnId != null && idsVus != null && idsVus.contains(t.txnId)) {
            return "Paiement de " + montant + " non confirmé : l'ID de transaction " + t.txnId + " a déjà été reçu. "
                    + "Un SMS renvoyé ou copié ne prouve pas un nouveau paiement. Vérifiez votre solde (" + ussd + ") avant de livrer.";
        }
        if (t.solde == null || soldePrecedent == null) return null;
        long attendu = soldePrecedent + t.montant - t.frais;
        if (Math.abs(t.solde - attendu) <= 100) return null;
        if (t.solde.longValue() == soldePrecedent.longValue()) {
            return "Paiement de " + montant + " non confirmé : le solde annoncé (" + SmsParser.fmt(t.solde) + ") n'a pas bougé depuis votre dernier SMS "
                    + Txn.opName(t.op) + ". Vérifiez votre solde (" + ussd + ") avant de livrer.";
        }
        return "Paiement de " + montant + " non confirmé : le nouveau solde annoncé (" + SmsParser.fmt(t.solde) + ") ne suit pas votre dernier solde "
                + Txn.opName(t.op) + " (" + SmsParser.fmt(soldePrecedent) + " + " + SmsParser.fmt(t.montant) + " = " + SmsParser.fmt(attendu) + "). "
                + "Faux SMS ou SMS manquant : vérifiez votre solde (" + ussd + ") avant de livrer.";
    }
}
