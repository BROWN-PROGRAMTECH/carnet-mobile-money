package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Application « Analyse » (version 0.16) : évolution sur 12 mois, analyse des ventes et des dépenses, prévisions et
 * alertes, budgets proposés d'après les dépenses habituelles et suivi de leur utilisation.
 * Tout est calculé sur le téléphone à partir des opérations classées. Montants en centimes.
 * Classe Java pure, testable sur ordinateur.
 */
public final class Analyse {
    private Analyse() {}

    static final long JOUR = 86400000L;
    public static final String[] JOURS = {"Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche"};
    public static final String[] TRANCHES = {"6 h – 9 h", "9 h – 12 h", "12 h – 15 h", "15 h – 18 h", "18 h – 21 h", "21 h – 6 h"};
    /** Une dépense est inhabituelle à partir de 3 fois le montant habituel de son poste, et d'au moins 5 000 FCFA. */
    static final int FACTEUR_INHABITUEL = 3;
    static final long MIN_INHABITUEL = 500000;

    public static final class Mois {
        public long debut, fin;
        public String nom;
        public boolean enCours;
        /** Mois d'avant les premières opérations connues (à ne pas afficher). */
        public boolean vide;
        public Finances.Mois chiffres;
        /** Total des comptes (OM, MoMo, banques, espèces) à la fin du mois, ou maintenant pour le mois en cours. */
        public Long tresorerie;
    }

    public static final class Item {
        public String nom, numero;
        public long montant;
        public int nombre;
        public long derniere;
    }

    public static final class Inhabituelle {
        public Txn sms;
        public String poste;
        public long montant, habituel;
    }

    public static final class BudgetSuivi {
        public String poste;
        /** Budget actuel (0 = aucun), dépense du mois, projection à la fin du mois, moyenne des 3 derniers mois, budget proposé. */
        public long budget, depense, projection, moyenne, propose;
        public int pourcent, pourcentProjete;
        /** % du budget actuel atteint chacun des 6 derniers mois complets (le plus ancien d'abord ; null : pas de budget). */
        public final List<Integer> historique = new ArrayList<>();
        public final List<String> moisHistorique = new ArrayList<>();
    }

    public static final class Rapport {
        public long now, debutMois;
        public int joursMois, joursEcoules;
        /** Les 12 derniers mois, le plus ancien d'abord ; le dernier est le mois en cours. */
        public final List<Mois> mois = new ArrayList<>();

        // Ventes, 90 derniers jours.
        public final long[] ventesJour = new long[7];
        public final int[] nbJour = new int[7];
        public final long[] ventesTranche = new long[6];
        public final int[] nbTranche = new int[6];
        public int nbVentes;
        public long totalVentes, panierMoyen;
        // Clients (paiements reçus par SMS, 12 mois).
        public int clients, fideles, nouveaux, perdus;
        public final List<Item> clientsPerdus = new ArrayList<>();

        // Dépenses, 90 derniers jours.
        public final List<Item> postes = new ArrayList<>();
        public final List<Item> beneficiaires = new ArrayList<>();
        public long sorties, frais;
        public final List<Inhabituelle> inhabituelles = new ArrayList<>();

        // Prévisions du mois en cours.
        public long ventesMois, depensesMois, ventesProjetees, depensesProjetees, moyVentes, moyDepenses;
        public Integer ecartVentes, ecartDepenses;
        public Long tresorerie, tresoreriePrevue, fluxJour, dateManque;
        public final List<String> alertes = new ArrayList<>();

        // Budgets.
        public final List<BudgetSuivi> budgets = new ArrayList<>();

        public String meilleurJour() {
            int b = -1;
            for (int i = 0; i < 7; i++) if (ventesJour[i] > 0 && (b < 0 || ventesJour[i] > ventesJour[b])) b = i;
            return b < 0 ? null : JOURS[b];
        }

        public String meilleureTranche() {
            int b = -1;
            for (int i = 0; i < 6; i++) if (ventesTranche[i] > 0 && (b < 0 || ventesTranche[i] > ventesTranche[b])) b = i;
            return b < 0 ? null : TRANCHES[b];
        }

        public int fraisPourcent() {
            return sorties > 0 ? (int) Math.round(frais * 100.0 / sorties) : 0;
        }

        /** Budgets proposés différents du budget actuel. */
        public List<BudgetSuivi> aAppliquer() {
            List<BudgetSuivi> out = new ArrayList<>();
            for (BudgetSuivi b : budgets) if (b.propose > 0 && b.propose != b.budget) out.add(b);
            return out;
        }
    }

