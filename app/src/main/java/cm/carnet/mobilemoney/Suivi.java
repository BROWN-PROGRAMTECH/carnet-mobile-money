package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suivi de l'argent (version 0.10) : d'où vient l'argent et ce qu'on en a fait.
 *  - Une source est une opération qui apporte ou sort de l'argent à suivre : un salaire ou toute autre entrée,
 *    un retrait, un transfert.
 *  - Ses utilisations : les dépenses payées avec cet argent (saisies en mode SUIVI, avec la catégorie de chacune)
 *    et les opérations existantes rattachées (une sortie OM, un paiement en espèces, un retrait…).
 *  - Reste = montant de la source − utilisations (« reste à justifier » pour une sortie, « reste disponible »
 *    pour une entrée).
 * Rattachements : cle de l'opération → cle de la source. Une ligne cle = source marque une source suivie, même
 * sans utilisation. Classe Java pure, testable sur ordinateur. Montants en centimes.
 */
public final class Suivi {
    private Suivi() {}

    /** Clé d'une saisie (espèces ou dépense suivie), comparable à la clé d'une opération SMS. */
    public static String cle(Caisse.Saisie s) {
        return "S|" + s.uid;
    }

    public static final class Usage {
        public String cle, categorie, libelle;
        public Txn sms;
        public Caisse.Saisie saisie;
        public long montant, date;
        /** true : dépense saisie dans le suivi (on peut l'annuler) ; false : opération rattachée (on peut la détacher). */
        public boolean depense;
    }

    public static final class Source {
        public String cle, libelle, categorie;
        public Txn sms;
        public Caisse.Saisie saisie;
        public int sens;
        public long montant, date, utilise;
        public final List<Usage> usages = new ArrayList<>();

        public long reste() {
            return montant - utilise;
        }

        public int pourcent() {
            return montant <= 0 ? 0 : (int) Math.min(999, Math.round(utilise * 100.0 / montant));
        }
    }

    /** Opération (SMS ou saisie) retrouvée par sa clé. */
    private static final class Op {
        Txn sms;
        Caisse.Saisie saisie;
    }

    /**
     * Toutes les sources suivies, la plus récente d'abord.
     * @param liens       rattachements (cle opération → cle source), sans les détachements
     * @param classements catégories déclarées (pour afficher la catégorie des opérations rattachées)
     */
    public static List<Source> sources(Analyzer.Result r, List<Caisse.Saisie> saisies, Map<String, String> liens,
                                       Map<String, Caisse.Classement> classements) {
        Map<String, Op> ops = new HashMap<>();
        if (r != null) for (Txn t : r.tx) {
            if (t.isBalanceCheck()) continue;
            Op o = new Op();
            o.sms = t;
            ops.put(Caisse.cle(t), o);
        }
        for (Caisse.Saisie s : saisies) {
            if (s.uid == null) continue;
            Op o = new Op();
            o.saisie = s;
            ops.put(cle(s), o);
        }

        Map<String, Source> m = new LinkedHashMap<>();
        java.util.function.Function<String, Source> source = k -> {
            Source src = m.get(k);
            if (src != null) return src;
            Op o = ops.get(k);
            if (o == null) return null; // opération pas (encore) sur ce téléphone
            src = new Source();
            src.cle = k;
            if (o.sms != null) {
                src.sms = o.sms;
                src.sens = o.sms.sens;
                src.montant = o.sms.montant;
                src.date = o.sms.date;
                String qui = o.sms.nom != null ? o.sms.nom : o.sms.numero;
                src.libelle = o.sms.typeLabel() + (qui != null ? " · " + qui : "");
                Caisse.Classement c = classements == null ? null : classements.get(k);
                src.categorie = c != null ? Caisse.normaliser(c.categorie, o.sms.sens) : null;
            } else {
                src.saisie = o.saisie;
                src.sens = o.saisie.sens;
                src.montant = o.saisie.montant;
                src.date = o.saisie.date;
                src.libelle = (o.saisie.libelle == null || o.saisie.libelle.isEmpty() ? "Espèces" : o.saisie.libelle);
                src.categorie = Caisse.normaliserSaisie(o.saisie.categorie, o.saisie.sens);
            }
            m.put(k, src);
            return src;
        };

        // Sources marquées (cle = source), puis celles qui ont des utilisations.
        for (Map.Entry<String, String> e : liens.entrySet()) {
            if (e.getValue() != null && e.getValue().equals(e.getKey())) source.apply(e.getKey());
        }
        for (Caisse.Saisie s : saisies) {
            if (s.annule || !Caisse.SUIVI.equals(s.mode) || s.source == null) continue;
            Source src = source.apply(s.source);
            if (src == null) continue;
            Usage u = new Usage();
            u.cle = cle(s);
            u.saisie = s;
            u.depense = true;
            u.montant = s.montant;
            u.date = s.date;
            u.categorie = Caisse.normaliserSaisie(s.categorie, s.sens);
            u.libelle = s.libelle;
            src.usages.add(u);
            src.utilise += s.montant;
        }
        for (Map.Entry<String, String> e : liens.entrySet()) {
            String k = e.getKey(), sk = e.getValue();
            if (sk == null || sk.equals(k)) continue;
            Source src = source.apply(sk);
            Op o = ops.get(k);
            if (src == null || o == null) continue;
            if (o.saisie != null && o.saisie.annule) continue;
            Usage u = new Usage();
            u.cle = k;
            if (o.sms != null) {
                u.sms = o.sms;
                u.montant = o.sms.montant;
                u.date = o.sms.date;
                Caisse.Classement c = classements == null ? null : classements.get(k);
                u.categorie = c != null ? Caisse.normaliser(c.categorie, o.sms.sens) : null;
                String qui = o.sms.nom != null ? o.sms.nom : o.sms.numero;
                u.libelle = o.sms.typeLabel() + (qui != null ? " · " + qui : "");
            } else {
                u.saisie = o.saisie;
                u.montant = o.saisie.montant;
                u.date = o.saisie.date;
                u.categorie = Caisse.normaliserSaisie(o.saisie.categorie, o.saisie.sens);
                u.libelle = o.saisie.libelle == null || o.saisie.libelle.isEmpty() ? "Espèces" : o.saisie.libelle;
            }
            src.usages.add(u);
            src.utilise += u.montant;
        }
        List<Source> out = new ArrayList<>(m.values());
        for (Source s : out) Collections.sort(s.usages, (a, b) -> Long.compare(b.date, a.date));
        Collections.sort(out, (a, b) -> Long.compare(b.date, a.date));
        return out;
    }

