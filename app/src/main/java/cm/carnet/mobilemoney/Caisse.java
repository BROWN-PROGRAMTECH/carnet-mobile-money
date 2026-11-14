package cm.carnet.mobilemoney;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Caisse automatique : chaque opération lue dans un SMS devient une ligne de caisse au montant verrouillé.
 * L'utilisateur la qualifie (Vente, Paiement, Don, Personnel, Autre) et peut ajuster le motif.
 * Les espèces, sans SMS, sont saisies à la main avec la même catégorie.
 * Montants en centimes. Classe Java pure, testable sur ordinateur.
 */
public final class Caisse {
    private Caisse() {}

    public static final String OM = "OM", MOMO = "MOMO", ESPECES = "ESPECES";

    // ---------- Catégories ----------

    public static final String VENTE = "VENTE", PAIEMENT = "PAIEMENT", DON = "DON", PERSONNEL = "PERSONNEL", AUTRE = "AUTRE";
    /** Dépôt ou retrait d'un client sur un compte agent (classé automatiquement). */
    public static final String AGENT = "AGENT";
    /** Catégories proposées à l'utilisateur, dans l'ordre d'affichage. */
    public static final String[] CATEGORIES = {VENTE, PAIEMENT, DON, PERSONNEL, AUTRE};

    public static String categorieNom(String c) {
        if (c == null) return "À classer";
        switch (c) {
            case VENTE: return "Vente";
            case PAIEMENT: return "Paiement";
            case DON: return "Don";
            case PERSONNEL: return "Personnel";
            case AGENT: return "Opération client (agent)";
            default: return "Autre";
        }
    }

    /** Ce que l'utilisateur a déclaré pour une opération SMS. Le montant n'en fait pas partie : il reste celui du SMS. */
    public static final class Classement {
        public String cle, categorie, motif;
    }

    /** Clé stable d'une opération SMS, pour retrouver son classement. */
    public static String cle(Txn t) {
        if (t.txnId != null) return t.op + "|" + t.txnId;
        return t.op + "|" + t.date + "|" + t.montant + "|" + t.solde;
    }

    /** Une ligne de caisse issue d'un SMS. */
    public static final class Ligne {
        public Txn sms;
        public String categorie;
        public String motif;
        /** true si la catégorie vient d'une règle « toujours classer ce contact ainsi » ou du mode agent. */
        public boolean auto;
    }

    // ---------- Espèces (saisie manuelle) ----------

    /** Un mouvement d'espèces saisi à la main. Jamais modifié : une erreur s'annule et se ressaisit. */
    public static final class Saisie {
        public long id, date, montant;
        public String mode = ESPECES;
        /** +1 entrée, -1 sortie. */
        public int sens = 1;
        public String libelle = "", ref = "", categorie;
        public boolean annule, depuisSms;
    }

    /** Une journée de caisse espèces : fond à l'ouverture, comptage à la clôture. */
    public static final class Jour {
        public String jour;
        public Long fond, compte;
        public String note = "";
        public long clotureAt;
    }

    // ---------- Résultats ----------

    public static final class Compte {
        public String op;
        public boolean agent;
        public Long ouverture, reel, theo, ecart;
        public long entrees, sorties, frais;
        public int alertesSms, aClasser;
        public Analyzer.Account sms;
    }

    public static final class Especes {
        public Long ouverture, theo, compte, ecart;
        public long entrees, sorties, depotsClients, retraitsClients;
        public String origineOuverture = "";
        public long clotureAt;
    }

    /** Totaux d'une catégorie : entrées et sorties, tous moyens confondus (OM, MoMo, espèces). */
    public static final class Total {
        public long entrees, sorties;
        public int count;
    }

    public static final class Bilan {
        public final List<Compte> comptes = new ArrayList<>();
        public final Especes especes = new Especes();
        /** Lignes SMS de la période, de la plus récente à la plus ancienne (consultations de solde exclues). */
        public final List<Ligne> lignes = new ArrayList<>();
        public final List<Ligne> aClasser = new ArrayList<>();
        public final Map<Txn, Ligne> parSms = new IdentityHashMap<>();
        /** Mouvements d'espèces de la période, du plus récent au plus ancien. */
        public final List<Saisie> mouvementsEspeces = new ArrayList<>();
        /** Totaux par catégorie ; la clé null regroupe ce qui reste à classer. */
        public final Map<String, Total> totaux = new LinkedHashMap<>();
        public long frais;
        /** Ventes − paiements − frais. */
        public long resultat;

        public Total total(String categorie) {
            Total t = totaux.get(categorie);
            return t != null ? t : new Total();
        }
    }