    /**
     * @param bilan   bilan d'une période (début, fin) comme dans l'application
     * @param soldes  relevé des soldes d'une période
     * @param budgets budget mensuel par poste
     */
    public static Rapport calculer(BiFunction<Long, Long, Caisse.Bilan> bilan, BiFunction<Long, Long, Soldes.Releve> soldes,
                                   Map<String, Long> budgets, long now) {
        Rapport r = new Rapport();
        r.now = now;
        r.debutMois = Finances.debutMois(now, 0);
        long finMois = Finances.debutMois(now, 1) - 1;
        r.joursMois = (int) Math.round((finMois + 1 - r.debutMois) / (double) JOUR);
        r.joursEcoules = (int) Math.max(1, Math.min(r.joursMois, (now - r.debutMois) / JOUR + 1));
        if (budgets == null) budgets = new HashMap<>();

        // 1. Évolution sur 12 mois.
        long debut12 = Finances.debutMois(now, -11);
        Soldes.Releve rv = soldes.apply(debut12, now);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMM yy", Locale.FRANCE);
        for (int k = -11; k <= 0; k++) {
            Mois m = new Mois();
            m.debut = Finances.debutMois(now, k);
            m.enCours = k == 0;
            m.fin = m.enCours ? now : Finances.debutMois(now, k + 1) - 1;
            m.nom = f.format(new java.util.Date(m.debut)).replace(".", "");
            m.chiffres = Finances.mois(bilan.apply(m.debut, m.fin));
            m.tresorerie = rv.totalA(m.fin);
            r.mois.add(m);
        }
        // Mois d'avant les premières données : rien à montrer.
        for (Mois m : r.mois) {
            if (m.chiffres.operations > 0 || m.enCours) break;
            m.tresorerie = null;
            m.vide = true;
        }

        // 2. Ventes et dépenses détaillées sur 12 mois (une seule lecture).
        Caisse.Bilan b12 = bilan.apply(debut12, now);
        long d90 = now - 90 * JOUR, d30 = now - 30 * JOUR, d45 = now - 45 * JOUR;
        Calendar c = Calendar.getInstance();
        Map<String, Item> clients = new LinkedHashMap<>();
        Map<String, Long> premiere = new HashMap<>();
        Map<String, java.util.Set<Integer>> moisClient = new HashMap<>();
        Map<String, Item> postes = new LinkedHashMap<>(), benef = new LinkedHashMap<>();
        Map<String, List<Long>> historiquePoste = new HashMap<>();
        List<Caisse.Ligne> recentes = new ArrayList<>();
        for (Caisse.Ligne l : b12.lignes) {
            Txn t = l.sms;
            boolean vente = Caisse.effetResultat(l.categorie) > 0 && t.sens > 0;
            boolean depense = t.sens < 0 && l.categorie != null && (Caisse.ACHAT.equals(l.categorie) || Caisse.estCharge(l.categorie));
            if (vente) {
                String cle = cleClient(t);
                if (!cle.isEmpty()) {
                    Item it = clients.get(cle);
                    if (it == null) { it = new Item(); it.nom = t.nom; it.numero = t.numero; clients.put(cle, it); }
                    if ((it.nom == null || it.nom.isEmpty()) && t.nom != null) it.nom = t.nom;
                    it.montant += t.montant;
                    it.nombre++;
                    it.derniere = Math.max(it.derniere, t.date);
                    premiere.put(cle, Math.min(premiere.getOrDefault(cle, Long.MAX_VALUE), t.date));
                    if (t.date >= Finances.debutMois(now, -2)) {
                        c.setTimeInMillis(t.date);
                        moisClient.computeIfAbsent(cle, x -> new java.util.HashSet<>()).add(c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH));
                    }
                }
                if (t.date >= d90) compterVente(r, c, t.date, t.montant);
            }
            if (t.sens < 0 && t.date >= d90) {
                r.sorties += t.montant;
                r.frais += t.frais;
            }
            if (depense) {
                long montant = t.montant - l.reparti;
                if (montant <= 0) continue;
                if (t.date >= d90) {
                    ajouter(postes, l.categorie, Caisse.categorieNom(l.categorie), null, montant, t.date);
                    String cb = cleClient(t);
                    if (!cb.isEmpty()) ajouter(benef, cb, t.nom != null && !t.nom.trim().isEmpty() ? t.nom.trim() : t.numero, t.numero, montant, t.date);
                }
                if (t.date >= d30) recentes.add(l);
                else if (t.date >= now - 120 * JOUR) historiquePoste.computeIfAbsent(l.categorie, x -> new ArrayList<>()).add(montant);
            }
        }
        for (Caisse.Saisie s : b12.mouvementsEspeces) {
            if (s.annule || s.date < d90) continue;
            String cat = Caisse.normaliserSaisie(s.categorie, s.sens);
            if (s.sens > 0 && Caisse.effetResultat(cat) > 0) compterVente(r, c, s.date, s.montant);
            if (s.sens < 0) {
                r.sorties += s.montant;
                if (Caisse.ACHAT.equals(cat) || Caisse.estCharge(cat)) ajouter(postes, cat, Caisse.categorieNom(cat), null, s.montant, s.date);
            }
        }
        for (Caisse.Saisie s : b12.depensesSuivies) {
            if (s.annule || s.date < d90) continue;
            String cat = Caisse.normaliserSaisie(s.categorie, s.sens);
            if (Caisse.ACHAT.equals(cat) || Caisse.estCharge(cat)) ajouter(postes, cat, Caisse.categorieNom(cat), null, s.montant, s.date);
        }
        r.panierMoyen = r.nbVentes > 0 ? r.totalVentes / r.nbVentes : 0;

