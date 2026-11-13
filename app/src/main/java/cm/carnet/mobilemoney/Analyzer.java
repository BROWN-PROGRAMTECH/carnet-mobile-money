package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reconstitue les comptes à partir des SMS : dédoublonnage, chaîne des soldes,
 * solde réel (dernier SMS) et solde théorique (départ + entrées - sorties - frais).
 * Classe Java pure, testable sur ordinateur.
 */
public final class Analyzer {
    private Analyzer() {}

    public static final class Account {
        public String id, op, sub;
        public int count, breaks;
        public long entrees, sorties, frais;
        public Long start, reel, theo, ecart;
        public long from, to;
    }

    public static final class Result {
        public final List<Txn> tx = new ArrayList<>();
        public final List<Txn> unknown = new ArrayList<>();
        public final List<Account> accounts = new ArrayList<>();
        public int scanned, noise, failed, dup;
    }

    public static Result analyse(List<SmsParser.Sms> messages, SmsParser.Config cfg) {
        Result out = new Result();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (SmsParser.Sms m : messages) {
            i++;
            String op = m.op != null ? m.op : SmsParser.detectOp(m.address, m.body, cfg);
            if (op == null) continue;
            out.scanned++;
            SmsParser.Sms s = new SmsParser.Sms(m.address, m.body, m.date, m.sub);
            s.op = op;
            Txn t = SmsParser.parse(s);
            t.order = i;
            if (t.kind == Txn.NOISE) { out.noise++; continue; }
            if (t.kind == Txn.FAILED) { out.failed++; continue; }
            if (t.kind == Txn.UNKNOWN) { out.unknown.add(t); continue; }
            String key = t.txnId != null
                    ? t.op + "|" + t.txnId
                    : t.op + "|" + t.montant + "|" + t.solde + "|" + (t.date > 0 ? t.date / 60000 : t.raw.hashCode());
            if (!seen.add(key)) { out.dup++; continue; }
            out.tx.add(t);
        }

        // Un seul compte par opérateur et par SIM ; si un opérateur n'a qu'une SIM, on n'affiche pas la SIM.
        Map<String, List<Txn>> byAcct = new LinkedHashMap<>();
        for (Txn t : out.tx) byAcct.computeIfAbsent(t.account, k -> new ArrayList<>()).add(t);

        for (Map.Entry<String, List<Txn>> e : byAcct.entrySet()) {
            List<Txn> list = e.getValue();
            list.sort((a, b) -> a.date != b.date ? Long.compare(a.date, b.date) : Integer.compare(a.order, b.order));
            Long prev = null, start = null;
            long entrees = 0, sorties = 0, frais = 0;
            int breaks = 0;
            Txn last = null;
            for (Txn t : list) {
                if (t.solde != null && prev != null) {
                    long fixed = t.frais + t.taxe;
                    long dIn = prev + t.montant - fixed, dOut = prev - t.montant - fixed;
                    if (t.sens == 0) t.sens = Math.abs(t.solde - dIn) <= Math.abs(t.solde - dOut) ? 1 : -1;
                    long exp = t.sens > 0 ? dIn : dOut;
                    long diff = t.solde - exp;
                    if (Math.abs(diff) > 1) {
                        long alt = t.sens > 0 ? dOut : dIn;
                        long tol = Math.max(1000, t.montant * 6 / 100);
                        if (Math.abs(t.solde - alt) <= 1) {
                            t.sens = -t.sens;
                            t.flags.add("~Sens corrigé par la chaîne des soldes");
                        } else if (diff < 0 && fixed == 0 && -diff <= tol) {
                            t.frais = -diff;
                            t.flags.add("~Frais déduits du solde : " + SmsParser.fmt(-diff) + " FCFA");
                        } else {
                            breaks++;
                            t.gap = diff;
                            t.flags.add("!Rupture de la chaîne des soldes : " + (diff > 0 ? "+" : "") + SmsParser.fmt(diff)
                                    + " FCFA non expliqués (SMS manquant ?)");
                        }
                    }
                }
                if (t.sens == 0) {
                    t.sens = SmsParser.has(SmsParser.INBOUND, SmsParser.norm(t.raw).toLowerCase(Locale.ROOT)) ? 1 : -1;
                    t.flags.add("~Sens incertain");
                }
                if (t.solde == null) t.flags.add("~Solde absent du SMS : non contrôlé");
                if (start == null && t.solde != null) start = t.solde - (t.sens > 0 ? t.montant : -t.montant) + t.frais + t.taxe;
                if (t.sens > 0) entrees += t.montant; else sorties += t.montant;
                frais += t.frais + t.taxe;
                if (t.solde != null) { prev = t.solde; last = t; }
                else if (prev != null) prev = prev + (t.sens > 0 ? t.montant : -t.montant) - t.frais - t.taxe;
            }
            Account a = new Account();
            a.id = e.getKey();
            a.op = list.get(0).op;
            a.sub = list.get(0).sub;
            a.count = list.size();
            a.entrees = entrees;
            a.sorties = sorties;
            a.frais = frais;
            a.breaks = breaks;
            a.start = start;
            a.reel = last != null ? last.solde : null;
            a.theo = start != null ? start + entrees - sorties - frais : null;
            a.ecart = a.reel != null && a.theo != null ? a.reel - a.theo : null;
            a.from = list.get(0).date;
            a.to = list.get(list.size() - 1).date;
            out.accounts.add(a);
        }
        out.accounts.sort((x, y) -> x.id.compareTo(y.id));
        out.tx.sort((a, b) -> a.date != b.date ? Long.compare(b.date, a.date) : Integer.compare(b.order, a.order));
        return out;
    }

    /** Libellé du compte : "Orange Money" ou "Orange Money · SIM 2" quand un opérateur a plusieurs SIM. */
    public static String accountLabel(Account a, List<Account> all) {
        int same = 0;
        for (Account x : all) if (x.op.equals(a.op)) same++;
        return Txn.opName(a.op) + (same > 1 && a.sub != null && !a.sub.isEmpty() ? " · SIM " + a.sub : "");
    }
}
