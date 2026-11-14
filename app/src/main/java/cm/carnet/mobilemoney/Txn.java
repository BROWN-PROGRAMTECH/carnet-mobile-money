package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.List;

/** Une transaction (ou un SMS non exploitable) issue d'un SMS. Montants en centimes de FCFA. */
public final class Txn {
    public static final int TX = 0, NOISE = 1, FAILED = 2, UNKNOWN = 3;

    public int kind;
    public String reason = "";
    public String raw = "", sender = "", op = "", sub = "";
    public long smsDate;
    public int order;

    /** TRANSFERT_RECU, TRANSFERT_ENVOYE, TRANSFERT (sens à déduire), DEPOT, DEPOT_CLIENT, RETRAIT, PAIEMENT, CREDIT_TEL, SOLDE, AUTRE. */
    public String type = "AUTRE";
    /** +1 entrée, -1 sortie, 0 inconnu ou consultation de solde. */
    public int sens;
    public long montant, frais;
    public Long solde;
    /** Solde connu ou calculé juste avant et juste après l'opération (rempli par Analyzer). */
    public Long avant, apres;
    public String txnId, nom, numero, motif;
    public long date;

    /** Alertes : chaque entrée commence par "!" (grave) ou "~" (à surveiller). */
    public final List<String> flags = new ArrayList<>();

    public boolean isBalanceCheck() { return "SOLDE".equals(type); }

    public String typeLabel() {
        switch (type) {
            case "TRANSFERT_RECU": return "Transfert reçu";
            case "TRANSFERT_ENVOYE": return "Transfert envoyé";
            case "TRANSFERT": return "Transfert";
            case "DEPOT": return "Dépôt reçu";
            case "DEPOT_CLIENT": return "Dépôt pour un client";
            case "RETRAIT": return "Retrait";
            case "PAIEMENT": return "Paiement";
            case "CREDIT_TEL": return "Achat de crédit";
            case "SOLDE": return "Consultation de solde";
            default: return "Opération";
        }
    }

    public static String opName(String op) {
        return "OM".equals(op) ? "Orange Money" : "MTN MoMo";
    }
}
