package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstitue les comptes à partir des SMS : dédoublonnage par ID, chaîne des soldes,
 * puis synthèse sur une période (solde d'ouverture, entrées, sorties, frais, théorique, réel).
 * Un seul compte par opérateur : l'identifiant de SIM d'Android change avec le téléphone ou la carte,
 * il ne désigne pas un compte. Classe Java pure, testable sur ordinateur.
 */
public final class Analyzer {
    private Analyzer() {}

    /** Tolérance d'arrondi de la chaîne des soldes : 1 FCFA. */
    static final long TOL = 100;

    public static final class Account {
        public String op;
        public int count, breaks;
        public long entrees, sorties, frais;
        public Long ouverture, reel, theo, ecart;
        public long from, to;
    }

    public static final class Result {
        /** Toutes les opérations retenues, de la plus récente à la plus ancienne (consultations de solde comprises). */
        public final List<Txn> tx = new ArrayList<>();
        public final List<Txn> unknown = new ArrayList<>();
        public int scanned, noise, failed, dup;
        public String ownerOm = "";
    }

    public static Result analyse(List<SmsParser.Sms> messages, SmsParser.Config cfg) {
        Result out = new Result();
        List<SmsParser.Sms> mm = new ArrayList<>();
        List<SmsParser.Sms> om = new ArrayList<>();
        for (SmsParser.Sms m : messages) {
            String op = m.op != null ? m.op : SmsParser.detectOp(m.address, m.body, cfg);
            if (op == null) continue;
            SmsParser.Sms s = new SmsParser.Sms(m.address, m.body, m.date, m.sub);
            s.op = op;
            mm.add(s);
            if ("OM".equals(op)) om.add(s);
        }
        // Numéro du titulaire Orange Money : saisi dans les réglages, sinon détecté.
        SmsParser.Config c = new SmsParser.Config();
        c.om.addAll(cfg.om);
        c.momo.addAll(cfg.momo);
        c.auto = cfg.auto;
        c.ownerOm = cfg.ownerOm != null && cfg.ownerOm.matches("6\\d{8}") ? cfg.ownerOm : SmsParser.detectOwner(om);
        out.ownerOm = c.ownerOm;

        Set<String> seen = new HashSet<>();
        int i = 0;
        for (SmsParser.Sms s : mm) {
            i++;
            out.scanned++;
            Txn t = SmsParser.parse(s, c);
            t.order = i;
            if (t.kind == Txn.NOISE) { out.noise++; continue; }
            if (t.kind == Txn.FAILED) { out.failed++; continue; }
            if (t.kind == Txn.UNKNOWN) { out.unknown.add(t); continue; }
            String key = t.txnId != null
                    ? t.op + "|" + t.txnId
                    : t.op + "|" + t.type + "|" + t.montant + "|" + t.solde + "|" + (t.date / 60000);
            if (!seen.add(key)) { out.dup++; continue; }
            out.tx.add(t);
        }

        Map<String, List<Txn>> byOp = new LinkedHashMap<>();
        for (Txn t : out.tx) byOp.computeIfAbsent(t.op, k -> new ArrayList<>()).add(t);
        for (List<Txn> list : byOp.values()) chain(list);
        out.tx.sort((a, b) -> a.date != b.date ? Long.compare(b.date, a.date) : Integer.compare(b.order, a.order));
        return out;
    }

    /** Parcourt les opérations d'un compte dans l'ordre et vérifie que chaque solde se déduit du précédent. */
    static void chain(List<Txn> list) {
        list.sort((a, b) -> a.date != b.date ? Long.compare(a.date, b.date) : Integer.compare(a.order, b.order));
        Long prev = null;
        for (Txn t : list) {
            t.avant = prev;
            if (t.isBalanceCheck()) {
                if (t.avant == null) t.avant = t.solde;
                if (prev != null && Math.abs(t.solde - prev) > TOL) {
                    t.flags.add("!Écart constaté à la consultation : " + signed(t.solde - prev) + " FCFA (SMS manquant ?)");
                }
                prev = t.solde;
                t.apres = prev;
                continue;
            }
            long fixed = t.frais;
            if (t.solde != null && prev != null) {
                long dIn = prev + t.montant - fixed, dOut = prev - t.montant - fixed;
                if (t.sens == 0) {
                    t.sens = Math.abs(t.solde - dIn) <= Math.abs(t.solde - dOut) ? 1 : -1;
                    if ("TRANSFERT".equals(t.type)) t.type = t.sens > 0 ? "TRANSFERT_RECU" : "TRANSFERT_ENVOYE";
                }
                long diff = t.solde - (t.sens > 0 ? dIn : dOut);
                if (Math.abs(diff) > TOL) {
                    long alt = t.sens > 0 ? dOut : dIn;
                    long tol = Math.max(100000, t.montant * 6 / 100);
                    if (Math.abs(t.solde - alt) <= TOL) {
                        t.sens = -t.sens;
                        t.flags.add("~Sens corrigé par la chaîne des soldes");
                    } else if (diff < 0 && fixed == 0 && -diff <= tol) {
                        t.frais = -diff;
                        t.flags.add("~Frais déduits du solde : " + SmsParser.fmt(-diff) + " FCFA");
                    } else {
                        t.flags.add("!Rupture de la chaîne des soldes : " + signed(diff) + " FCFA non expliqués (SMS manquant ?)");
                    }
                }
            }
            if (t.sens == 0) {
                t.sens = -1;
                t.flags.add("~Sens inconnu : compté comme sortie");
                if ("TRANSFERT".equals(t.type)) t.type = "TRANSFERT_ENVOYE";
            }
            if (t.solde == null) t.flags.add("~Solde absent du SMS : non contrôlé");
            if (t.solde != null) prev = t.solde;
            else if (prev != null) prev = prev + t.sens * t.montant - t.frais;
            t.apres = prev;
            if (t.avant == null && t.solde != null) t.avant = t.solde - t.sens * t.montant + t.frais;
        }
    }

    /** Synthèse par opérateur sur la période [from, to] (millisecondes ; 0 et Long.MAX_VALUE pour tout). */
    public static List<Account> summary(Result r, long from, long to) {
        Map<String, Account> acc = new LinkedHashMap<>();
        // r.tx est trié du plus récent au plus ancien : on le parcourt à l'envers.
        for (int k = r.tx.size() - 1; k >= 0; k--) {
            Txn t = r.tx.get(k);
            Account a = acc.get(t.op);
            if (a == null) { a = new Account(); a.op = t.op; acc.put(t.op, a); }
            if (t.date < from) {
                if (t.apres != null) a.ouverture = t.apres;
                a.reel = t.apres;
                continue;
            }
            if (t.date > to) continue;
            if (a.count == 0) {
                a.from = t.date;
                if (a.ouverture == null) a.ouverture = t.avant;
            }
            a.count++;
            a.to = t.date;
            for (String f : t.flags) if (f.startsWith("!")) a.breaks++;
            if (!t.isBalanceCheck()) {
                if (t.sens > 0) a.entrees += t.montant; else a.sorties += t.montant;
                a.frais += t.frais;
            }
            if (t.apres != null) a.reel = t.apres;
        }
        List<Account> out = new ArrayList<>();
        for (Account a : acc.values()) {
            a.theo = a.ouverture != null ? a.ouverture + a.entrees - a.sorties - a.frais : null;
            a.ecart = a.reel != null && a.theo != null ? a.reel - a.theo : null;
            out.add(a);
        }
        out.sort((x, y) -> x.op.compareTo(y.op));
        return out;
    }

    static String signed(long cents) {
        return (cents > 0 ? "+" : "") + SmsParser.fmt(cents);
    }
}
