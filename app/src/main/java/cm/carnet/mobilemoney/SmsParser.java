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
 * Classe Java pure (aucune dépendance Android) : elle se teste sur ordinateur.
 */
public final class SmsParser {
    private SmsParser() {}

    public static final String DEFAULT_OM = "OrangeMoney, Orange Money";
    public static final String DEFAULT_MOMO = "MobileMoney, MTN MoMo, MoMo";

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

    /** Réglages de détection des expéditeurs. */
    public static final class Config {
        public final List<String> om = new ArrayList<>();
        public final List<String> momo = new ArrayList<>();
        public boolean auto = true;

        public static Config of(String om, String momo, boolean auto) {
            Config c = new Config();
            for (String s : split(om)) c.om.add(s);
            for (String s : split(momo)) c.momo.add(s);
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

    public static String norm(String s) {
        return deaccent(s).replace(' ', ' ').replace(' ', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    public static String senderKey(String s) {
        return deaccent(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---------- Détection de l'opérateur ----------

    private static final Pattern OM_CONTENT = Pattern.compile("orange money");
    private static final Pattern MOMO_CONTENT = Pattern.compile("momo|mobile money|\\*126");

    /** Renvoie "OM", "MOMO" ou null si le SMS ne vient pas d'un compte mobile money. */
    public static String detectOp(String address, String body, Config cfg) {
        String k = senderKey(address);
        if (!k.isEmpty()) {
            for (String s : cfg.om) if (k.equals(senderKey(s))) return "OM";
            for (String s : cfg.momo) if (k.equals(senderKey(s))) return "MOMO";
        }
        if (!cfg.auto) return null;
        String low = norm(body).toLowerCase(Locale.ROOT);
        boolean om = OM_CONTENT.matcher(low).find();
        boolean mm = MOMO_CONTENT.matcher(low).find();
        if (om && !mm) return "OM";
        if (mm && !om) return "MOMO";
        return null;
    }

    // ---------- Montants ----------

    static final String N = "(\\d{1,3}(?:[ .,]\\d{3})+|\\d+)(?:[.,]\\d{1,2}(?!\\d))?";
    static final String CUR = "(?:F\\s?CFA|FCFA|XAF|CFA|F(?![a-z]))";

    static final class Span {
        final Long value;
        final int start, end;
        Span(Long v, int s, int e) { value = v; start = s; end = e; }
    }

    static Long toLong(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\s\\u00a0]", "");
        if (t.matches(".*[.,]\\d{1,2}$") && !t.matches(".*[.,]\\d{3}$")) t = t.replaceAll("[.,]\\d{1,2}$", "");
        t = t.replaceAll("[.,]", "");
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Span findLabeled(String text, String label) {
        Pattern p = Pattern.compile("(?:" + label + ")\\s*(?:est|is|de|of|:|=)?\\s*:?\\s*(?:" + CUR + "\\s*)?" + N
                + "(?:\\s*" + CUR + ")?", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        return new Span(toLong(m.group(1)), m.start(), m.end());
    }

    private static final Pattern AMOUNT_AFTER = Pattern.compile(N + "\\s*" + CUR, Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_BEFORE = Pattern.compile("(?:XAF|FCFA)\\s*" + N, Pattern.CASE_INSENSITIVE);

    static List<Span> allAmounts(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = AMOUNT_AFTER.matcher(text);
        while (m.find()) out.add(new Span(toLong(m.group(1)), m.start(), m.end()));
        m = AMOUNT_BEFORE.matcher(text);
        while (m.find()) {
            boolean inside = false;
            for (Span a : out) if (m.start() >= a.start && m.start() < a.end) inside = true;
            if (!inside) out.add(new Span(toLong(m.group(1)), m.start(), m.end()));
        }
        out.sort((a, b) -> Integer.compare(a.start, b.start));
        return out;
    }

    // ---------- Autres champs ----------

    private static final Pattern TXN_ID = Pattern.compile(
            "(?:ID\\s*(?:de\\s*)?(?:la\\s*)?transaction|financial\\s+transaction\\s+id|transaction\\s*id|trans(?:action)?\\.?\\s*(?:id|no|num(?:ero)?)|txn\\s*id|r[ée]f(?:[ée]rence)?\\s*(?:transaction)?|reference)\\s*[:#.]?\\s*([A-Z0-9][A-Z0-9.\\-/]{4,})",
            Pattern.CASE_INSENSITIVE);

    static String findTxnId(String text) {
        Matcher m = TXN_ID.matcher(text);
        if (m.find() && m.group(1).matches(".*\\d.*")) return m.group(1).replaceAll("[.\\-/]+$", "");
        return null;
    }

    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern DATE_FR = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})\\s*(?:a|à|at)?\\s*(\\d{2})[:h](\\d{2})(?::(\\d{2}))?");

    static long findDateInText(String text) {
        Matcher m = DATE_ISO.matcher(text);
        if (m.find()) return millis(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6));
        m = DATE_FR.matcher(text);
        if (m.find()) return millis(m.group(3), m.group(2), m.group(1), m.group(4), m.group(5), m.group(6));
        return 0;
    }

    private static long millis(String y, String mo, String d, String h, String mi, String s) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(y), Integer.parseInt(mo) - 1, Integer.parseInt(d), Integer.parseInt(h),
                Integer.parseInt(mi), s == null ? 0 : Integer.parseInt(s));
        return c.getTimeInMillis();
    }

    private static final Pattern PHONE = Pattern.compile("(?:\\+?237)?\\s?(6\\d{8})(?!\\d)");

    static String findNumber(String text, String exclude) {
        Matcher m = PHONE.matcher(text);
        while (m.find()) if (exclude == null || !exclude.equals(m.group(1))) return m.group(1);
        return null;
    }

    private static final Pattern NAME_PAREN = Pattern.compile("\\(([A-Z][A-Z .'\\-]{2,40})\\)");
    private static final Pattern NAME_AFTER = Pattern.compile(
            "(?:\\bde|\\bfrom|\\ba|à|\\bto|\\bvers|\\bpar)\\s+([A-Z][A-Z'\\-+&]+(?:\\s+[A-Z0-9][A-Z0-9'\\-+&]+){0,4})\\s*(?:\\(|\\d|,|\\.|\\s+(?:reussi|réussi|le|on|effectu))");

    static String findName(String raw) {
        Matcher m = NAME_PAREN.matcher(raw);
        if (m.find()) return m.group(1).trim();
        m = NAME_AFTER.matcher(raw);
        if (m.find() && !m.group(1).matches("FCFA|XAF|CFA|ID|MTN|OM")) return m.group(1).trim();
        return null;
    }

    private static final Pattern MOTIF = Pattern.compile("(?:message|motif|ref(?:erence)?\\s*client|reason)\\s*:\\s*([^.]{1,60})", Pattern.CASE_INSENSITIVE);

    // ---------- Classement ----------

    static final Pattern NOISE = Pattern.compile("(code de confirmation|code secret|ne le partagez|ne partagez|otp|mot de passe|profitez|promo|bonus|tapez \\*|composez|gagnez|offre|jeu concours|password|verification code)");
    static final Pattern FAIL = Pattern.compile("(echec|echoue|failed|insuffisant|insufficient|n'a pas pu|not successful|impossible d|refuse|rejected|expire)");
    static final Pattern REVERSAL = Pattern.compile("(reversal|reversement|annulation|annule|rembourse|refund)");
    static final Pattern BILL = Pattern.compile("(facture|bill|eneo|camwater|canal\\+|canal |dstv|startimes|achat de credit|airtime|forfait|bundle|recharge)");
    static final Pattern PAY = Pattern.compile("(paiement|payment|paid|achat|merchant|marchand)");
    static final Pattern DEPOSIT = Pattern.compile("(depot|deposit|cash in|cash-in)");
    static final Pattern WITHDRAW = Pattern.compile("(retrait|withdraw|cash out|cash-out)");
    static final Pattern COMMISSION = Pattern.compile("(commission)");
    static final Pattern INBOUND = Pattern.compile("(vous avez recu|you have received|recu de|received from|recu un|sur votre compte|credite|credited|paiement recu|payment received|has been received|a ete recu|depot recu)");
    static final Pattern OUTBOUND = Pattern.compile("(vous avez transfere|vous avez envoye|you have sent|transfert de .{0,40}(vers|a |au )|transferred|sent to|transfer of|vous avez paye|you have paid|retrait de .{0,30}de votre|debite|debited|vous avez retire|cash out)");

    static boolean has(Pattern p, String s) { return p.matcher(s).find(); }

    /** Analyse un SMS dont l'opérateur (sms.op) est déjà connu. */
    public static Txn parse(Sms sms) {
        String raw = sms.body.replace(' ', ' ').replace(' ', ' ');
        String text = norm(raw);
        String low = text.toLowerCase(Locale.ROOT);
        Txn t = new Txn();
        t.raw = raw;
        t.sender = sms.address;
        t.op = sms.op;
        t.account = sms.op + (sms.sub == null || sms.sub.isEmpty() ? "" : "|" + sms.sub);
        t.sub = sms.sub == null ? "" : sms.sub;
        t.smsDate = sms.date;

        Span bal = findLabeled(text, "nouveau\\s+solde|solde\\s+actuel|solde\\s+disponible|votre\\s+solde|new\\s+balance|your\\s+balance|available\\s+balance|current\\s+balance|solde|balance");
        Span fee = findLabeled(text, "frais|fees?|charges?|cout");
        Span tax = findLabeled(text, "taxe|tax|tta|droit d'accise");
        Span com = findLabeled(text, "commission");
        String txnId = findTxnId(text);

        List<Span> labeled = new ArrayList<>();
        for (Span s : new Span[] {bal, fee, tax, com}) if (s != null) labeled.add(s);
        Long amount = null;
        for (Span a : allAmounts(text)) {
            boolean inLabel = false;
            for (Span s : labeled) if (a.start >= s.start && a.start < s.end) inLabel = true;
            if (!inLabel) { amount = a.value; break; }
        }

        if (bal == null && txnId == null && has(NOISE, low)) { t.kind = Txn.NOISE; return t; }
        if (has(FAIL, low) && bal == null) { t.kind = Txn.FAILED; t.reason = "Opération échouée : aucun mouvement"; return t; }
        if (amount == null || (bal == null && txnId == null)) {
            if (amount == null && bal == null) { t.kind = Txn.NOISE; return t; }
            t.kind = Txn.UNKNOWN;
            t.reason = amount == null ? "Montant introuvable" : "Ni solde ni identifiant de transaction";
            return t;
        }

        boolean in = has(INBOUND, low), out = has(OUTBOUND, low);
        String type = "AUTRE";
        int sens = 0;
        if (has(REVERSAL, low)) { type = "REVERSEMENT"; sens = out ? -1 : (in ? 1 : 0); }
        else if (has(BILL, low)) { type = "FACTURE"; sens = in && !out ? 1 : -1; }
        else if (has(PAY, low)) { type = in && !out ? "PAIEMENT_RECU" : "PAIEMENT_ENVOYE"; sens = in && !out ? 1 : -1; }
        else if (has(DEPOSIT, low)) { type = "DEPOT"; sens = in ? 1 : -1; }
        else if (has(WITHDRAW, low)) { type = "RETRAIT"; sens = in && !out ? 1 : -1; }
        else if (has(COMMISSION, low) && !out) { type = "COMMISSION"; sens = 1; }
        else if (in && !out) { type = "TRANSFERT_RECU"; sens = 1; }
        else if (out) { type = "TRANSFERT_ENVOYE"; sens = -1; }

        t.kind = Txn.TX;
        t.type = type;
        t.sens = sens;
        t.montant = amount;
        t.frais = fee != null && fee.value != null ? fee.value : 0;
        t.taxe = tax != null && tax.value != null ? tax.value : 0;
        t.commission = "COMMISSION".equals(type) ? 0 : (com != null && com.value != null ? com.value : 0);
        t.solde = bal != null ? bal.value : null;
        t.txnId = txnId;
        long inText = findDateInText(text);
        t.date = sms.date > 0 ? sms.date : inText;
        t.nom = findName(raw);
        t.numero = findNumber(text, txnId);
        Matcher mm = MOTIF.matcher(text);
        t.motif = mm.find() ? mm.group(1).trim() : "";
        return t;
    }

    /** Montant avec séparateur de milliers : 125000 -> "125 000". */
    public static String fmt(Long n) {
        if (n == null) return "—";
        long v = n;
        boolean neg = v < 0;
        String s = Long.toString(Math.abs(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (neg ? "-" : "") + b;
    }
}
