package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applications à la carte (version 0.11), comme les applications d'Odoo.
 * Base gratuite pour tous : lecture des SMS (OM, MoMo, banques), journal, classement, entrées et dépenses par
 * catégorie, soldes, sauvegarde. Les autres applications s'activent une à une (ou par pack), 14 jours d'essai puis
 * abonnement mensuel prélevé sur le porte-monnaie. Non renouvelée : lecture seule, rien n'est perdu.
 * L'état vient du serveur (mes_modules) et reste en mémoire pour fonctionner hors ligne.
 * Classe Java pure, testable sur ordinateur.
 */
public final class Modules {
    private Modules() {}

    public static final String CAISSE = "CAISSE", FINANCES = "FINANCES", SUIVI = "SUIVI", DETTES = "DETTES", MULTI = "MULTI", ALERTES = "ALERTES", ASSISTANT = "ASSISTANT";
    public static final String PACK_PARTICULIER = "PACK_PARTICULIER", PACK_COMMERCANT = "PACK_COMMERCANT", PACK_ENTREPRISE = "PACK_ENTREPRISE";

    /** ACTIF : utilisable ; LECTURE : abonnement terminé, données visibles sans ajout ; ABSENT : jamais installée. */
    public enum Acces { ACTIF, LECTURE, ABSENT }

    public static final class Module {
        public String code, nom, description = "";
        /** Prix catalogue et prix pour ce compte (Multi-téléphones : par téléphone en plus), FCFA par mois. */
        public long prix, prixCompte;
        /** Version 0.14 : prix sans promotion, nom de la promotion appliquée (ou null), formule annuelle (0 = aucune). */
        public long prixNormal, prixAnnuel, prixAnnuelCompte;
        public String promo;

        public boolean enPromo() {
            return promo != null && prixCompte < prixNormal;
        }

        public boolean annuel() {
            return prixAnnuel > 0;
        }

        /** Économie de la formule annuelle par rapport à 12 mois au prix normal (FCFA, 0 si aucune). */
        public long economieAnnuelle() {
            return annuel() ? Math.max(0, prixNormal * 12 - prixAnnuelCompte) : 0;
        }
        public boolean parTelephone, estPack;
        public List<String> contenu = new ArrayList<>();
        public int telephones = 1;
    }

    public static final class Abonnement {
        public String code;
        public long fin;
        public boolean renouveler, essai, essaiUtilise, offert;
        /** "mois" ou "an" (version 0.14). */
        public String periode = "mois";
    }

    /** Promotion ouverte pour ce compte (version 0.14). */
    public static final class Promo {
        public String nom, description = "", type;
        public long valeur, seuil, fin;
        public int duree;
        /** Mois de réduction restants, -1 si pas encore utilisée. */
        public int restants = -1;
        public final List<String> modules = new ArrayList<>();

        public boolean bonusRecharge() {
            return "bonus_recharge".equals(type);
        }
    }

    public static final class Etat {
        public final List<Module> catalogue = new ArrayList<>();
        public final List<Abonnement> abonnements = new ArrayList<>();
        public final List<Promo> promos = new ArrayList<>();
        public long solde;
        public int essaiJours = 14, caisses;
        /** Heure du serveur moins heure du téléphone au moment de la lecture (corrige une horloge fausse). */
        public long decalage;
        /** false tant que l'état n'a jamais été lu (pas de compte, ou pas encore de réseau). */
        public boolean connu;

        public Module module(String code) {
            for (Module m : catalogue) if (m.code.equals(code)) return m;
            return null;
        }

        public Abonnement abonnement(String code) {
            for (Abonnement a : abonnements) if (a.code.equals(code)) return a;
            return null;
        }

        private long maintenant(long now) {
            return now + decalage;
        }

        /** Pack actif qui contient cette application, ou null. */
        public Module packActif(String code, long now) {
            for (Abonnement a : abonnements) {
                Module m = module(a.code);
                if (m != null && m.estPack && m.contenu.contains(code) && a.fin > maintenant(now)) return m;
            }
            return null;
        }

