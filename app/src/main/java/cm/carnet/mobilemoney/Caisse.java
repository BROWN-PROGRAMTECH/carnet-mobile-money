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
    /**
     * Mode d'une dépense payée avec un argent suivi (retrait, salaire…), hors caisse espèces (version 0.10).
     * Sa source est la clé de l'opération qui a apporté cet argent.
     */
    public static final String SUIVI = "SUIVI";
    /** Catégorie calculée d'une sortie entièrement répartie en dépenses : jamais enregistrée. */
    public static final String REPARTI = "REPARTI";

    // ---------- Catégories ----------

    // Entrées uniquement
    public static final String VENTE = "VENTE", APPORT = "APPORT", PRET_RECU = "PRET_RECU";
    /** Une personne me rembourse ce que je lui avais prêté (créance). Version 0.10. */
    public static final String REMB_RECU = "REMB_RECU";
    // Sorties uniquement
    public static final String ACHAT = "ACHAT", CHARGE = "CHARGE", RETRAIT_PERSO = "RETRAIT_PERSO", REMB_PRET = "REMB_PRET";
    /** Je prête de l'argent à quelqu'un (créance). Version 0.10. */
    public static final String PRET_ACCORDE = "PRET_ACCORDE";
    /** Postes de charges (v0.9). CHARGE seul = « Autre charge » (et les charges classées avant la v0.9). */
    public static final String CHARGE_LOYER = "CHARGE_LOYER", CHARGE_SALAIRE = "CHARGE_SALAIRE", CHARGE_TRANSPORT = "CHARGE_TRANSPORT",
            CHARGE_ENERGIE = "CHARGE_ENERGIE", CHARGE_TELECOM = "CHARGE_TELECOM", CHARGE_TAXES = "CHARGE_TAXES";
    public static final String[] POSTES_CHARGE = {CHARGE_LOYER, CHARGE_SALAIRE, CHARGE_TRANSPORT, CHARGE_ENERGIE, CHARGE_TELECOM, CHARGE_TAXES, CHARGE};
    /** Postes suivis dans les dépenses et les budgets : achats puis charges. */
    public static final String[] POSTES_DEPENSE = {ACHAT, CHARGE_LOYER, CHARGE_SALAIRE, CHARGE_TRANSPORT, CHARGE_ENERGIE, CHARGE_TELECOM, CHARGE_TAXES, CHARGE};

    public static boolean estCharge(String c) {
        return c != null && c.startsWith(CHARGE);
    }

    // ---------- Catégories créées par l'utilisateur (communes au compte) ----------
    //  - « RECETTE_P… » : entrée qui compte dans le résultat (+) ;
    //  - « CHARGE_P… »  : sortie rangée sous Charges, compte dans le résultat (−), peut avoir un budget ;
    //  - « P_… »        : hors résultat, pour les entrées, les sorties ou les deux.
    // Une ligne dont le code est une catégorie fournie (ex. « VENTE ») sert seulement à la masquer.

    public static final String PREFIXE_RECETTE = "RECETTE_P", PREFIXE_CHARGE = "CHARGE_P", PREFIXE_HORS = "P_";

    public static final class Perso {
        public String code, nom;
        /** Pour « P_… » : +1 entrées, -1 sorties, 0 les deux. */
        public int sens;
        public boolean masquee;
        public long maj;
    }

    private static volatile Map<String, Perso> PERSO = new java.util.LinkedHashMap<>();

    public static void definirPerso(java.util.Collection<Perso> liste) {
        Map<String, Perso> m = new java.util.LinkedHashMap<>();
        for (Perso p : liste) if (p.code != null) m.put(p.code, p);
        PERSO = m;
    }

    public static void ajouterPerso(Perso p) {
        Map<String, Perso> m = new java.util.LinkedHashMap<>(PERSO);
        m.put(p.code, p);
        PERSO = m;
    }

    public static boolean estCree(String c) {
        return c != null && (c.startsWith(PREFIXE_RECETTE) || c.startsWith(PREFIXE_CHARGE) || c.startsWith(PREFIXE_HORS));
    }

    /** Catégories créées (masquées comprises), dans l'ordre de création. */
    public static List<Perso> creees() {
        List<Perso> out = new ArrayList<>();
        for (Perso p : PERSO.values()) if (estCree(p.code)) out.add(p);
        return out;
    }

    public static boolean masquee(String c) {
        Perso p = c == null ? null : PERSO.get(c);
        return p != null && p.masquee;
    }

    /** Autre et Opération client restent toujours disponibles. */
    public static boolean masquable(String c) {
        return c != null && !AUTRE.equals(c) && !AGENT.equals(c);
    }

    /** Catégories proposées pour une opération de ce sens (fournies non masquées, puis créées non masquées). */
    public static List<String> choix(int sens) {
        List<String> out = new ArrayList<>();
        for (String c : pour(sens)) if (!masquee(c)) out.add(c);
        for (Perso p : creees()) if (!p.masquee && compatible(p.code, sens)) out.add(p.code);
        return out;
    }

    /** Postes de dépense : achats, charges fournies, puis charges créées. */
    public static List<String> postesDepense() {
        List<String> out = new ArrayList<>(java.util.Arrays.asList(POSTES_DEPENSE));
        for (Perso p : creees()) if (p.code.startsWith(PREFIXE_CHARGE)) out.add(p.code);
        return out;
    }

    /** Nouveau code de catégorie créée : type +1 recette, -1 charge, 0 hors résultat. */
    public static String nouveauCode(int type) {
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return (type > 0 ? PREFIXE_RECETTE : type < 0 ? PREFIXE_CHARGE : PREFIXE_HORS) + id;
    }
    // Entrées et sorties
    public static final String DON = "DON", TRANSFERT = "TRANSFERT", AUTRE = "AUTRE";
    /** Dépôt ou retrait d'un client sur un compte agent (classé automatiquement, entrée ou sortie). */
    public static final String AGENT = "AGENT";
    /** Anciennes catégories (versions 0.3 à 0.6), converties à la lecture selon le sens de l'opération. */
    public static final String PAIEMENT = "PAIEMENT", PERSONNEL = "PERSONNEL";

    /** Toutes les catégories proposées, dans l'ordre des totaux et de l'export. */
    public static final String[] CATEGORIES = {VENTE, APPORT, PRET_RECU, REMB_RECU, ACHAT, CHARGE_LOYER, CHARGE_SALAIRE, CHARGE_TRANSPORT, CHARGE_ENERGIE,
            CHARGE_TELECOM, CHARGE_TAXES, CHARGE, RETRAIT_PERSO, PRET_ACCORDE, REMB_PRET, DON, TRANSFERT, AUTRE};
    /** Catégories proposées sur une entrée, puis sur une sortie. */
    public static final String[] ENTREES = {VENTE, APPORT, PRET_RECU, REMB_RECU, DON, TRANSFERT, AUTRE};
    public static final String[] SORTIES = {ACHAT, CHARGE_LOYER, CHARGE_SALAIRE, CHARGE_TRANSPORT, CHARGE_ENERGIE, CHARGE_TELECOM, CHARGE_TAXES, CHARGE,
            RETRAIT_PERSO, PRET_ACCORDE, REMB_PRET, DON, TRANSFERT, AUTRE};
    /** Regroupement affiché dans le choix de catégorie. */
    public static final String[] SORTIES_PRINCIPALES = {ACHAT, RETRAIT_PERSO, REMB_PRET};
    public static final String[] LES_DEUX = {DON, TRANSFERT, AUTRE};

    public static String[] pour(int sens) {
        return sens > 0 ? ENTREES : SORTIES;
    }

    /** +1 : entrée uniquement ; -1 : sortie uniquement ; 0 : les deux. */
    public static int sensDe(String c) {
        if (c == null) return 0;
        if (estCharge(c)) return -1;
        if (c.startsWith(PREFIXE_RECETTE)) return 1;
        if (c.startsWith(PREFIXE_HORS)) {
            Perso p = PERSO.get(c);
            return p == null ? 0 : p.sens;
        }
        switch (c) {
            case VENTE: case APPORT: case PRET_RECU: case REMB_RECU: return 1;
            case ACHAT: case RETRAIT_PERSO: case REMB_PRET: case PRET_ACCORDE: return -1;
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
        return AGENT.equals(c) || estCree(c);
    }

    public static String categorieNom(String c) {
        if (c == null) return "À classer";
        if (estCree(c)) {
            Perso p = PERSO.get(c);
            return p != null && p.nom != null && !p.nom.isEmpty() ? p.nom : "Catégorie personnalisée";
        }
        switch (c) {
            case VENTE: return "Vente";
            case APPORT: return "Apport personnel";
            case PRET_RECU: return "Emprunt (je dois)";
            case REMB_RECU: return "Remboursement reçu";
            case PRET_ACCORDE: return "Prêt accordé (on me doit)";
            case REPARTI: return "Réparti";
            case ACHAT: return "Achat / fournisseur";
            case CHARGE: return "Autre charge";
            case CHARGE_LOYER: return "Loyer";
            case CHARGE_SALAIRE: return "Salaires";
            case CHARGE_TRANSPORT: return "Transport";
            case CHARGE_ENERGIE: return "Électricité / eau";
            case CHARGE_TELECOM: return "Téléphone / internet";
            case CHARGE_TAXES: return "Impôts et taxes";
            case RETRAIT_PERSO: return "Retrait personnel";
            case REMB_PRET: return "Remboursement de dette";
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
        if (estCree(c)) {
            String n = categorieNom(c);
            return n.length() > 14 ? n.substring(0, 13) + "…" : n;
        }
        switch (c) {
            case VENTE: return "Vente";
            case APPORT: return "Apport perso";
            case PRET_RECU: return "Emprunt";
            case REMB_RECU: return "Remb. reçu";
            case PRET_ACCORDE: return "Prêt accordé";
            case REPARTI: return "Réparti";
            case ACHAT: return "Achat";
            case CHARGE: return "Autre charge";
            case CHARGE_LOYER: return "Loyer";
            case CHARGE_SALAIRE: return "Salaires";
            case CHARGE_TRANSPORT: return "Transport";
            case CHARGE_ENERGIE: return "Énergie";
            case CHARGE_TELECOM: return "Téléphone";
            case CHARGE_TAXES: return "Taxes";
            case RETRAIT_PERSO: return "Retrait perso";
            case REMB_PRET: return "Remb. dette";
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
        if (VENTE.equals(c) || (c != null && c.startsWith(PREFIXE_RECETTE))) return 1;
        if (ACHAT.equals(c) || estCharge(c)) return -1;
        return 0;
    }

    /** Explication courte affichée sous le choix de catégorie. */
    public static String aide(String c) {
        if (c == null) return "Choisissez une catégorie.";
        if (c.startsWith(PREFIXE_RECETTE)) return "Votre catégorie de recette. Compte dans le résultat (+).";
        if (c.startsWith(PREFIXE_CHARGE)) return "Votre catégorie de charge. Compte dans le résultat (−), peut avoir un budget.";
        if (c.startsWith(PREFIXE_HORS)) return "Votre catégorie. Ne compte pas dans le résultat.";
        switch (c) {
            case VENTE: return "Argent reçu d'un client. Compte dans le résultat (+).";
            case APPORT: return "Argent personnel mis dans l'activité. Hors résultat.";
            case PRET_RECU: return "Quelqu'un vous prête de l'argent : vous le lui devez. Hors résultat.";
            case REMB_RECU: return "Quelqu'un vous rend l'argent que vous lui aviez prêté. Hors résultat.";
            case PRET_ACCORDE: return "Vous prêtez de l'argent : la personne vous le doit. Hors résultat.";
            case REPARTI: return "Sortie répartie entre plusieurs dépenses.";
            case ACHAT: return "Marchandise ou fournisseur. Compte dans le résultat (−).";
            case CHARGE: return "Autre dépense de fonctionnement. Compte dans le résultat (−).";
            case CHARGE_LOYER: return "Loyer de la boutique ou du local. Compte dans le résultat (−).";
            case CHARGE_SALAIRE: return "Salaires et primes des employés. Compte dans le résultat (−).";
            case CHARGE_TRANSPORT: return "Taxi, moto, carburant, livraison. Compte dans le résultat (−).";
            case CHARGE_ENERGIE: return "Électricité (ENEO), eau (Camwater), gaz. Compte dans le résultat (−).";
            case CHARGE_TELECOM: return "Crédit, forfaits internet, téléphone de la boutique. Compte dans le résultat (−).";
            case CHARGE_TAXES: return "Impôts, patente, taxes communales. Compte dans le résultat (−).";
            case RETRAIT_PERSO: return "Argent pris pour vous ou votre famille. Hors résultat.";
            case REMB_PRET: return "Vous remboursez quelqu'un qui vous avait prêté. Hors résultat.";
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
        /** Part de cette sortie répartie en dépenses (centimes) ; le reste garde la catégorie de la ligne. */
        public long reparti;

        /** Reste à justifier d'une sortie répartie sans catégorie pour le reste. */
        public long reste() {
            return sms == null ? 0 : Math.max(0, sms.montant - reparti);
        }
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
        /** Identifiant universel ; source : clé de l'argent qui a payé cette dépense (mode SUIVI), ou null. */
        public String uid, source;
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
        /** Dépenses payées avec un argent suivi (mode SUIVI), du plus récent au plus ancien. */
        public final List<Saisie> depensesSuivies = new ArrayList<>();
        /** Totaux par catégorie ; la clé null regroupe ce qui reste à classer. */
        public final Map<String, Total> totaux = new LinkedHashMap<>();
        public long frais;
        /** Ventes − paiements − frais. */
        public long resultat;

        public Total total(String categorie) {
            Total t = totaux.get(categorie);
            return t != null ? t : new Total();
        }

        /** Somme de toutes les charges, fournies et créées (sorties). */
        public long charges() {
            long t = 0;
            for (Map.Entry<String, Total> e : totaux.entrySet()) if (estCharge(e.getKey())) t += e.getValue().sorties;
            return t;
        }

        /** Ventes + recettes créées par l'utilisateur (entrées qui comptent dans le résultat). */
        public long recettes() {
            long t = total(VENTE).entrees;
            for (Map.Entry<String, Total> e : totaux.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(PREFIXE_RECETTE)) t += e.getValue().entrees;
            }
            return t;
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
            c.agent = OM.equals(op) ? agentOm : MOMO.equals(op) && agentMomo;
            comptes.put(op, c);
        }
        // Parts réparties de chaque sortie (dépenses SUIVI dont la source est cette opération), toutes dates confondues.
        Map<String, Long> parts = new java.util.HashMap<>();
        for (Saisie s : saisies) {
            if (s.annule || !SUIVI.equals(s.mode) || s.source == null) continue;
            parts.put(s.source, parts.getOrDefault(s.source, 0L) + s.montant);
        }
        for (Txn t : r.tx) {
            if (t.isBalanceCheck() || t.date < from || t.date > to) continue;
            boolean agent = OM.equals(t.op) ? agentOm : MOMO.equals(t.op) && agentMomo;
            Ligne l = new Ligne();
            l.sms = t;
            Classement c = classements.get(cle(t));
            String declaree = c != null ? normaliser(c.categorie, t.sens) : null;
            String parRegle;
            if (declaree != null) l.categorie = declaree;
            else if (agent && effetEspecesAgent(t) != 0) { l.categorie = AGENT; l.auto = true; }
            else if ((parRegle = regle(regles, t.numero, t.sens)) != null) { l.categorie = parRegle; l.auto = true; }
            l.motif = c != null && c.motif != null ? c.motif : (t.motif != null ? t.motif : "");
            if (t.sens < 0) l.reparti = Math.min(t.montant, parts.getOrDefault(cle(t), 0L));
            if (l.reparti > 0 && l.reparti >= t.montant && l.categorie == null) l.categorie = REPARTI;
            b.lignes.add(l);
            b.parSms.put(t, l);
            if (t.montant - l.reparti > 0 || l.reparti == 0) add(b, REPARTI.equals(l.categorie) ? null : l.categorie, t.sens, t.montant - l.reparti);
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
        for (Saisie s : saisies) {
            if (!SUIVI.equals(s.mode) || s.date < from || s.date > to) continue;
            b.depensesSuivies.add(s);
            if (!s.annule) add(b, normaliserSaisie(s.categorie, s.sens), s.sens, s.montant);
        }
        b.depensesSuivies.sort((x, y) -> Long.compare(y.date, x.date));

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
            if (!(OM.equals(t.op) ? agentOm : MOMO.equals(t.op) && agentMomo)) continue;
            int effet = effetEspecesAgent(t);
            if (effet > 0) e.depotsClients += t.montant;
            if (effet < 0) e.retraitsClients += t.montant;
        }
        if (e.ouverture != null) e.theo = e.ouverture + e.entrees - e.sorties + e.depotsClients - e.retraitsClients;
        if (e.theo != null && e.compte != null) e.ecart = e.compte - e.theo;

        // 4. Résultat
        // Résultat = ventes − achats − charges − frais. Apports, retraits personnels, prêts, dons, transferts
        // et opérations clients d'agent n'en font pas partie.
        b.resultat = b.recettes() - b.total(ACHAT).sorties - b.charges() - b.frais;
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
