package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ce que l'assistant IA voit de vos chiffres (version 0.13) : des totaux, jamais les SMS.
 * Les personnes (clients, dettes) sont remplacées par « Client 1 », « Personne 2 »… ; la correspondance reste sur le
 * téléphone et sert à remettre les vrais noms dans la réponse. Classe Java pure, testable sur ordinateur.
 */
public final class Contexte {
    private Contexte() {}

    /** Texte envoyé à l'IA et table de correspondance « jeton → vrai nom » (gardée sur le téléphone). */
    public static final class Resultat {
        public final StringBuilder texte = new StringBuilder();
        public final Map<String, String> noms = new LinkedHashMap<>();
    }

    static String f(long centimes) {
        return SmsParser.fmt(centimes) + " FCFA";
    }

    /**
     * @param mois     bilans du mois en cours et des 2 précédents (le plus récent d'abord), avec leur libellé
     * @param semaine  bilan des 7 derniers jours
     * @param aujourdhui bilan du jour
     */
    public static Resultat construire(String caisse, long now, List<String> libellesMois, List<Caisse.Bilan> mois, Caisse.Bilan semaine,
                                      Caisse.Bilan aujourdhui, Finances.Tresorerie tr, Map<String, Long> budgets, Dettes.Bilan dettes,
                                      List<Suivi.Source> suivis) {
        Resultat r = new Resultat();
        StringBuilder t = r.texte;
        t.append("Caisse : ").append(caisse).append(". Date du jour : ").append(Alertes.date(now)).append(".\n");
        if (tr != null) {
            t.append("\nTrésorerie actuelle : ").append(f(tr.total)).append(" (");
            for (int i = 0; i < tr.postes.size(); i++) {
                Finances.Poste p = tr.postes.get(i);
                t.append(i == 0 ? "" : ", ").append(p.manuel ? "avoir « " + p.nom + " »" : p.nom).append(" ").append(f(p.montant));
            }
            t.append(").\n");
        }
        bloc(t, "Aujourd'hui", aujourdhui, null);
        bloc(t, "7 derniers jours", semaine, null);
        for (int i = 0; i < mois.size(); i++) bloc(t, libellesMois.get(i), mois.get(i), i == 0 ? r : null);
        if (budgets != null && !budgets.isEmpty() && !mois.isEmpty()) {
            t.append("\nBudgets du mois (dépensé / budget) : ");
            Finances.Mois m = Finances.mois(mois.get(0));
            boolean premier = true;
            for (Finances.Budget b : Finances.budgets(m, budgets)) {
                if (b.budget <= 0) continue;
                t.append(premier ? "" : " ; ").append(Caisse.categorieNom(b.poste)).append(" ").append(f(b.depense)).append(" / ").append(f(b.budget));
                premier = false;
            }
            t.append(".\n");
        }
        if (dettes != null && !dettes.personnes.isEmpty()) {
            t.append("\nDettes et créances : on me doit ").append(f(dettes.onMeDoit)).append(", je dois ").append(f(dettes.jeDois)).append(".\n");
            int i = 0;
            for (Dettes.Personne p : dettes.personnes) {
                if (p.soldee() || i >= 15) continue;
                String jeton = "Personne " + (++i);
                r.noms.put(jeton, p.nom);
                t.append("- ").append(jeton).append(p.net() > 0 ? " me doit " + f(p.net()) : " : je lui dois " + f(-p.net()));
                if (p.echeance != null) t.append(p.enRetard(now) ? " (en retard depuis le " : " (à rembourser avant le ").append(Alertes.date(p.echeance)).append(")");
                t.append(".\n");
            }
        }
        if (suivis != null && !suivis.isEmpty()) {
            t.append("\nArgent suivi :\n");
            int i = 0;
            for (Suivi.Source s : suivis) {
                if (i++ >= 5) break;
                t.append("- ").append(s.sens > 0 ? "Entrée" : "Sortie").append(" de ").append(f(s.montant)).append(" du ").append(Alertes.date(s.date))
                        .append(s.categorie != null ? " (" + Caisse.categorieNom(s.categorie) + ")" : "").append(" : utilisé ").append(f(s.utilise))
                        .append(", reste ").append(f(s.reste())).append(".\n");
            }
        }
        return r;
    }

    private static void bloc(StringBuilder t, String titre, Caisse.Bilan b, Resultat clients) {
        if (b == null) return;
        Finances.Mois m = Finances.mois(b);
        t.append("\n").append(titre).append(" : ventes et recettes ").append(f(m.ventes)).append(", achats ").append(f(m.achats))
                .append(", charges ").append(f(m.charges)).append(", frais ").append(f(m.frais)).append(", résultat ").append(f(m.resultat))
                .append(", apports ").append(f(m.apports)).append(", retraits personnels ").append(f(m.retraits))
                .append(", ").append(m.operations).append(" opérations dont ").append(m.aClasser).append(" à classer.\n");
        StringBuilder e = new StringBuilder(), s = new StringBuilder();
        for (Map.Entry<String, Caisse.Total> x : b.totaux.entrySet()) {
            if (x.getValue().entrees > 0) e.append(e.length() == 0 ? "" : ", ").append(Caisse.categorieNom(x.getKey())).append(" ").append(f(x.getValue().entrees));
            if (x.getValue().sorties > 0) s.append(s.length() == 0 ? "" : ", ").append(Caisse.categorieNom(x.getKey())).append(" ").append(f(x.getValue().sorties));
        }
        if (e.length() > 0) t.append("  Entrées par catégorie : ").append(e).append(".\n");
        if (s.length() > 0) t.append("  Sorties par catégorie : ").append(s).append(".\n");
        if (!m.ventesParMoyen.isEmpty()) {
            t.append("  Ventes par moyen : ");
            boolean p = true;
            for (Map.Entry<String, Long> x : m.ventesParMoyen.entrySet()) { t.append(p ? "" : ", ").append(x.getKey()).append(" ").append(f(x.getValue())); p = false; }
            t.append(".\n");
        }
        if (clients != null && !m.meilleursClients.isEmpty()) {
            t.append("  Meilleurs clients : ");
            int i = 0;
            for (Finances.Client c : m.meilleursClients) {
                String jeton = "Client " + (++i);
                clients.noms.put(jeton, c.nom != null && !c.nom.isEmpty() ? c.nom : (c.numero != null ? c.numero : jeton));
                t.append(i == 1 ? "" : ", ").append(jeton).append(" ").append(f(c.montant)).append(" (").append(c.operations).append(" achats)");
            }
            t.append(".\n");
        }
    }

    /** Remet les vrais noms à la place des jetons dans la réponse de l'IA (les plus longs d'abord : « Client 10 » avant « Client 1 »). */
    public static String demasquer(String reponse, Map<String, String> noms) {
        if (reponse == null) return "";
        List<String> cles = new ArrayList<>(noms.keySet());
        cles.sort((a, b) -> b.length() - a.length());
        String out = reponse;
        for (String k : cles) out = out.replace(k, noms.get(k));
        return out;
    }
}
