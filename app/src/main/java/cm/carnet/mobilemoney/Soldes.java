package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Soldes sur une période (version 0.15) : ouverture, clôture, et solde de chaque compte (Orange Money, MTN MoMo,
 * banques, espèces) avec leur total après chaque opération, dans l'ordre où elles ont eu lieu.
 * Comptes électroniques : solde lu ou reconstitué d'après les SMS. Espèces : fond de caisse ou dernier comptage,
 * puis mouvements saisis (et opérations clients en mode agent) ; un comptage remet le solde au montant compté.
 * Montants en centimes. Classe Java pure, testable sur ordinateur.
 */
public final class Soldes {
    private Soldes() {}

    public static final String ESPECES = Caisse.ESPECES;

    /** Nature d'une ligne du relevé. */
    public static final String OPERATION = "OPERATION", MOUVEMENT_ESPECES = "ESPECES", FOND = "FOND", COMPTAGE = "COMPTAGE", CONSULTATION = "CONSULTATION";

    public static final class Ligne {
        public long date;
        public String nature, compte;
        /** Opération SMS, mouvement d'espèces saisi ou journée de caisse, selon la nature. */
        public Txn sms;
        public Caisse.Saisie saisie;
        public Caisse.Jour jour;
        /** Variation du compte de la ligne (centimes, frais compris) ; pour un fond ou un comptage, l'écart constaté. */
        public long variation;
        /** Effet sur les espèces d'une opération client en mode agent (0 sinon). */
        public long variationEspeces;
        /** Solde de chaque compte après la ligne (null : inconnu à ce moment). */
        public final Map<String, Long> apres = new LinkedHashMap<>();
        public Long total;
        /** Ordre à date égale : fond, puis opérations, puis comptage. */
        int rang;
        int seq;

        public Long solde(String compte) {
            return apres.get(compte);
        }
    }

    public static final class Releve {
        public long from, to;
        /** Comptes présents, dans l'ordre d'affichage : Orange Money, MTN MoMo, banques, espèces. */
        public final List<String> comptes = new ArrayList<>();
        public final Map<String, Long> ouverture = new LinkedHashMap<>(), cloture = new LinkedHashMap<>();
        public Long totalOuverture, totalCloture;
        /** Lignes de la plus ancienne à la plus récente. */
        public final List<Ligne> lignes = new ArrayList<>();
        public final Map<Txn, Ligne> parSms = new IdentityHashMap<>();
        public final Map<Caisse.Saisie, Ligne> parSaisie = new IdentityHashMap<>();

        public Long variation(String compte) {
            Long o = ouverture.get(compte), c = cloture.get(compte);
            return o != null && c != null ? c - o : null;
        }

        public Long variationTotale() {
            return totalOuverture != null && totalCloture != null ? totalCloture - totalOuverture : null;
        }

        /** Total des comptes à un instant de la période (ouverture si avant la première ligne). */
        public Long totalA(long t) {
            Long v = totalOuverture;
            for (Ligne l : lignes) {
                if (l.date > t) break;
                v = l.total;
            }
            return v;
        }

        /** Totaux en fin de chaque jour de la période (le plus ancien d'abord), au plus les maxi derniers jours. */
        public Long[] parJour(int maxi) {
            Calendar d = Calendar.getInstance();
            d.setTimeInMillis(to == Long.MAX_VALUE ? System.currentTimeMillis() : to);
            d.set(Calendar.HOUR_OF_DAY, 0);
            d.set(Calendar.MINUTE, 0);
            d.set(Calendar.SECOND, 0);
            d.set(Calendar.MILLISECOND, 0);
            java.util.LinkedList<Long> out = new java.util.LinkedList<>();
            for (int i = 0; i < maxi; i++) {
                Calendar f = (Calendar) d.clone();
                f.add(Calendar.DAY_OF_MONTH, 1);
                long finJour = Math.min(f.getTimeInMillis() - 1, to);
                if (finJour < from) break;
                out.addFirst(totalA(finJour));
                d.add(Calendar.DAY_OF_MONTH, -1);
            }
            return out.toArray(new Long[0]);
        }
    }

