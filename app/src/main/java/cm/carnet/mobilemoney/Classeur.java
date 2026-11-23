package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classement automatique (version 0.13, application « Assistant IA »).
 * Apprend sur le téléphone, à partir des opérations déjà classées par l'utilisateur :
 *  - même contact (numéro, sinon nom) dans le même sens : indice fort ;
 *  - même type d'opération et même tranche de montant : indice moyen ;
 *  - même type d'opération : indice faible.
 * Pour les opérations sans indice suffisant (contact nouveau), l'IA en ligne peut proposer une catégorie.
 * Classe Java pure, testable sur ordinateur.
 */
public final class Classeur {
    private Classeur() {}

    public static final class Proposition {
        public String cle, categorie;
        /** Confiance de 0 à 100. */
        public int confiance;
        /** "local" (appris sur le téléphone) ou "ia" (proposé par l'IA en ligne). */
        public String source = "local";
    }

    static String contact(Txn t) {
        if (t.numero != null && !t.numero.isEmpty()) return "n:" + Recharges.neuf(t.numero);
        if (t.nom != null && !t.nom.trim().isEmpty()) return "m:" + Dettes.clePersonne(t.nom);
        return null;
    }

    /** Tranche de montant (puissance de 10 en FCFA, à mi-chemin près) : 1 000 et 1 500 vont ensemble, pas 1 000 et 20 000. */
    static int tranche(long centimes) {
        double f = Math.max(1, centimes / 100.0);
        return (int) Math.round(Math.log10(f) * 2);
    }

    /** Ce qu'il a appris : compteurs par indice et par catégorie. */
    public static final class Modele {
        final Map<String, Map<String, Integer>> parIndice = new HashMap<>();
        int exemples;

        void ajouter(String indice, String cat) {
            Map<String, Integer> m = parIndice.computeIfAbsent(indice, k -> new HashMap<>());
            m.put(cat, m.getOrDefault(cat, 0) + 1);
        }
    }

    /** Apprend sur toutes les opérations classées par l'utilisateur (pas sur les classements automatiques). */
    public static Modele apprendre(Analyzer.Result r, Map<String, Caisse.Classement> classements) {
        Modele m = new Modele();
        for (Txn t : r.tx) {
            if (t.isBalanceCheck() || t.sens == 0) continue;
            Caisse.Classement c = classements.get(Caisse.cle(t));
            if (c == null || c.categorie == null) continue;
            String cat = Caisse.normaliser(c.categorie, t.sens);
            if (cat == null || Caisse.AGENT.equals(cat)) continue;
            m.exemples++;
            String k = contact(t);
            if (k != null) m.ajouter("c|" + t.sens + "|" + k, cat);
            m.ajouter("tm|" + t.op + "|" + t.type + "|" + t.sens + "|" + tranche(t.montant), cat);
            m.ajouter("t|" + t.op + "|" + t.type + "|" + t.sens, cat);
        }
        return m;
    }

    /** Proposition pour une opération, ou null si rien n'est assez sûr (en dessous de 50 %). */
    public static Proposition proposer(Modele m, Txn t) {
        if (t.isBalanceCheck() || t.sens == 0) return null;
        Map<String, Double> score = new HashMap<>();
        double poids = 0;
        String k = contact(t);
        Map<String, Integer> pc = k == null ? null : m.parIndice.get("c|" + t.sens + "|" + k);
        Map<String, Integer> ptm = m.parIndice.get("tm|" + t.op + "|" + t.type + "|" + t.sens + "|" + tranche(t.montant));
        Map<String, Integer> pt = m.parIndice.get("t|" + t.op + "|" + t.type + "|" + t.sens);
        poids += ajouter(score, pc, 6);
        poids += ajouter(score, ptm, 2);
        poids += ajouter(score, pt, 1);
        if (poids == 0) return null;
        String best = null;
        double bs = 0;
        for (Map.Entry<String, Double> e : score.entrySet()) if (e.getValue() > bs) { bs = e.getValue(); best = e.getKey(); }
        int conf = (int) Math.round(bs * 100 / poids);
        // Peu d'exemples : on modère la confiance (1 exemple du contact ne suffit pas pour 100 %).
        int n = pc == null ? 0 : total(pc);
        if (n < 3) conf = Math.min(conf, n == 0 ? 70 : 85);
        if (conf < 50 || best == null || !Caisse.compatible(best, t.sens)) return null;
        Proposition p = new Proposition();
        p.cle = Caisse.cle(t);
        p.categorie = best;
        p.confiance = conf;
        return p;
    }

    private static int total(Map<String, Integer> m) {
        int s = 0;
        for (int v : m.values()) s += v;
        return s;
    }

    /** Ajoute les parts d'un indice (normalisées) pondérées ; renvoie le poids ajouté. */
    private static double ajouter(Map<String, Double> score, Map<String, Integer> m, double w) {
        if (m == null || m.isEmpty()) return 0;
        int tot = total(m);
        for (Map.Entry<String, Integer> e : m.entrySet()) score.put(e.getKey(), score.getOrDefault(e.getKey(), 0.0) + w * e.getValue() / tot);
        return w;
    }

    /** Propositions pour toutes les opérations à classer d'une liste. */
    public static List<Proposition> proposer(Modele m, List<Caisse.Ligne> aClasser) {
        List<Proposition> out = new ArrayList<>();
        for (Caisse.Ligne l : aClasser) {
            if (l.sms == null) continue;
            Proposition p = proposer(m, l.sms);
            if (p != null) out.add(p);
        }
        return out;
    }
}
