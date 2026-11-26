package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestion des finances : trésorerie totale, mois en cours comparé au précédent, dépenses par poste et budgets,
 * encaissements par moyen de paiement, meilleurs clients. Classe Java pure, testable sur ordinateur.
 * Tous les montants sont en centimes.
 */
public final class Finances {
    private Finances() {}

    // ---------- Trésorerie ----------

    /** Une ligne de trésorerie : compte lu par SMS, espèces, ou avoir saisi à la main. */
    public static final class Poste {
        public String nom, op, id;
        public long montant;
        /** Date de l'information (dernier SMS, saisie) ; 0 si inconnue. */
        public long date;
        public boolean manuel;
    }

    public static final class Tresorerie {
        public final List<Poste> postes = new ArrayList<>();
        public long total;
    }

    /**
     * Ce que le commerçant a maintenant : dernier solde lu de chaque compte (OM, MoMo, banques), espèces du jour
     * (comptées, sinon théoriques) et autres avoirs saisis.
     */
    public static Tresorerie tresorerie(Analyzer.Result r, Caisse.Especes especesDuJour, List<Store.Avoir> avoirs) {
        return tresorerie(r, especes(especesDuJour), avoirs, Long.MAX_VALUE);
    }

    /**
     * Trésorerie à la fin d'une période (version 0.15) : soldes lus jusqu'à « to », espèces à cette date (null : inconnues).
     * Les autres avoirs saisis gardent leur dernière valeur.
     */
    public static Tresorerie tresorerie(Analyzer.Result r, Long especes, List<Store.Avoir> avoirs, long to) {
        Tresorerie t = new Tresorerie();
        for (Analyzer.Account a : Analyzer.summary(r, 0, to)) {
            if (a.reel == null) continue;
            Poste p = new Poste();
            p.op = a.op;
            p.nom = Txn.opName(a.op);
            p.montant = a.reel;
            p.date = a.to;
            t.postes.add(p);
        }
        Long esp = especes;
        if (esp != null) {
            Poste p = new Poste();
            p.op = Caisse.ESPECES;
            p.nom = "Espèces";
            p.montant = esp;
            t.postes.add(p);
        }
        if (avoirs != null) for (Store.Avoir a : avoirs) {
            Poste p = new Poste();
            p.id = a.id;
            p.nom = a.nom;
            p.montant = a.montant;
            p.date = a.maj;
            p.manuel = true;
            t.postes.add(p);
        }
        for (Poste p : t.postes) t.total += p.montant;
        return t;
    }

    /** Espèces d'une journée : montant compté s'il existe, sinon théorique ; null si la caisse n'a pas de fond. */
    public static Long especes(Caisse.Especes e) {
        if (e == null) return null;
        return e.compte != null ? e.compte : e.theo;
    }

    /** Total des soldes lus par SMS (OM, MoMo, banques) à une date (fin de journée). */
    public static long soldesA(Analyzer.Result r, long to) {
        long s = 0;
        for (Analyzer.Account a : Analyzer.summary(r, 0, to)) if (a.reel != null) s += a.reel;
        return s;
    }

    /** Soldes lus par SMS en fin de chacun des derniers jours (le plus ancien d'abord) ; null si aucun solde connu. */
    public static Long[] evolution(Analyzer.Result r, int jours, long now) {
        Long[] out = new Long[jours];
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        long finAujourdhui = c.getTimeInMillis() - 1;
        for (int i = jours - 1; i >= 0; i--) {
            boolean connu = false;
            long s = 0;
            for (Analyzer.Account a : Analyzer.summary(r, 0, finAujourdhui)) if (a.reel != null) { s += a.reel; connu = true; }
            out[i] = connu ? s : null;
            c.add(Calendar.DAY_OF_MONTH, -1);
            finAujourdhui = c.getTimeInMillis() - 1;
        }
        return out;
    }

    // ---------- Mois ----------