        /** Fin de la période en cours pour cette application (directe ou par un pack), 0 si aucune. */
        public long fin(String code, long now) {
            long f = 0;
            for (Abonnement a : abonnements) {
                Module m = module(a.code);
                boolean couvre = a.code.equals(code) || (m != null && m.estPack && m.contenu.contains(code));
                if (couvre) f = Math.max(f, a.fin);
            }
            return f;
        }

        public Acces acces(String code, long now) {
            long f = fin(code, now);
            if (f > maintenant(now)) return Acces.ACTIF;
            return f > 0 ? Acces.LECTURE : Acces.ABSENT;
        }

        public boolean actif(String code, long now) {
            return acces(code, now) == Acces.ACTIF;
        }

        /** Applications actives (packs dépliés). */
        public Set<String> actifs(long now) {
            Set<String> s = new LinkedHashSet<>();
            for (String c : TOUTES) if (actif(c, now)) s.add(c);
            return s;
        }

        /** Nouveau compte qui n'a encore rien choisi (aucun abonnement, même terminé). */
        public boolean aChoisir() {
            return connu && abonnements.isEmpty();
        }

        /** Période offerte (lancement, geste commercial) qui finit dans moins de 7 jours, sans renouvellement prévu. */
        public long finOffre(long now) {
            long f = 0;
            for (Abonnement a : abonnements) {
                if (!a.offert || a.renouveler || a.fin <= maintenant(now)) continue;
                if (a.fin - maintenant(now) < 7L * 24 * 3600 * 1000) f = f == 0 ? a.fin : Math.min(f, a.fin);
            }
            return f;
        }

        /** Montant à prévoir pour les renouvellements des 3 prochains jours (FCFA). */
        public long aPrevoir(long now) {
            long total = 0;
            for (Abonnement a : abonnements) {
                if (!a.renouveler || a.offert) continue;
                long reste = a.fin - maintenant(now);
                if (reste <= 0 || reste > 3L * 24 * 3600 * 1000) continue;
                Module m = module(a.code);
                if (m == null || (!m.estPack && packActif(a.code, now) != null)) continue;
                total += "an".equals(a.periode) && m.annuel() ? m.prixAnnuelCompte : m.prixCompte;
            }
            return total;
        }

        /** Rappel à afficher si le solde ne couvre pas les renouvellements proches, sinon null. */
        /** Meilleur bonus de recharge ouvert pour ce montant (FCFA), 0 si aucun. */
        public long bonusRecharge(long montant) {
            long best = 0;
            for (Promo p : promos) {
                if (!p.bonusRecharge() || montant < p.seuil) continue;
                best = Math.max(best, montant * Math.min(100, p.valeur) / 100);
            }
            return best;
        }

        /** Bonus de recharge le plus généreux (pour l'annoncer), ou null. */
        public Promo meilleurBonus() {
            Promo best = null;
            for (Promo p : promos) if (p.bonusRecharge() && (best == null || p.valeur > best.valeur)) best = p;
            return best;
        }

        public String rappel(long now) {
            long p = aPrevoir(now);
            if (p <= 0 || solde >= p) return null;
            return "Vos applications se renouvellent dans moins de 3 jours : il faut " + Tarif.fcfa(p) + ", vous avez "
                    + Tarif.fcfa(Math.max(0, solde)) + ". Rechargez " + Tarif.fcfa(p - Math.max(0, solde)) + " pour ne pas passer en lecture seule.";
        }
    }

    /** Applications (hors packs), dans l'ordre d'affichage. */
    public static final List<String> TOUTES = Arrays.asList(CAISSE, FINANCES, SUIVI, DETTES, MULTI, ALERTES, ASSISTANT);

    /** Profils proposés à l'inscription et ce qu'ils pré-cochent. */
    public static final String[][] PROFILS = {
            {"Particulier", "Salaire, dépenses, prêts entre proches", PACK_PARTICULIER},
            {"Commerçant", "Boutique, caisse, ventes et charges", PACK_COMMERCANT},
            {"Entreprise", "Plusieurs téléphones ou points de vente", PACK_ENTREPRISE},
            {"Sur mesure", "Je choisis mes applications", ""},
    };

    /** Applications contenues dans une sélection (packs dépliés). */
    public static Set<String> deplier(Etat e, List<String> choix) {
        Set<String> s = new LinkedHashSet<>();
        for (String c : choix) {
            Module m = e.module(c);
            if (m != null && m.estPack) s.addAll(m.contenu); else s.add(c);
        }
        return s;
    }