    /** Nom affiché d'un compte. */
    public static String nom(String compte) {
        return ESPECES.equals(compte) ? "Espèces" : Txn.opName(compte);
    }

    /** Nom court : OM, MoMo, nom de la banque, Espèces. */
    public static String court(String compte) {
        if (Caisse.OM.equals(compte)) return "OM";
        if (Caisse.MOMO.equals(compte)) return "MoMo";
        return nom(compte);
    }

    static int ordre(String compte) {
        if (Caisse.OM.equals(compte)) return 0;
        if (Caisse.MOMO.equals(compte)) return 1;
        if (ESPECES.equals(compte)) return 3;
        return 2;
    }

    public static Releve calculer(Analyzer.Result r, List<Caisse.Saisie> saisies, List<Caisse.Jour> jours, long from, long to,
                                  boolean agentOm, boolean agentMomo) {
        Releve rv = new Releve();
        rv.from = from;
        rv.to = to;
        if (r == null) r = new Analyzer.Result();
        if (saisies == null) saisies = new ArrayList<>();
        if (jours == null) jours = new ArrayList<>();

        // 1. Ouverture des comptes électroniques : dernier solde connu avant la période, sinon solde avant la 1re opération.
        Map<String, Long> courant = new LinkedHashMap<>();
        List<String> comptes = new ArrayList<>();
        for (Analyzer.Account a : Analyzer.summary(r, from, to)) {
            if (a.count == 0 && a.reel == null) continue;
            comptes.add(a.op);
            courant.put(a.op, a.ouverture);
        }

        // 2. Ouverture des espèces.
        List<Caisse.Jour> tri = new ArrayList<>(jours);
        tri.sort((x, y) -> x.jour.compareTo(y.jour));
        String jFrom = from <= 0 ? "" : Caisse.jourDe(from), jTo = to == Long.MAX_VALUE ? "9999" : Caisse.jourDe(to);
        Long esp = null;
        for (Caisse.Jour j : tri) {
            if (j.jour.compareTo(jFrom) < 0 && j.compte != null) esp = j.compte;
        }
        List<Ligne> lignes = new ArrayList<>();
        boolean especesUtilisees = esp != null;
        for (Caisse.Jour j : tri) {
            if (j.jour.compareTo(jFrom) < 0 || j.jour.compareTo(jTo) > 0) continue;
            if (j.fond != null) {
                especesUtilisees = true;
                long debut = Caisse.debutJour(j.jour);
                if (debut <= from) {
                    esp = j.fond;   // fond du premier jour = ouverture
                } else {
                    Ligne l = new Ligne();
                    l.date = debut;
                    l.nature = FOND;
                    l.compte = ESPECES;
                    l.jour = j;
                    l.rang = 0;
                    lignes.add(l);
                }
            }
            if (j.compte != null) {
                especesUtilisees = true;
                long quand = j.clotureAt > 0 ? j.clotureAt : Caisse.debutJour(j.jour) + 86400000L - 1;
                if (quand >= from && quand <= to) {
                    Ligne l = new Ligne();
                    l.date = quand;
                    l.nature = COMPTAGE;
                    l.compte = ESPECES;
                    l.jour = j;
                    l.rang = 2;
                    lignes.add(l);
                }
            }
        }
        for (Caisse.Saisie s : saisies) {
            if (s.annule || !Caisse.ESPECES.equals(s.mode) || s.date < from || s.date > to) continue;
            especesUtilisees = true;
            Ligne l = new Ligne();
            l.date = s.date;
            l.nature = MOUVEMENT_ESPECES;
            l.compte = ESPECES;
            l.saisie = s;
            l.variation = s.sens > 0 ? s.montant : -s.montant;
            l.rang = 1;
            lignes.add(l);
            rv.parSaisie.put(s, l);
        }

        // 3. Opérations SMS (les consultations de solde ne comptent que si elles corrigent le solde).
        for (int k = r.tx.size() - 1; k >= 0; k--) {
            Txn t = r.tx.get(k);
            if (t.date < from || t.date > to) continue;
            Ligne l = new Ligne();
            l.date = t.date;
            l.sms = t;
            l.compte = t.op;
            l.rang = 1;
            if (t.isBalanceCheck()) {
                l.nature = CONSULTATION;
            } else {
                l.nature = OPERATION;
                l.variation = t.sens * t.montant - t.frais;
                boolean agent = Caisse.OM.equals(t.op) ? agentOm : Caisse.MOMO.equals(t.op) && agentMomo;
                if (agent) {
                    int effet = Caisse.effetEspecesAgent(t);
                    if (effet != 0) { l.variationEspeces = effet * t.montant; especesUtilisees = true; }
                }
            }
            lignes.add(l);
        }
        for (int i = 0; i < lignes.size(); i++) lignes.get(i).seq = i;
        lignes.sort((x, y) -> x.date != y.date ? Long.compare(x.date, y.date) : x.rang != y.rang ? Integer.compare(x.rang, y.rang) : Integer.compare(x.seq, y.seq));

        comptes.sort((x, y) -> ordre(x) != ordre(y) ? Integer.compare(ordre(x), ordre(y)) : x.compareTo(y));
        if (especesUtilisees) {
            comptes.add(ESPECES);
            courant.put(ESPECES, esp);
        }
        rv.comptes.addAll(comptes);
        for (String c : comptes) rv.ouverture.put(c, courant.get(c));
        rv.totalOuverture = somme(rv.ouverture);

        // 4. Déroulé.
        for (Ligne l : lignes) {
            switch (l.nature) {
                case FOND:
                case COMPTAGE: {
                    Long avant = courant.get(ESPECES);
                    long nouveau = FOND.equals(l.nature) ? l.jour.fond : l.jour.compte;
                    l.variation = avant != null ? nouveau - avant : 0;
                    courant.put(ESPECES, nouveau);
                    break;
                }
                case MOUVEMENT_ESPECES: {
                    Long avant = courant.get(ESPECES);
                    courant.put(ESPECES, avant != null ? avant + l.variation : null);
                    break;
                }
                case CONSULTATION: {
                    Long avant = courant.get(l.compte);
                    if (l.sms.apres != null) courant.put(l.compte, l.sms.apres);
                    l.variation = avant != null && l.sms.apres != null ? l.sms.apres - avant : 0;
                    break;
                }
                default: {
                    Long avant = courant.get(l.compte);
                    Long apres = l.sms.apres != null ? l.sms.apres : avant != null ? avant + l.variation : null;
                    courant.put(l.compte, apres);
                    if (l.variationEspeces != 0) {
                        Long e = courant.get(ESPECES);
                        courant.put(ESPECES, e != null ? e + l.variationEspeces : null);
                    }
                }
            }
            for (String c : comptes) l.apres.put(c, courant.get(c));
            l.total = somme(l.apres);
        }
        // Une consultation qui ne change rien n'a pas sa place dans le relevé.
        for (Ligne l : lignes) {
            if (CONSULTATION.equals(l.nature) && l.variation == 0) continue;
            rv.lignes.add(l);
            if (l.sms != null) rv.parSms.put(l.sms, l);
        }
        for (String c : comptes) rv.cloture.put(c, courant.get(c));
        rv.totalCloture = somme(rv.cloture);
        return rv;
    }

    /** Somme des soldes connus ; null si aucun n'est connu. */
    public static Long somme(Map<String, Long> m) {
        Long s = null;
        for (Long v : m.values()) if (v != null) s = (s == null ? 0 : s) + v;
        return s;
    }

    /** « OM 50 000 · total 82 000 » sous une opération. */
    public static String texteApres(Releve rv, Ligne l) {
        if (l == null) return "";
        Long s = l.solde(l.compte);
        StringBuilder b = new StringBuilder();
        if (s != null) b.append("solde ").append(court(l.compte)).append(" ").append(SmsParser.fmt(s));
        if (l.total != null && rv.comptes.size() > 1) b.append(b.length() > 0 ? " · " : "").append("total ").append(SmsParser.fmt(l.total));
        return b.toString();
    }
}