    /** Début du mois (décalage 0 = mois en cours, -1 = mois précédent…). */
    public static long debutMois(long now, int decalage) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MONTH, decalage);
        return c.getTimeInMillis();
    }

    /** Même nombre de jours dans le mois précédent (pour comparer un mois en cours à la même période). */
    public static long memeMomentMoisPrecedent(long debutMois, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.MONTH, -1);
        long t = c.getTimeInMillis();
        return Math.min(t, debutMois - 1);
    }

    public static final class Client {
        public String nom, numero;
        public long montant;
        public int operations;
    }

    public static final class Mois {
        public long ventes, achats, charges, frais, resultat, apports, retraits, depenses;
        public int operations, aClasser;
        /** Dépenses par poste (achats puis charges), dans l'ordre des postes. */
        public final Map<String, Long> parPoste = new LinkedHashMap<>();
        /** Ventes par moyen de paiement (Orange Money, MTN MoMo, banque, Espèces). */
        public final Map<String, Long> ventesParMoyen = new LinkedHashMap<>();
        public final List<Client> meilleursClients = new ArrayList<>();
    }

    public static Mois mois(Caisse.Bilan b) {
        Mois m = new Mois();
        m.ventes = b.recettes();
        m.achats = b.total(Caisse.ACHAT).sorties;
        m.charges = b.charges();
        m.depenses = m.achats + m.charges;
        m.frais = b.frais;
        m.resultat = b.resultat;
        m.apports = b.total(Caisse.APPORT).entrees;
        m.retraits = b.total(Caisse.RETRAIT_PERSO).sorties;
        m.aClasser = b.aClasser.size();
        m.operations = b.lignes.size();
        for (String p : Caisse.postesDepense()) m.parPoste.put(p, b.total(p).sorties);

        Map<String, Client> clients = new LinkedHashMap<>();
        for (Caisse.Ligne l : b.lignes) {
            if (Caisse.effetResultat(l.categorie) <= 0 || l.sms == null || l.sms.sens <= 0) continue;
            String moyen = Txn.opName(l.sms.op);
            m.ventesParMoyen.put(moyen, m.ventesParMoyen.getOrDefault(moyen, 0L) + l.sms.montant);
            String cle = l.sms.numero != null && !l.sms.numero.isEmpty() ? l.sms.numero : (l.sms.nom != null ? l.sms.nom.trim().toUpperCase() : "");
            if (cle.isEmpty()) continue;
            Client c = clients.get(cle);
            if (c == null) {
                c = new Client();
                c.numero = l.sms.numero;
                c.nom = l.sms.nom;
                clients.put(cle, c);
            }
            if ((c.nom == null || c.nom.isEmpty()) && l.sms.nom != null) c.nom = l.sms.nom;
            c.montant += l.sms.montant;
            c.operations++;
        }
        for (Caisse.Saisie s : b.mouvementsEspeces) {
            if (s.annule || s.sens <= 0 || Caisse.effetResultat(Caisse.normaliserSaisie(s.categorie, s.sens)) <= 0) continue;
            m.ventesParMoyen.put("Espèces", m.ventesParMoyen.getOrDefault("Espèces", 0L) + s.montant);
        }
        List<Client> tous = new ArrayList<>(clients.values());
        Collections.sort(tous, (x, y) -> Long.compare(y.montant, x.montant));
        for (int i = 0; i < Math.min(5, tous.size()); i++) m.meilleursClients.add(tous.get(i));
        return m;
    }

    /** Variation en pourcentage (arrondie), ou null si la référence est nulle. */
    public static Integer variation(long actuel, long reference) {
        if (reference == 0) return null;
        return (int) Math.round((actuel - reference) * 100.0 / Math.abs(reference));
    }

    // ---------- Budgets ----------

    public static final class Budget {
        public String poste;
        public long depense, budget;
        /** Part du budget utilisée en % (0 si pas de budget). */
        public int pourcent;
    }

    /** Dépenses du mois face au budget de chaque poste (seulement les postes avec une dépense ou un budget). */
    public static List<Budget> budgets(Mois m, Map<String, Long> budgets) {
        List<Budget> out = new ArrayList<>();
        for (String p : Caisse.postesDepense()) {
            long dep = m.parPoste.getOrDefault(p, 0L);
            long bud = budgets.getOrDefault(p, 0L);
            if (dep == 0 && bud == 0) continue;
            Budget b = new Budget();
            b.poste = p;
            b.depense = dep;
            b.budget = bud;
            b.pourcent = bud > 0 ? (int) Math.round(dep * 100.0 / bud) : 0;
            out.add(b);
        }
        return out;
    }
}
