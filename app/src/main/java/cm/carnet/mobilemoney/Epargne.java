package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Épargne et tontines (version 0.17), gratuites dans la base.
 *  - Objectif d'épargne : un montant à atteindre (date facultative), des versements et des retraits.
 *  - Tontine : une cotisation fixe à chaque séance (semaine, quinzaine ou mois), des membres dans l'ordre du tour.
 *    À la séance n, c'est le tour du membre n (modulo le nombre de membres) de « bouffer ». On note ses cotisations,
 *    ce qu'on a reçu à son tour et, pour le trésorier, qui a cotisé.
 * Montants en centimes. Classe Java pure, testable sur ordinateur.
 */
public final class Epargne {
    private Epargne() {}

    public static final String OBJECTIF = "objectif", TONTINE = "tontine";
    public static final String SEMAINE = "semaine", QUINZAINE = "quinzaine", MOIS = "mois";
    static final long JOUR = 86400000L;

    public static final class Projet {
        public String id, type = OBJECTIF, nom = "";
        /** Objectif : montant à atteindre ; tontine : cotisation par séance. */
        public long montant;
        public String frequence = MOIS;
        /** Tontine : date de la première séance ; objectif : date de création. */
        public long debut;
        /** Objectif : date à laquelle l'atteindre (facultative). */
        public Long fin;
        /** Tontine : membres dans l'ordre du tour. */
        public final List<String> membres = new ArrayList<>();
        /** Position de l'utilisateur dans la liste des membres (-1 : pas membre, il tient seulement les comptes). */
        public int moi = -1;
        public boolean annule;
        public long maj;

        public boolean tontine() {
            return TONTINE.equals(type);
        }

        /** Somme reçue par le bénéficiaire d'une séance : une cotisation par membre. */
        public long pot() {
            return montant * Math.max(1, membres.size());
        }
    }

    public static final class Versement {
        public String id, projet;
        public long date, montant;
        /** +1 : argent mis (versement, cotisation) ; -1 : argent récupéré (retrait, tour reçu). */
        public int sens = 1;
        /** Tontine : position du membre (celle de l'utilisateur pour ses propres cotisations). */
        public int membre = -1;
        /** Tontine : numéro de séance (0 = première). */
        public int seance = -1;
        /** Opération SMS ou mouvement d'espèces qui correspond (Caisse.cle), facultatif. */
        public String cle;
        public String motif = "";
        public boolean annule;
        public long maj;
    }

    // ---------- Objectif d'épargne ----------

    public static final class EtatObjectif {
        public Projet projet;
        public long epargne, verse, retire, reste;
        public int pourcent, versements;
        public boolean atteint;
        /** Montant à mettre de côté par mois et par semaine pour finir à temps (null sans date ou si atteint). */
        public Long parMois, parSemaine;
        public boolean enRetard;
    }

    public static EtatObjectif objectif(Projet p, List<Versement> tous, long now) {
        EtatObjectif e = new EtatObjectif();
        e.projet = p;
        for (Versement v : tous) {
            if (v.annule || !p.id.equals(v.projet)) continue;
            if (v.sens > 0) e.verse += v.montant; else e.retire += v.montant;
            e.versements++;
        }
        e.epargne = e.verse - e.retire;
        e.reste = Math.max(0, p.montant - e.epargne);
        e.pourcent = p.montant > 0 ? (int) Math.min(100, Math.max(0, Math.round(e.epargne * 100.0 / p.montant))) : 0;
        e.atteint = p.montant > 0 && e.epargne >= p.montant;
        if (!e.atteint && p.fin != null) {
            long jours = (p.fin - now) / JOUR;
            if (jours <= 0) {
                e.enRetard = true;
            } else {
                double mois = Math.max(1, jours / 30.4);
                double semaines = Math.max(1, jours / 7.0);
                e.parMois = arrondi((long) Math.ceil(e.reste / mois));
                e.parSemaine = arrondi((long) Math.ceil(e.reste / semaines));
            }
        }
        return e;
    }

    /** Arrondi aux 100 FCFA au-dessus. */
    static long arrondi(long cents) {
        return (cents + 9999) / 10000 * 10000;
    }

    // ---------- Tontine ----------

    public static final class Seance {
        public int n;
        public long date;
        public int beneficiaire;
        public String nomBeneficiaire;
        /** Membres qui ont cotisé à cette séance. */
        public boolean[] paye;
        public int payes;
        /** Montant reçu par l'utilisateur à cette séance (quand c'est son tour). */
        public long recu;
        public boolean passee;

        public boolean monTour(Projet p) {
            return p.moi >= 0 && beneficiaire == p.moi;
        }

        public boolean moiPaye(Projet p) {
            return p.moi >= 0 && p.moi < paye.length && paye[p.moi];
        }
    }

    public static final class EtatTontine {
        public Projet projet;
        public final List<Seance> seances = new ArrayList<>();
        /** Prochaine séance (aujourd'hui compris), et la prochaine où c'est le tour de l'utilisateur. */
        public Seance prochaine, monTour;
        public long cotise, recu;
        /** Séances passées sans cotisation de l'utilisateur. */
        public int retards;
        /** Séance en cours ou la dernière passée (index dans le cycle). */
        public int courante;

        public long net() {
            return recu - cotise;
        }
    }