    public static String jourDe(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date(millis));
    }

    /** Opérations du compte agent qui font bouger les espèces : dépôt client (+ espèces), retrait client (− espèces). */
    static int effetEspecesAgent(Txn t) {
        if (t.isBalanceCheck()) return 0;
        if (t.sens < 0 && ("DEPOT_CLIENT".equals(t.type) || "DEPOT".equals(t.type))) return 1;
        if (t.sens > 0 && "RETRAIT".equals(t.type)) return -1;
        return 0;
    }

    private static void add(Bilan b, String cat, int sens, long montant) {
        Total t = b.totaux.get(cat);
        if (t == null) { t = new Total(); b.totaux.put(cat, t); }
        if (sens > 0) t.entrees += montant; else t.sorties += montant;
        t.count++;
    }

    /**
     * @param classements classement déclaré par opération (clé = cle(txn))
     * @param regles      catégorie à appliquer automatiquement par numéro de contrepartie
     */
    public static Bilan bilan(Analyzer.Result r, Map<String, Classement> classements, Map<String, String> regles,
                              List<Saisie> saisies, List<Jour> jours, long from, long to, boolean agentOm, boolean agentMomo) {
        Bilan b = new Bilan();
        for (String c : CATEGORIES) b.totaux.put(c, new Total());

        // 1. Lignes de caisse issues des SMS
        Map<String, Compte> comptes = new LinkedHashMap<>();
        for (String op : new String[] {OM, MOMO}) {
            Compte c = new Compte();
            c.op = op;
            c.agent = OM.equals(op) ? agentOm : agentMomo;
            comptes.put(op, c);
        }
        for (Txn t : r.tx) {
            if (t.isBalanceCheck() || t.date < from || t.date > to) continue;
            boolean agent = OM.equals(t.op) ? agentOm : agentMomo;
            Ligne l = new Ligne();
            l.sms = t;
            Classement c = classements.get(cle(t));
            if (c != null && c.categorie != null) l.categorie = c.categorie;
            else if (agent && effetEspecesAgent(t) != 0) { l.categorie = AGENT; l.auto = true; }
            else if (t.numero != null && regles.containsKey(t.numero)) { l.categorie = regles.get(t.numero); l.auto = true; }
            l.motif = c != null && c.motif != null ? c.motif : (t.motif != null ? t.motif : "");
            b.lignes.add(l);
            b.parSms.put(t, l);
            add(b, l.categorie, t.sens, t.montant);
            b.frais += t.frais;
            if (l.categorie == null) {
                b.aClasser.add(l);
                Compte k = comptes.get(t.op);
                if (k != null) k.aClasser++;
            }
        }

        // 2. Comptes électroniques (contrôle par la chaîne des soldes SMS)
        for (Analyzer.Account a : Analyzer.summary(r, from, to)) {
            Compte c = comptes.get(a.op);
            if (c == null) continue;
            c.sms = a;
            c.ouverture = a.ouverture;
            c.reel = a.reel;
            c.entrees = a.entrees;
            c.sorties = a.sorties;
            c.frais = a.frais;
            c.theo = a.theo;
            c.ecart = a.ecart;
            c.alertesSms = a.breaks;
            b.comptes.add(c);
        }

        // 3. Espèces
        for (Saisie s : saisies) {
            if (!ESPECES.equals(s.mode) || s.date < from || s.date > to) continue;
            b.mouvementsEspeces.add(s);
            if (!s.annule) add(b, s.categorie, s.sens, s.montant);
        }
        b.mouvementsEspeces.sort((x, y) -> Long.compare(y.date, x.date));

        Especes e = b.especes;
        List<Jour> tri = new ArrayList<>(jours);
        tri.sort((x, y) -> x.jour.compareTo(y.jour));
        String jFrom = from <= 0 ? "" : jourDe(from), jTo = to == Long.MAX_VALUE ? "9999" : jourDe(to);
        long debut = from;
        for (Jour j : tri) {
            if (j.jour.compareTo(jFrom) >= 0 && j.jour.compareTo(jTo) <= 0 && j.fond != null) {
                e.ouverture = j.fond;
                e.origineOuverture = "Fond de caisse du " + affiche(j.jour);
                debut = Math.max(from, debutJour(j.jour));
                break;
            }
        }
        if (e.ouverture == null) {
            for (Jour j : tri) {
                if (j.jour.compareTo(jFrom) < 0 && j.compte != null) {
                    e.ouverture = j.compte;
                    e.origineOuverture = "Comptage du " + affiche(j.jour);
                }
            }
        }
        long fin = to;
        for (Jour j : tri) {
            if (j.jour.compareTo(jFrom) >= 0 && j.jour.compareTo(jTo) <= 0 && j.compte != null) {
                e.compte = j.compte;
                e.clotureAt = j.clotureAt;
                fin = Math.min(to, j.clotureAt > 0 ? j.clotureAt : to);
            }
        }
        for (Saisie s : saisies) {
            if (s.annule || !ESPECES.equals(s.mode) || s.date < debut || s.date > fin) continue;
            if (s.sens > 0) e.entrees += s.montant; else e.sorties += s.montant;
        }
        for (Txn t : r.tx) {
            if (t.date < debut || t.date > fin) continue;
            if (!(OM.equals(t.op) ? agentOm : agentMomo)) continue;
            int effet = effetEspecesAgent(t);
            if (effet > 0) e.depotsClients += t.montant;
            if (effet < 0) e.retraitsClients += t.montant;
        }
        if (e.ouverture != null) e.theo = e.ouverture + e.entrees - e.sorties + e.depotsClients - e.retraitsClients;
        if (e.theo != null && e.compte != null) e.ecart = e.compte - e.theo;

        // 4. Résultat
        b.resultat = b.total(VENTE).entrees - b.total(VENTE).sorties - b.total(PAIEMENT).sorties + b.total(PAIEMENT).entrees - b.frais;
        return b;
    }

    static long debutJour(String jour) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).parse(jour);
            return d == null ? 0 : d.getTime();
        } catch (Exception ex) {
            return 0;
        }
    }

    static String affiche(String jour) {
        return jour.length() == 10 ? jour.substring(8) + "/" + jour.substring(5, 7) + "/" + jour.substring(0, 4) : jour;
    }

    public static String modeNom(String mode) {
        return OM.equals(mode) ? "Orange Money" : MOMO.equals(mode) ? "MTN MoMo" : "Espèces";
    }
}
