package cm.carnet.mobilemoney;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit un SMS Orange Money ou MTN MoMo et en extrait une transaction.
 * Version 2 : règles construites à partir de vrais SMS (formats 2026).
 * Tous les montants sont en centimes de FCFA (Orange Money affiche des décimales).
 * Classe Java pure (aucune dépendance Android) : elle se teste sur ordinateur.
 */
public final class SmsParser {
    private SmsParser() {}

    public static final String DEFAULT_OM = "OrangeMoney, Orange Money";
    public static final String DEFAULT_MOMO = "MobileMoney, MTN MoMo";

    /** Un SMS brut tel que lu dans la boîte de réception. */
    public static final class Sms {
        public String address = "";
        public String body = "";
        public long date;
        public String sub = "";
        public String op;

        public Sms() {}

        public Sms(String address, String body, long date, String sub) {
            this.address = address == null ? "" : address;
            this.body = body == null ? "" : body;
            this.date = date;
            this.sub = sub == null ? "" : sub;
        }
    }

    /** Réglages de détection. */
    public static final class Config {
        public final List<String> om = new ArrayList<>();
        public final List<String> momo = new ArrayList<>();
        public boolean auto = false;
        /** Numéro Orange Money du titulaire (9 chiffres), saisi ou détecté. Sert au sens des transferts. */
        public String ownerOm = "";

        public static Config of(String om, String momo, boolean auto) {
            Config c = new Config();
            c.om.addAll(split(om));
            c.momo.addAll(split(momo));
            c.auto = auto;
            return c;
        }