    /** Date de la séance n. */
    public static long dateSeance(Projet p, int n) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(p.debut);
        if (MOIS.equals(p.frequence)) c.add(Calendar.MONTH, n);
        else c.add(Calendar.DAY_OF_MONTH, n * (QUINZAINE.equals(p.frequence) ? 14 : 7));
        return c.getTimeInMillis();
    }

    /** Numéro de la séance du jour ou de la dernière passée (-1 avant la première). */
    public static int seanceCourante(Projet p, long now) {
        int n = -1;
        long finJour = Caisse.debutJour(Caisse.jourDe(now)) + JOUR - 1;
        while (n < 1000 && dateSeance(p, n + 1) <= finJour) n++;
        return n;
    }

    /**
     * État d'une tontine : les séances passées (au plus 12) et les suivantes jusqu'au prochain tour de l'utilisateur
     * (au moins 3, au plus un tour complet).
     */
    public static EtatTontine tontine(Projet p, List<Versement> tous, long now) {
        EtatTontine e = new EtatTontine();
        e.projet = p;
        int m = Math.max(1, p.membres.size());
        int cour = seanceCourante(p, now);
        e.courante = cour;
        int derniere = cour;
        for (Versement v : tous) if (!v.annule && p.id.equals(v.projet) && v.seance > derniere) derniere = v.seance;
        // Jusqu'au prochain tour de l'utilisateur, au moins 3 séances à venir, au plus un tour complet.
        int fin = cour + 3;
        if (p.moi >= 0) for (int k = cour + 1; k <= cour + m; k++) if (k % m == p.moi) { fin = Math.max(fin, k); break; }
        fin = Math.max(fin, derniere);
        int debut = Math.max(0, cour - 11);
        long finJour = Caisse.debutJour(Caisse.jourDe(now)) + JOUR - 1;
        for (int n = Math.min(debut, Math.max(0, derniere - 11)); n <= fin; n++) {
            Seance s = new Seance();
            s.n = n;
            s.date = dateSeance(p, n);
            s.beneficiaire = n % m;
            s.nomBeneficiaire = p.membres.isEmpty() ? "—" : p.membres.get(s.beneficiaire);
            s.paye = new boolean[m];
            s.passee = s.date < Caisse.debutJour(Caisse.jourDe(now));
            e.seances.add(s);
        }
        for (Versement v : tous) {
            if (v.annule || !p.id.equals(v.projet)) continue;
            boolean moi = p.moi >= 0 && v.membre == p.moi;
            if (moi) { if (v.sens > 0) e.cotise += v.montant; else e.recu += v.montant; }
            Seance s = seance(e, v.seance);
            if (s == null) continue;
            if (v.sens > 0 && v.membre >= 0 && v.membre < s.paye.length && !s.paye[v.membre]) { s.paye[v.membre] = true; s.payes++; }
            if (v.sens < 0 && moi) s.recu += v.montant;
        }
        for (Seance s : e.seances) {
            if (s.date <= finJour && p.moi >= 0 && !s.moiPaye(p) && s.n <= cour && s.passee) e.retards++;
            if (e.prochaine == null && s.date >= Caisse.debutJour(Caisse.jourDe(now))) e.prochaine = s;
            if (e.monTour == null && s.monTour(p) && s.date >= Caisse.debutJour(Caisse.jourDe(now))) e.monTour = s;
        }
        return e;
    }

    public static Seance seance(EtatTontine e, int n) {
        for (Seance s : e.seances) if (s.n == n) return s;
        return null;
    }

    /** Rappels du jour : cotisation demain ou aujourd'hui, tour de l'utilisateur, cotisations en retard, objectif dépassé. */
    public static List<String> rappels(List<Projet> projets, List<Versement> tous, long now) {
        List<String> out = new ArrayList<>();
        long aujourdhui = Caisse.debutJour(Caisse.jourDe(now));
        for (Projet p : projets) {
            if (p.annule) continue;
            if (p.tontine()) {
                EtatTontine e = tontine(p, tous, now);
                Seance s = e.prochaine;
                if (s != null && s.date < aujourdhui + 2 * JOUR) {
                    String quand = s.date < aujourdhui + JOUR ? "aujourd'hui" : "demain";
                    if (s.monTour(p)) out.add("Tontine « " + p.nom + " » : c'est votre tour " + quand + " (" + SmsParser.fmt(p.pot()) + " FCFA).");
                    else if (p.moi >= 0 && !s.moiPaye(p))
                        out.add("Tontine « " + p.nom + " » : cotisation de " + SmsParser.fmt(p.montant) + " FCFA " + quand + " (tour de " + s.nomBeneficiaire + ").");
                }
                if (e.retards > 0) out.add("Tontine « " + p.nom + " » : " + e.retards + " cotisation(s) non notée(s) aux séances passées.");
            } else {
                EtatObjectif e = objectif(p, tous, now);
                if (e.enRetard) out.add("Épargne « " + p.nom + " » : date dépassée, il manque " + SmsParser.fmt(e.reste) + " FCFA.");
            }
        }
        return out;
    }

    public static String frequenceNom(String f) {
        return SEMAINE.equals(f) ? "chaque semaine" : QUINZAINE.equals(f) ? "toutes les deux semaines" : "chaque mois";
    }
}