    /** Cherche une source par sa clé. */
    public static Source trouver(List<Source> liste, String cle) {
        for (Source s : liste) if (s.cle.equals(cle)) return s;
        return null;
    }

    /**
     * Opérations qu'on peut rattacher à une source : sorties (SMS ou espèces) à partir de la date de la source,
     * pas encore rattachées ailleurs, la plus récente d'abord. Une sortie répartie elle-même (source) est proposée
     * aussi : un retrait payé avec le salaire, par exemple.
     */
    public static List<Usage> rattachables(Source src, Analyzer.Result r, List<Caisse.Saisie> saisies, Map<String, String> liens, int max) {
        List<Usage> out = new ArrayList<>();
        long depuis = src.date - 60L * 1000;
        if (r != null) for (Txn t : r.tx) {
            if (out.size() >= max) break;
            if (t.isBalanceCheck() || t.sens >= 0 || t.date < depuis) continue;
            String k = Caisse.cle(t);
            if (k.equals(src.cle) || (liens.get(k) != null && !k.equals(liens.get(k)))) continue;
            Usage u = new Usage();
            u.cle = k;
            u.sms = t;
            u.montant = t.montant;
            u.date = t.date;
            String qui = t.nom != null ? t.nom : t.numero;
            u.libelle = t.typeLabel() + (qui != null ? " · " + qui : "");
            out.add(u);
        }
        List<Usage> esp = new ArrayList<>();
        for (Caisse.Saisie s : saisies) {
            if (s.annule || s.uid == null || !Caisse.ESPECES.equals(s.mode) || s.sens >= 0 || s.date < depuis) continue;
            String k = cle(s);
            if (k.equals(src.cle) || (liens.get(k) != null && !k.equals(liens.get(k)))) continue;
            Usage u = new Usage();
            u.cle = k;
            u.saisie = s;
            u.montant = s.montant;
            u.date = s.date;
            u.categorie = Caisse.normaliserSaisie(s.categorie, s.sens);
            u.libelle = "Espèces" + (s.libelle == null || s.libelle.isEmpty() ? "" : " · " + s.libelle);
            esp.add(u);
        }
        out.addAll(esp);
        Collections.sort(out, (a, b) -> Long.compare(b.date, a.date));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Vrai pour une sortie qu'on répartit (ses dépenses remplacent la sortie dans les totaux). */
    public static boolean repartition(Source s) {
        return s.sms != null && s.sens < 0;
    }
}
