package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Règles de lecture proposées par l'assistant IA (version 0.13), pour les SMS que les règles fournies ne savent pas lire.
 * Une règle est écrite sur le texte normalisé du SMS (sans accents, espaces simplifiés) :
 *  - signature : expression qui doit se trouver dans le SMS (insensible à la casse) ;
 *  - montant, solde, frais : expressions dont le premier groupe capture le nombre ;
 *  - sens : +1 entrée, -1 sortie, 0 consultation ou information ; libellé : nom de l'opération affiché.
 * Le serveur vérifie chaque règle sur l'exemple avant de l'accepter ; ici, une expression illisible est ignorée.
 * Classe Java pure, testable sur ordinateur.
 */
public final class ReglesIa {
    private ReglesIa() {}

    public static final class Regle {
        public String id, op, signature, montant, solde, frais, libelle;
        public int sens;
        /** true : validée par le superadmin (servie à tous) ; false : proposée pour ce compte seulement. */
        public boolean validee;
        private Pattern pSig, pMontant, pSolde, pFrais;
        private boolean compilee, valide;

        boolean prete() {
            if (compilee) return valide;
            compilee = true;
            try {
                pSig = Pattern.compile(signature, Pattern.CASE_INSENSITIVE);
                pMontant = montant == null || montant.isEmpty() ? null : Pattern.compile(montant, Pattern.CASE_INSENSITIVE);
                pSolde = solde == null || solde.isEmpty() ? null : Pattern.compile(solde, Pattern.CASE_INSENSITIVE);
                pFrais = frais == null || frais.isEmpty() ? null : Pattern.compile(frais, Pattern.CASE_INSENSITIVE);
                valide = signature != null && !signature.isEmpty();
            } catch (PatternSyntaxException | NullPointerException e) {
                valide = false;
            }
            return valide;
        }
    }

    /** Nombre capturé par le premier groupe, en centimes ; null si absent. */
    static Long capture(Pattern p, String text) {
        if (p == null) return null;
        Matcher m = p.matcher(text);
        if (!m.find() || m.groupCount() < 1 || m.group(1) == null) return null;
        return SmsParser.money(m.group(1));
    }

    /** Applique la première règle de cet opérateur qui reconnaît le SMS ; vrai si le SMS est lu. */
    public static boolean appliquer(List<Regle> regles, String op, String text, Txn t) {
        if (regles == null || text == null) return false;
        for (Regle r : regles) {
            if (r.op != null && !r.op.equals(op)) continue;
            if (!r.prete() || !r.pSig.matcher(text).find()) continue;
            Long montant = capture(r.pMontant, text);
            Long solde = capture(r.pSolde, text);
            if (montant == null && solde == null) continue;
            t.kind = Txn.TX;
            if (montant == null || r.sens == 0) {
                if (solde == null) continue;
                t.type = "SOLDE";
                t.solde = solde;
                return true;
            }
            t.type = "IA:" + (r.libelle == null || r.libelle.trim().isEmpty() ? "Opération" : r.libelle.trim());
            t.sens = r.sens > 0 ? 1 : -1;
            t.montant = montant;
            t.solde = solde;
            Long f = capture(r.pFrais, text);
            t.frais = f == null ? 0 : f;
            return true;
        }
        return false;
    }

    /** Empreinte de l'ensemble des règles (pour relire les SMS quand elles changent). */
    public static String signature(List<Regle> regles) {
        if (regles == null || regles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Regle r : regles) sb.append(r.id).append(r.validee ? 'v' : 'p').append(',');
        return Integer.toHexString(sb.toString().hashCode());
    }

    // ---------- Masquage avant l'envoi à l'IA ----------

    private static final Pattern TEL = Pattern.compile("(?:\\+?237[ .]?)?\\b6[ .]?\\d{2}[ .]?\\d{2}[ .]?\\d{2}[ .]?\\d{2}\\b");
    private static final Pattern ID_OM = Pattern.compile("\\b[A-Z]{1,3}\\d{6}\\.\\d{4}\\.[A-Z]\\d{5}\\b");
    private static final Pattern LONG_NUM = Pattern.compile("\\b\\d{8,}\\b");
    private static final Pattern NOMS = Pattern.compile("\\b(?:[A-Z][A-Z'\\-]{1,}\\s+){1,4}[A-Z][A-Z'\\-]{1,}\\b");
    private static final String GARDES = "XAF|FCFA|CFA|OM|MTN|MOMO|ID|SMS|USSD|BICEC|UBA|SGC|CCA|ECOBANK|AFRILAND|ORANGE|MONEY|MOBILE";

    /**
     * Texte du SMS sans données personnelles : numéros de téléphone, ID de transaction, longues suites de chiffres
     * (comptes) et noms en majuscules sont remplacés. Les montants restent (la règle en a besoin).
     */
    public static String masquer(String brut) {
        if (brut == null) return "";
        String s = SmsParser.norm(brut);
        s = ID_OM.matcher(s).replaceAll("<ID>");
        s = TEL.matcher(s).replaceAll("<TEL>");
        s = LONG_NUM.matcher(s).replaceAll("<NUMERO>");
        Matcher m = NOMS.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            boolean garde = g.trim().matches("(?:(?:" + GARDES + ")\\s*)+");
            m.appendReplacement(out, garde ? Matcher.quoteReplacement(g) : "<NOM>");
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Liste à partir des champs reçus du serveur (pour les tests et l'application). */
    public static Regle regle(String id, String op, String signature, String montant, String solde, String frais, int sens, String libelle, boolean validee) {
        Regle r = new Regle();
        r.id = id;
        r.op = op;
        r.signature = signature;
        r.montant = montant;
        r.solde = solde;
        r.frais = frais;
        r.sens = sens;
        r.libelle = libelle;
        r.validee = validee;
        return r;
    }

    public static List<Regle> vide() {
        return new ArrayList<>();
    }
}
