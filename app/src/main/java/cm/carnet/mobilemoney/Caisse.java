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
 * L'utilisateur la qualifie avec une catégorie adaptée à son sens (entrée ou sortie) et peut ajuster le motif.
 * Les espèces, sans SMS, sont saisies à la main avec la même catégorie.
 * Montants en centimes. Classe Java pure, testable sur ordinateur.
 */
public final class Caisse {
    private Caisse() {}

    public static final String OM = "OM", MOMO = "MOMO", ESPECES = "ESPECES";

    // ---------- Catégories ----------

    // Entrées uniquement
    public static final String VENTE = "VENTE", APPORT = "APPORT", PRET_RECU = "PRET_RECU";
    // Sorties uniquement
    public static final String ACHAT = "ACHAT", CHARGE = "CHARGE", RETRAIT_PERSO = "RETRAIT_PERSO", REMB_PRET = "REMB_PRET";
    // Entrées et sorties
    public static final String DON = "DON", TRANSFERT = "TRANSFERT", AUTRE = "AUTRE";
    /** Dépôt ou retrait d'un client sur un compte agent (classé automatiquement, entrée ou sortie). */
    public static final String AGENT = "AGENT";
    /** Anciennes catégories (versions 0.3 à 0.6), converties à la lecture selon le sens de l'opération. */
    public static final String PAIEMENT = "PAIEMENT", PERSONNEL = "PERSONNEL";

    /** Toutes les catégories proposées, dans l'ordre des totaux et de l'export. */
    public static final String[] CATEGORIES = {VENTE, APPORT, PRET_RECU, ACHAT, CHARGE, RETRAIT_PERSO, REMB_PRET, DON, TRANSFERT, AUTRE};
    /** Catégories proposées sur une entrée, puis sur une sortie. */
    public static final String[] ENTREES = {VENTE, APPORT, PRET_RECU, DON, TRANSFERT, AUTRE};
    public static final String[] SORTIES = {ACHAT, CHARGE, RETRAIT_PERSO, REMB_PRET, DON, TRANSFERT, AUTRE};

    public static String[] pour(int sens) {
        return sens > 0 ? ENTREES : SORTIES;
    }

    /** +1 : entrée uniquement ; -1 : sortie uniquement ; 0 : les deux. */
    public static int sensDe(String c) {
        if (c == null) return 0;
        switch (c) {
            case VENTE: case APPORT: case PRET_RECU: return 1;
            case ACHAT: case CHARGE: case RETRAIT_PERSO: case REMB_PRET: return -1;
            default: return 0;
        }
    }

    public static boolean compatible(String c, int sens) {
        int s = sensDe(c);
        return s == 0 || s == (sens > 0 ? 1 : -1);
    }

    /**
     * Catégorie à retenir pour une opération de ce sens : convertit les anciennes catégories
     * (Paiement → Achat, Personnel → Apport ou Retrait personnel) et renvoie null (à classer)
     * si la catégorie ne correspond pas au sens (ex. Vente sur une sortie).
     */
    public static String normaliser(String c, int sens) {
        if (c == null) return null;
        switch (c) {
            case PAIEMENT: c = sens < 0 ? ACHAT : null; break;
            case PERSONNEL: c = sens > 0 ? APPORT : RETRAIT_PERSO; break;
            case AGENT: return AGENT;
            default:
                if (!connue(c)) c = AUTRE;
        }
        return c != null && compatible(c, sens) ? c : null;
    }

    /** Pour les espèces, qui ne se reclassent pas : une catégorie incompatible devient « Autre ». */
    public static String normaliserSaisie(String c, int sens) {
        String n = normaliser(c, sens);
        return n != null ? n : AUTRE;
    }

    static boolean connue(String c) {
        for (String x : CATEGORIES) if (x.equals(c)) return true;
        return AGENT.equals(c);
    }