        // Clients : fidèles (achat dans au moins 2 des 3 derniers mois), nouveaux (1er achat il y a moins de 30 jours),
        // perdus (au moins 2 achats, plus rien depuis 45 jours).
        List<Item> perdus = new ArrayList<>();
        for (Map.Entry<String, Item> e : clients.entrySet()) {
            Item it = e.getValue();
            if (it.derniere >= d90) r.clients++;
            if (moisClient.getOrDefault(e.getKey(), Collections.emptySet()).size() >= 2) r.fideles++;
            if (premiere.get(e.getKey()) >= d30) r.nouveaux++;
            if (it.nombre >= 2 && it.derniere < d45) perdus.add(it);
        }
        r.perdus = perdus.size();
        perdus.sort((x, y) -> Long.compare(y.montant, x.montant));
        r.clientsPerdus.addAll(perdus.subList(0, Math.min(5, perdus.size())));

        r.postes.addAll(postes.values());
        r.postes.sort((x, y) -> Long.compare(y.montant, x.montant));
        List<Item> bl = new ArrayList<>(benef.values());
        bl.sort((x, y) -> Long.compare(y.montant, x.montant));
        r.beneficiaires.addAll(bl.subList(0, Math.min(5, bl.size())));

        // Dépenses inhabituelles des 30 derniers jours.
        for (Caisse.Ligne l : recentes) {
            List<Long> h = historiquePoste.get(l.categorie);
            if (h == null || h.size() < 3) continue;
            long med = mediane(h);
            long montant = l.sms.montant - l.reparti;
            if (montant >= MIN_INHABITUEL && montant >= FACTEUR_INHABITUEL * med) {
                Inhabituelle x = new Inhabituelle();
                x.sms = l.sms;
                x.poste = l.categorie;
                x.montant = montant;
                x.habituel = med;
                r.inhabituelles.add(x);
            }
        }
        r.inhabituelles.sort((x, y) -> Long.compare(y.montant, x.montant));
        while (r.inhabituelles.size() > 5) r.inhabituelles.remove(r.inhabituelles.size() - 1);

        // 3. Prévisions du mois en cours.
        Mois cour = r.mois.get(r.mois.size() - 1);
        r.ventesMois = cour.chiffres.ventes;
        r.depensesMois = cour.chiffres.depenses + cour.chiffres.frais;
        r.ventesProjetees = r.ventesMois * r.joursMois / r.joursEcoules;
        r.depensesProjetees = r.depensesMois * r.joursMois / r.joursEcoules;
        int n = 0;
        for (int i = r.mois.size() - 4; i < r.mois.size() - 1; i++) {
            Mois m = r.mois.get(i);
            if (m.chiffres.operations == 0) continue;
            r.moyVentes += m.chiffres.ventes;
            r.moyDepenses += m.chiffres.depenses + m.chiffres.frais;
            n++;
        }
        if (n > 0) {
            r.moyVentes /= n;
            r.moyDepenses /= n;
            r.ecartVentes = Finances.variation(r.ventesProjetees, r.moyVentes);
            r.ecartDepenses = Finances.variation(r.depensesProjetees, r.moyDepenses);
        }
        r.tresorerie = rv.totalCloture;
        Long il30 = rv.totalA(now - 30 * JOUR);
        if (r.tresorerie != null && il30 != null && now - 30 * JOUR >= debut12) {
            r.fluxJour = (r.tresorerie - il30) / 30;
            r.tresoreriePrevue = r.tresorerie + r.fluxJour * (r.joursMois - r.joursEcoules);
            if (r.fluxJour < 0 && r.tresorerie > 0) {
                long jours = r.tresorerie / -r.fluxJour;
                if (jours <= 60) r.dateManque = now + jours * JOUR;
            }
        }