        static List<String> split(String v) {
            List<String> out = new ArrayList<>();
            if (v == null) return out;
            for (String p : v.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
    }

    // ---------- Normalisation ----------

    public static String deaccent(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /** Sans accents, espaces simples, sauts de ligne remplacés par des espaces. */
    public static String norm(String s) {
        return deaccent(s).replace(' ', ' ').replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    public static String senderKey(String s) {
        return deaccent(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---------- Opérateur ----------

    private static final Pattern OM_CONTENT = Pattern.compile("orange money");
    private static final Pattern MOMO_CONTENT = Pattern.compile("mobile money|\\bmomo\\b");

    /** "OM", "MOMO" ou null. Par défaut seuls les expéditeurs officiels sont retenus. */
    public static String detectOp(String address, String body, Config cfg) {
        String k = senderKey(address);
        if (!k.isEmpty()) {
            for (String s : cfg.om) if (k.equals(senderKey(s))) return "OM";
            for (String s : cfg.momo) if (k.equals(senderKey(s))) return "MOMO";
        }
        if (!cfg.auto) return null;
        String low = norm(body).toLowerCase(Locale.ROOT);
        boolean om = OM_CONTENT.matcher(low).find(), mm = MOMO_CONTENT.matcher(low).find();
        if (om && !mm) return "OM";
        if (mm && !om) return "MOMO";
        return null;
    }

    // ---------- Montants (en centimes) ----------

    /** "635.34" -> 63534 ; "10 000" -> 1000000 ; "50.000" -> 5000000 ; "4.2" -> 420. */
    public static Long money(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\s\\u00a0\\u202f]", "");
        if (t.isEmpty()) return null;
        long cents = 0;
        Matcher dec = Pattern.compile("^(.*?)[.,](\\d{1,2})$").matcher(t);
        if (dec.find() && !dec.group(1).isEmpty()) {
            String d = dec.group(2);
            cents = Long.parseLong(d.length() == 1 ? d + "0" : d);
            t = dec.group(1);
        }
        t = t.replaceAll("[.,]", "");
        if (!t.matches("\\d+")) return null;
        try {
            return Long.parseLong(t) * 100 + cents;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Un nombre : chiffres groupés par milliers (espace, point ou virgule) avec décimales éventuelles. */
    static final String NUM = "(\\d{1,3}(?:[ .,]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)(?!\\d)";
    static final String CUR = "(?:FCFA|F CFA|XAF|CFA|F\\b|U\\b)";

    /** Montant qui suit une étiquette, ex. "Nouveau solde : 635.34 FCFA" ou "FRAIS: FCFA 0". */
    static Long labeled(String text, String label) {
        Matcher m = Pattern.compile("(?:" + label + ")\\s*(?:est de|est|de|is|:|=|a)?\\s*:?\\s*(?:" + CUR + "\\s*)?" + NUM, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? money(m.group(1)) : null;
    }

    /** Premier montant suivi d'une devise. */
    static Long firstAmount(String text) {
        Matcher m = Pattern.compile(NUM + "\\s*(?:FCFA|F CFA|XAF)", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? money(m.group(1)) : null;
    }

    static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    static boolean has(String text, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    // ---------- Champs communs ----------

    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?");

    static long dateInText(String text) {
        Matcher m = DATE_ISO.matcher(text);
        if (!m.find()) return 0;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
        return c.getTimeInMillis();
    }

    /** Numéros camerounais (9 chiffres commençant par 6) dans l'ordre d'apparition. */
    public static List<String> phones(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<!\\d)(?:\\+?237)?(6\\d{8})(?!\\d)").matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ---------- Bruit (jamais une transaction) ----------

    static final String NOISE = "(conditions specifiques|code de confirmation|code secret est|ne le partagez|souscri|fidelite paie|erreur de frappe"
            + "|vous souhaitez payer|vous avez demande un paiement|dernieres transactions|a ete deplace|communication credit from orange money"
            + "|airtime|a court d'argent|emprunte jusqu|obtiens jusqu|xtracash|telecharge|bonus|forfait|bundle)";

    // ---------- Analyse ----------

    /** Analyse un SMS dont l'opérateur (sms.op) est déjà connu. */
    public static Txn parse(Sms sms, Config cfg) {
        String raw = sms.body == null ? "" : sms.body;
        String text = norm(raw);
        Txn t = new Txn();
        t.raw = raw;
        t.sender = sms.address;
        t.op = sms.op;
        t.sub = sms.sub == null ? "" : sms.sub;
        t.smsDate = sms.date;
        t.date = sms.date;

        if (has(text, "echec|echoue|failed|n'a pas pu|insuffisant|insufficient")) {
            t.kind = Txn.FAILED;
            t.reason = "Opération échouée : aucun mouvement";
            return t;
        }
        boolean ok = "OM".equals(sms.op) ? parseOm(text, t, cfg) : parseMomo(text, t);
        if (!ok) {
            Long amt = firstAmount(text);
            if (has(text, NOISE) || amt == null) { t.kind = Txn.NOISE; return t; }
            t.kind = Txn.UNKNOWN;
            t.reason = "Format non reconnu";
            return t;
        }
        if (t.kind == Txn.TX && t.montant == 0 && !"SOLDE".equals(t.type)) {
            t.kind = Txn.UNKNOWN;
            t.reason = "Montant introuvable";
        }
        return t;
    }

    // ---------- Orange Money ----------

    static final Pattern OM_ID = Pattern.compile("\\b([A-Z]{1,3}\\d{6}\\.\\d{4}\\.[A-Z]\\d{5})\\b");

    static boolean parseOm(String text, Txn t, Config cfg) {
        // Consultation de solde
        Long solde = labeled(text, "le solde de votre compte est de|votre solde est de|votre solde est");
        if (solde != null && !OM_ID.matcher(text).find()) {
            t.kind = Txn.TX;
            t.type = "SOLDE";
            t.solde = solde;
            return true;
        }
        Matcher idm = OM_ID.matcher(text);
        if (!idm.find()) return false;
        if (has(text, NOISE) && !has(text, "nouveau solde|new balance|solde:")) return false;

        t.kind = Txn.TX;
        t.txnId = idm.group(1);
        String prefix = t.txnId.replaceAll("\\d.*$", "");
        t.solde = labeled(text, "nouveau solde|new balance|solde");
        t.frais = orZero(labeled(text, "frais|fees|fee"));
        Long amt = labeled(text, "montant transaction|montant de la transaction|montant de transaction|transaction amount|montant|amount");
        if (amt == null) amt = labeled(text, "payement de|paiement de|transfert de|depot de|retrait de");
        if (amt == null) amt = firstAmount(text);
        t.montant = amt == null ? 0 : amt;
        String owner = cfg.ownerOm == null ? "" : cfg.ownerOm;
        List<String> tel = phones(text);

        if (has(text, "n'a pas ete accepte|refuse par le destinataire|annul")) {
            t.kind = Txn.UNKNOWN;
            t.reason = "Transfert refusé ou annulé : vérifiez le remboursement sur le solde";
            return true;
        }

        switch (prefix) {
            case "PP": {
                t.type = "TRANSFERT";
                String from = group(text, "transfert de (?:\\+?237)?(6\\d{8})");
                String to = group(text, "vers (?:\\+?237)?(6\\d{8})");
                if (!owner.isEmpty() && owner.equals(from)) { t.sens = -1; counterparty(t, text, to, "vers (?:\\+?237)?6\\d{8}\\s+(.+?)\\s+reussi"); }
                else if (!owner.isEmpty() && owner.equals(to)) { t.sens = 1; t.frais = 0; counterparty(t, text, from, "transfert de (?:\\+?237)?6\\d{8}\\s+(.+?)\\s+vers"); }
                else { t.sens = 0; t.numero = to; }
                if (t.sens != 0) t.type = t.sens > 0 ? "TRANSFERT_RECU" : "TRANSFERT_ENVOYE";
                break;
            }
            case "CI": {
                String from = group(text, "par (?:\\+?237)?(6\\d{8})");
                String to = group(text, "\\bto (?:\\+?237)?(6\\d{8})");
                if (!owner.isEmpty() && owner.equals(from) && !owner.equals(to)) { t.type = "DEPOT_CLIENT"; t.sens = -1; counterparty(t, text, to, "\\bto (?:\\+?237)?6\\d{8}\\s+(.+?)\\.?\\s+informations"); }
                else { t.type = "DEPOT"; t.sens = 1; counterparty(t, text, from, "par (?:\\+?237)?6\\d{8}\\s+(.+?)\\s+to "); }
                break;
            }
            case "CO":
                t.type = "RETRAIT";
                t.sens = -1;
                t.numero = tel.isEmpty() ? null : tel.get(0);
                break;
            case "RC":
                t.type = "CREDIT_TEL";
                t.sens = -1;
                break;
            case "MP":
                t.type = "PAIEMENT";
                t.sens = -1;
                t.nom = group(text, "paiement de (.+?) (?:reussi|en succes)");
                if (t.nom == null) t.nom = group(text, "pour (.+?)\\. n");
                break;
            default:
                t.type = "AUTRE";
                t.sens = 0;
        }
        return true;
    }

    private static void counterparty(Txn t, String text, String number, String nameRegex) {
        t.numero = number;
        String n = group(text, nameRegex);
        if (n != null && n.length() <= 40) t.nom = n;
    }

    // ---------- MTN MoMo ----------

    static final Pattern MOMO_ID = Pattern.compile(
            "(?:identifiant de transaction financiere|financial transaction id|transaction ?id|transactionid)\\s*:?\\s*(\\d{8,})", Pattern.CASE_INSENSITIVE);

    static boolean parseMomo(String text, Txn t) {
        Long courant = labeled(text, "solde courant");
        if (courant != null) {
            t.kind = Txn.TX;
            t.type = "SOLDE";
            t.solde = courant;
            return true;
        }
        Matcher idm = MOMO_ID.matcher(text);
        Long solde = labeled(text, "votre nouveau solde est de|votre nouveau solde est|votre nouveau solde|nouveau solde est|nouveau solde|new balance|balance");
        // "Vous avez reçu 500 XAF airtime" ou "acheté avec succès 100 XAF airtime" sans solde : crédit téléphonique,
        // pas un mouvement du compte (l'achat, lui, a son propre SMS de paiement avec solde).
        if (solde == null && has(text, "airtime")) return false;
        if (!idm.find() && solde == null) return false;

        t.kind = Txn.TX;
        t.txnId = idm.find(0) ? idm.group(1) : null;
        t.solde = solde;
        t.frais = orZero(labeled(text, "les frais s'elevaient a|frais|fees|fee"));
        long inText = dateInText(text);
        if (inText > 0) t.date = inText;
        List<String> tel = phones(text);
        t.numero = tel.isEmpty() ? null : tel.get(0);
        t.motif = clean(group(text, "message de l'expediteur\\s*:\\s*([^.]{0,60})\\."));
        if (t.motif == null) t.motif = clean(group(text, "reference\\s*:\\s*([^.]{0,60})\\."));

        Long amt;
        if ((amt = labeled(text, "vous avez recu")) != null) {
            t.type = has(text, "depot|deposit") ? "DEPOT" : "TRANSFERT_RECU";
            t.sens = 1;
            t.nom = group(text, "vous avez recu [\\d .,]+ (?:XAF|FCFA) de (.+?) \\(");
        } else if ((amt = labeled(text, "transfert de")) != null) {
            t.type = "TRANSFERT_ENVOYE";
            t.sens = -1;
            t.nom = group(text, "effectue avec succes a (.+?) \\(");
        } else if ((amt = labeled(text, "votre paiement de")) != null) {
            t.type = "PAIEMENT";
            t.sens = -1;
            t.nom = group(text, "votre paiement de [\\d .,]+ (?:XAF|FCFA) a (.+?) a ete effectue");
        } else if ((amt = labeled(text, "retrait de")) != null) {
            t.type = "RETRAIT";
            t.sens = -1;
            t.nom = group(text, "chez (.+?) le \\d{4}-");
        } else if ((amt = labeled(text, "une transaction de")) != null) {
            t.type = "PAIEMENT";
            t.sens = -1;
            t.nom = group(text, "effectuee par (.+?) \\(");
            t.motif = clean(group(text, "message du destinataire du debit\\s*:\\s*([^.]{0,60})\\."));
        } else if ((amt = labeled(text, "credit de communication de")) != null) {
            t.type = "CREDIT_TEL";
            t.sens = -1;
            if (tel.size() > 1) t.numero = tel.get(1);
        } else {
            amt = firstAmount(text);
            t.type = "AUTRE";
            t.sens = 0;
        }
        t.montant = amt == null ? 0 : amt;
        if ("PAIEMENT".equals(t.type) && t.nom != null && has(t.nom, "airtime|bundles|forfait")) t.type = "CREDIT_TEL";
        if (t.nom != null && t.nom.length() > 40) t.nom = t.nom.substring(0, 40);
        return true;
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() || s.equals("-") ? null : s;
    }

    private static long orZero(Long v) { return v == null ? 0 : v; }

    // ---------- Titulaire ----------

    /** Le numéro qui apparaît dans le plus de SMS Orange Money est celui du titulaire. */
    public static String detectOwner(List<Sms> omMessages) {
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        int withNumbers = 0;
        for (Sms s : omMessages) {
            java.util.Set<String> seen = new java.util.HashSet<>(phones(norm(s.body)));
            if (!seen.isEmpty()) withNumbers++;
            for (String p : seen) count.merge(p, 1, Integer::sum);
        }
        String best = "";
        int max = 0;
        for (java.util.Map.Entry<String, Integer> e : count.entrySet()) if (e.getValue() > max) { max = e.getValue(); best = e.getKey(); }
        return withNumbers > 0 && max * 2 >= withNumbers ? best : "";
    }

    // ---------- Affichage ----------

    /** Centimes -> "125 000" ou "635,34". */
    public static String fmt(Long cents) {
        if (cents == null) return "—";
        long v = Math.abs(cents);
        String s = Long.toString(v / 100);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        long c = v % 100;
        if (c != 0) b.append(',').append(c < 10 ? "0" + c : String.valueOf(c));
        return (cents < 0 ? "-" : "") + b;
    }

    /** Centimes -> nombre décimal pour Excel ("635.34"). */
    public static String plain(long cents) {
        long v = Math.abs(cents);
        long c = v % 100;
        return (cents < 0 ? "-" : "") + (v / 100) + (c == 0 ? "" : "." + (c < 10 ? "0" + c : String.valueOf(c)));
    }
}