    /** Prix mensuel d'une sélection après l'essai (FCFA). */
    public static long prixMensuel(Etat e, List<String> choix) {
        long t = 0;
        for (String c : choix) {
            Module m = e.module(c);
            if (m != null) t += m.prixCompte;
        }
        return t;
    }

    /**
     * Sélection sur mesure : si un pack contient au moins tout ce qui est coché et coûte moins cher, on le propose.
     * Renvoie le code du pack conseillé ou null.
     */
    public static String packConseille(Etat e, Set<String> coches) {
        if (coches.isEmpty()) return null;
        long prixSepares = 0;
        for (String c : coches) {
            Module m = e.module(c);
            if (m != null) prixSepares += m.prixCompte;
        }
        Module meilleur = null;
        for (Module m : e.catalogue) {
            if (!m.estPack || !m.contenu.containsAll(coches)) continue;
            if (m.prixCompte <= prixSepares && (meilleur == null || m.prixCompte < meilleur.prixCompte)) meilleur = m;
        }
        return meilleur == null ? null : meilleur.code;
    }

    /** Texte de l'état d'une application ou d'un pack. */
    public static String etatTexte(Etat e, String code, long now) {
        Module m = e.module(code);
        Abonnement a = e.abonnement(code);
        if (m != null && !m.estPack) {
            Module p = e.packActif(code, now);
            if (p != null) return "Incluse dans " + p.nom;
        }
        if (a == null) return "Non installée · " + e.essaiJours + " jours d'essai gratuit";
        long reste = a.fin - (now + e.decalage);
        if (reste <= 0) return "Lecture seule depuis le " + date(a.fin) + (a.essaiUtilise ? "" : " · essai disponible");
        String jusqua = " jusqu'au " + date(a.fin);
        boolean an = "an".equals(a.periode) && m != null && m.annuel();
        if (a.essai) return "Essai gratuit" + jusqua + (a.renouveler ? ", puis " + Tarif.fcfa(m != null ? m.prixCompte : 0) + "/mois" : "");
        if (an && !a.offert) return (a.renouveler ? "Formule annuelle · renouvelée le " + date(a.fin) : "Formule annuelle" + jusqua + " · ne sera pas renouvelée");
        if (a.offert) return "Offerte" + jusqua;
        return (a.renouveler ? "Active · renouvelée le " + date(a.fin) : "Active" + jusqua + " · ne sera pas renouvelée");
    }

    /** Texte court d'une promotion : « -30 % pendant 2 mois », « +10 % sur les recharges dès 5 000 FCFA ». */
    public static String promoTexte(Etat e, Promo p) {
        String cible = "";
        if (!p.modules.isEmpty()) {
            List<String> noms = new ArrayList<>();
            for (String c : p.modules) {
                Module m = e.module(c);
                noms.add(m != null ? m.nom : c);
            }
            cible = " sur " + String.join(", ", noms);
        }
        String duree = p.duree > 1 ? " pendant " + p.duree + " mois" : "";
        if (p.restants > 0) duree = " (encore " + p.restants + " mois)";
        String t;
        switch (p.type == null ? "" : p.type) {
            case "pourcent": t = "-" + p.valeur + " %" + cible + duree; break;
            case "montant": t = "-" + Tarif.fcfa(p.valeur) + " par mois" + cible + duree; break;
            case "prix": t = Tarif.fcfa(p.valeur) + " par mois" + cible + duree; break;
            case "bonus_recharge": t = "+" + p.valeur + " % offerts sur vos recharges" + (p.seuil > 0 ? " dès " + Tarif.fcfa(p.seuil) : ""); break;
            default: t = p.nom;
        }
        if (p.fin > 0) t += " · jusqu'au " + date(p.fin);
        return t;
    }

    /** Prix affiché : « 1 400 FCFA/mois au lieu de 2 000 FCFA » en promotion. */
    public static String prixTexte(Module m) {
        String t = Tarif.fcfa(m.prixCompte) + "/mois";
        if (m.enPromo()) t += " au lieu de " + Tarif.fcfa(m.prixNormal);
        return t;
    }

    static String date(long t) {
        return new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(new java.util.Date(t));
    }
}
