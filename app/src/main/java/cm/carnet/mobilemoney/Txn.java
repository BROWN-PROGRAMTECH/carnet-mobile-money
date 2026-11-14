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

    private static final String SEP = "\u001f";

    /** Résultat de lecture du SMS, sans le texte du SMS (déjà dans la base). */
    public String toCache() {
        return kind + SEP + n(reason) + SEP + n(type) + SEP + sens + SEP + montant + SEP + frais + SEP + (solde == null ? "" : solde)
                + SEP + n(txnId) + SEP + n(nom) + SEP + n(numero) + SEP + n(motif) + SEP + date;
    }

    /** Recrée la lecture mémorisée ; null si la donnée est illisible (le SMS sera alors relu). */
    public static Txn fromCache(String data) {
        try {
            String[] f = data.split(SEP, -1);
            if (f.length != 12) return null;
            Txn t = new Txn();
            t.kind = Integer.parseInt(f[0]);
            t.reason = f[1];
            t.type = f[2].isEmpty() ? "AUTRE" : f[2];
            t.sens = Integer.parseInt(f[3]);
            t.montant = Long.parseLong(f[4]);
            t.frais = Long.parseLong(f[5]);
            t.solde = f[6].isEmpty() ? null : Long.valueOf(f[6]);
            t.txnId = e(f[7]);
            t.nom = e(f[8]);
            t.numero = e(f[9]);
            t.motif = e(f[10]);
            t.date = Long.parseLong(f[11]);
            return t;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String n(String s) { return s == null ? "" : s.replace(SEP, " "); }

    private static String e(String s) { return s.isEmpty() ? null : s; }

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