    public static String categorieNom(String c) {
        if (c == null) return "À classer";
        switch (c) {
            case VENTE: return "Vente";
            case APPORT: return "Apport personnel";
            case PRET_RECU: return "Prêt reçu";
            case ACHAT: return "Achat / fournisseur";
            case CHARGE: return "Charge";
            case RETRAIT_PERSO: return "Retrait personnel";
            case REMB_PRET: return "Remboursement de prêt";
            case DON: return "Don";
            case TRANSFERT: return "Transfert entre mes comptes";
            case AGENT: return "Opération client (agent)";
            case PAIEMENT: return "Achat / fournisseur";
            case PERSONNEL: return "Personnel";
            default: return "Autre";
        }
    }

    /** Nom court pour les boutons. */
    public static String court(String c) {
        switch (c) {
            case VENTE: return "Vente";
            case APPORT: return "Apport perso";
            case PRET_RECU: return "Prêt reçu";
            case ACHAT: return "Achat";
            case CHARGE: return "Charge";
            case RETRAIT_PERSO: return "Retrait perso";
            case REMB_PRET: return "Remb. prêt";
            case DON: return "Don";
            case TRANSFERT: return "Transfert";
            default: return "Autre";
        }
    }

    /** Entrée, Sortie ou Les deux (pour l'export). */
    public static String typeNom(String c) {
        int s = sensDe(c);
        return s > 0 ? "Entrée" : s < 0 ? "Sortie" : "Entrée ou sortie";
    }

    /** Effet sur le résultat : +1 vente, -1 achat ou charge, 0 hors résultat. */
    public static int effetResultat(String c) {
        if (VENTE.equals(c)) return 1;
        if (ACHAT.equals(c) || CHARGE.equals(c)) return -1;
        return 0;
    }

    /** Explication courte affichée sous le choix de catégorie. */
    public static String aide(String c) {
        if (c == null) return "Choisissez une catégorie.";
        switch (c) {
            case VENTE: return "Argent reçu d'un client. Compte dans le résultat (+).";
            case APPORT: return "Argent personnel mis dans l'activité. Hors résultat.";
            case PRET_RECU: return "Argent prêté par quelqu'un, à rembourser. Hors résultat.";
            case ACHAT: return "Marchandise ou fournisseur. Compte dans le résultat (−).";
            case CHARGE: return "Loyer, salaire, transport, électricité, taxes… Compte dans le résultat (−).";
            case RETRAIT_PERSO: return "Argent pris pour vous ou votre famille. Hors résultat.";
            case REMB_PRET: return "Remboursement d'un prêt. Hors résultat.";
            case DON: return "Don reçu ou donné. Hors résultat.";
            case TRANSFERT: return "Argent déplacé entre vos propres comptes (OM, MoMo, espèces). Hors résultat.";
            case AGENT: return "Dépôt ou retrait d'un client sur votre compte agent. Hors résultat.";
            default: return "Ne compte pas dans le résultat.";
        }
    }

    /** Clé d'une règle « toujours classer ce contact » : séparée pour les entrées et les sorties. */
    public static String cleRegle(String numero, int sens) {
        return numero + (sens > 0 ? "|E" : "|S");
    }

    /** Catégorie donnée par les règles pour ce contact et ce sens (règle par sens, sinon ancienne règle commune). */
    public static String regle(Map<String, String> regles, String numero, int sens) {
        if (numero == null) return null;
        String c = regles.get(cleRegle(numero, sens));
        if (c == null) c = regles.get(numero);
        return normaliser(c, sens);
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
            String declaree = c != null ? normaliser(c.categorie, t.sens) : null;
            String parRegle;
            if (declaree != null) l.categorie = declaree;
            else if (agent && effetEspecesAgent(t) != 0) { l.categorie = AGENT; l.auto = true; }
            else if ((parRegle = regle(regles, t.numero, t.sens)) != null) { l.categorie = parRegle; l.auto = true; }
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
            if (!s.annule) add(b, normaliserSaisie(s.categorie, s.sens), s.sens, s.montant);
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
        // Résultat = ventes − achats − charges − frais. Apports, retraits personnels, prêts, dons, transferts
        // et opérations clients d'agent n'en font pas partie.
        b.resultat = b.total(VENTE).entrees - b.total(ACHAT).sorties - b.total(CHARGE).sorties - b.frais;
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
