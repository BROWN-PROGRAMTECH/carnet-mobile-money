package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.List;

/** Une transaction (ou un SMS non exploitable) issue d'un SMS. */
public final class Txn {
    public static final int TX = 0, NOISE = 1, FAILED = 2, UNKNOWN = 3;

    public int kind;
    public String reason = "";
    public String raw = "", sender = "", op = "", account = "", sub = "";
    public long smsDate;
    public int order;

    public String type = "AUTRE";
    /** +1 entrée, -1 sortie, 0 inconnu. */
    public int sens;
    public long montant, frais, taxe, commission;
    public Long solde;
    public String txnId, nom, numero, motif = "";
    public long date;
    public Long gap;

    /** Alertes : chaque entrée commence par "!" (grave) ou "~" (à surveiller). */
    public final List<String> flags = new ArrayList<>();

    public String typeLabel() {
        switch (type) {
            case "TRANSFERT_RECU": return "Transfert reçu";
            case "TRANSFERT_ENVOYE": return "Transfert envoyé";
            case "PAIEMENT_RECU": return "Paiement reçu";
            case "PAIEMENT_ENVOYE": return "Paiement envoyé";
            case "DEPOT": return "Dépôt";
            case "RETRAIT": return "Retrait";
            case "FACTURE": return "Facture / achat";
            case "COMMISSION": return "Commission";
            case "REVERSEMENT": return "Annulation";
            default: return "Opération";
        }
    }

    public static String opName(String op) {
        return "OM".equals(op) ? "Orange Money" : "MTN MoMo";
    }
}