        // 4. Budgets : proposition = moyenne des 3 derniers mois complets avec activité, arrondie aux 500 FCFA au-dessus.
        for (String p : Caisse.postesDepense()) {
            BudgetSuivi s = new BudgetSuivi();
            s.poste = p;
            s.budget = budgets.getOrDefault(p, 0L);
            s.depense = cour.chiffres.parPoste.getOrDefault(p, 0L);
            long somme = 0;
            int nb = 0;
            for (int i = r.mois.size() - 4; i < r.mois.size() - 1; i++) {
                Mois m = r.mois.get(i);
                if (m.chiffres.operations == 0) continue;
                somme += m.chiffres.parPoste.getOrDefault(p, 0L);
                nb++;
            }
            s.moyenne = nb > 0 ? somme / nb : 0;
            s.propose = s.moyenne > 0 ? (s.moyenne + 49999) / 50000 * 50000 : 0;
            if (s.budget == 0 && s.depense == 0 && s.moyenne == 0) continue;
            s.projection = s.depense * r.joursMois / r.joursEcoules;
            s.pourcent = s.budget > 0 ? (int) Math.round(s.depense * 100.0 / s.budget) : 0;
            s.pourcentProjete = s.budget > 0 ? (int) Math.round(s.projection * 100.0 / s.budget) : 0;
            for (int i = r.mois.size() - 7; i < r.mois.size() - 1; i++) {
                Mois m = r.mois.get(i);
                s.moisHistorique.add(m.nom);
                s.historique.add(s.budget > 0 ? (Integer) (int) Math.round(m.chiffres.parPoste.getOrDefault(p, 0L) * 100.0 / s.budget) : null);
            }
            r.budgets.add(s);
        }

        // 5. Alertes.
        if (r.dateManque != null && r.dateManque <= finMois + 30 * JOUR)
            r.alertes.add("Au rythme des 30 derniers jours, vos comptes seront vides vers le " + Modules.date(r.dateManque) + ".");
        else if (r.tresoreriePrevue != null && r.tresoreriePrevue < 0)
            r.alertes.add("Trésorerie prévue négative à la fin du mois : " + SmsParser.fmt(r.tresoreriePrevue) + " FCFA.");
        if (r.ecartDepenses != null && r.ecartDepenses >= 20 && r.joursEcoules >= 7)
            r.alertes.add("À ce rythme, vos dépenses du mois seront " + r.ecartDepenses + " % au-dessus de votre moyenne (" + SmsParser.fmt(r.depensesProjetees) + " FCFA).");
        if (r.ecartVentes != null && r.ecartVentes <= -20 && r.joursEcoules >= 7)
            r.alertes.add("Ventes en baisse : à ce rythme, " + (-r.ecartVentes) + " % sous votre moyenne ce mois-ci.");
        for (BudgetSuivi s : r.budgets) {
            if (s.budget <= 0) continue;
            if (s.pourcent >= 100) r.alertes.add("Budget « " + Caisse.categorieNom(s.poste) + " » dépassé : " + s.pourcent + " %.");
            else if (s.pourcentProjete >= 110 && r.joursEcoules >= 5)
                r.alertes.add("Budget « " + Caisse.categorieNom(s.poste) + " » : à ce rythme " + s.pourcentProjete + " % à la fin du mois.");
        }
        if (!r.inhabituelles.isEmpty()) {
            Inhabituelle x = r.inhabituelles.get(0);
            r.alertes.add("Dépense inhabituelle : " + SmsParser.fmt(x.montant) + " FCFA en « " + Caisse.categorieNom(x.poste) + " » le "
                    + Modules.date(x.sms.date) + " (d'habitude " + SmsParser.fmt(x.habituel) + ").");
        }
        if (r.perdus > 0) r.alertes.add(r.perdus + " client(s) réguliers n'ont rien acheté depuis 45 jours.");
        return r;
    }

    static String cleClient(Txn t) {
        if (t.numero != null && !t.numero.isEmpty()) return t.numero;
        return t.nom != null ? t.nom.trim().toUpperCase(Locale.ROOT) : "";
    }

    static void compterVente(Rapport r, Calendar c, long date, long montant) {
        c.setTimeInMillis(date);
        int j = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // lundi = 0
        int h = c.get(Calendar.HOUR_OF_DAY);
        int t = h < 6 || h >= 21 ? 5 : (h - 6) / 3;
        r.ventesJour[j] += montant;
        r.nbJour[j]++;
        r.ventesTranche[t] += montant;
        r.nbTranche[t]++;
        r.nbVentes++;
        r.totalVentes += montant;
    }

    static void ajouter(Map<String, Item> m, String cle, String nom, String numero, long montant, long date) {
        Item it = m.get(cle);
        if (it == null) { it = new Item(); it.nom = nom; it.numero = numero; m.put(cle, it); }
        it.montant += montant;
        it.nombre++;
        it.derniere = Math.max(it.derniere, date);
    }

    static long mediane(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
