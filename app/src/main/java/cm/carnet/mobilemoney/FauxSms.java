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
}
