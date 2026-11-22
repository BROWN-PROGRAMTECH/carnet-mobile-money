package cm.carnet.mobilemoney;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dettes et créances par personne (version 0.10), communes à tout le compte.
 *  - Créance (on me doit) : augmente avec un prêt accordé, diminue avec un remboursement reçu.
 *  - Dette (je dois)      : augmente avec un emprunt reçu, diminue avec un remboursement versé.
 * Chaque mouvement vient d'une opération classée (SMS, espèces, part d'un retrait) ou d'une saisie à la main
 * (dette ou créance ancienne). Classe Java pure, testable sur ordinateur. Montants en centimes.
 */
public final class Dettes {
    private Dettes() {}

    public static final String CREANCE = "CREANCE", DETTE = "DETTE";

    public static final class Engagement {
        public String id, tiers, numero, type, motif, cle;
        /** +1 la somme due augmente, -1 elle diminue. */
        public int sens;
        public long montant, date;
        /** Date prévue de remboursement (ms), ou null. */
        public Long echeance;
        public boolean annule;
        public long maj;
    }

    /** Identifiant du mouvement lié à une opération : le même sur tous les téléphones du compte. */
    public static String idPour(String cleOperation) {
        return "op:" + cleOperation;
    }

    /** Type (CREANCE / DETTE) et sens d'un mouvement d'après la catégorie de l'opération ; null si sans rapport. */
    public static Object[] nature(String categorie) {
        if (categorie == null) return null;
        switch (categorie) {
            case Caisse.PRET_ACCORDE: return new Object[] {CREANCE, 1};
            case Caisse.REMB_RECU: return new Object[] {CREANCE, -1};
            case Caisse.PRET_RECU: return new Object[] {DETTE, 1};
            case Caisse.REMB_PRET: return new Object[] {DETTE, -1};
            default: return null;
        }
    }

    public static boolean estCategorieDette(String categorie) {
        return nature(categorie) != null;
    }

    /** Clé d'une personne : nom sans accents ni majuscules ni espaces en trop. */
    public static String clePersonne(String nom) {
        return SmsParser.deaccent(nom == null ? "" : nom).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    public static final class Personne {
        public String nom, numero;
        /** Ce que cette personne me doit encore (créance) et ce que je lui dois encore (dette). */
        public long creance, dette;
        /** Prochaine échéance des sommes encore dues (ms), ou null. */
        public Long echeance;
        public long derniere;
        public final List<Engagement> mouvements = new ArrayList<>();

        /** Positif : elle me doit ; négatif : je lui dois. */
        public long net() {
            return creance - dette;
        }

        public boolean soldee() {
            return creance == 0 && dette == 0;
        }

        public boolean enRetard(long now) {
            return echeance != null && echeance < now && !soldee();
        }
    }

    public static final class Bilan {
        public final List<Personne> personnes = new ArrayList<>();
        /** Total de ce qu'on me doit, total de ce que je dois (soldes positifs seulement). */
        public long onMeDoit, jeDois;
        public int enRetard;

        public long net() {
            return onMeDoit - jeDois;
        }

        public Personne personne(String nom) {
            String k = clePersonne(nom);
            for (Personne p : personnes) if (clePersonne(p.nom).equals(k)) return p;
            return null;
        }
    }

    /**
     * Soldes par personne. Ordre : personnes qui me doivent ou à qui je dois (montant le plus élevé d'abord),
     * puis les comptes soldés (plus récents d'abord).
     */
    public static Bilan calculer(List<Engagement> liste, long now) {
        Map<String, Personne> m = new LinkedHashMap<>();
        List<Engagement> tri = new ArrayList<>();
        for (Engagement e : liste) if (!e.annule && e.montant > 0 && e.tiers != null && !e.tiers.trim().isEmpty()) tri.add(e);
        Collections.sort(tri, (a, b) -> Long.compare(a.date, b.date));
        for (Engagement e : tri) {
            String k = clePersonne(e.tiers);
            Personne p = m.get(k);
            if (p == null) {
                p = new Personne();
                p.nom = e.tiers.trim();
                m.put(k, p);
            }
            if (e.numero != null && !e.numero.isEmpty()) p.numero = e.numero;
            p.mouvements.add(e);
            p.derniere = Math.max(p.derniere, e.date);
            long d = e.sens > 0 ? e.montant : -e.montant;
            if (CREANCE.equals(e.type)) p.creance += d; else p.dette += d;
        }
        Bilan b = new Bilan();
        for (Personne p : m.values()) {
            // Un remboursement plus grand que la somme due passe de l'autre côté (trop-perçu, trop-versé).
            if (p.creance < 0) { p.dette += -p.creance; p.creance = 0; }
            if (p.dette < 0) { p.creance += -p.dette; p.dette = 0; }
            long net = p.creance - p.dette;
            if (p.creance > 0 && p.dette > 0) { p.creance = Math.max(0, net); p.dette = Math.max(0, -net); }
            // Échéance : la plus proche parmi les prêts et emprunts encore ouverts.
            if (!p.soldee()) {
                for (Engagement e : p.mouvements) {
                    if (e.sens <= 0 || e.echeance == null) continue;
                    boolean ouvert = (CREANCE.equals(e.type) && p.creance > 0) || (DETTE.equals(e.type) && p.dette > 0);
                    if (ouvert && (p.echeance == null || e.echeance < p.echeance)) p.echeance = e.echeance;
                }
            }
            Collections.reverse(p.mouvements); // le plus récent d'abord
            b.onMeDoit += p.creance;
            b.jeDois += p.dette;
            if (p.enRetard(now)) b.enRetard++;
            b.personnes.add(p);
        }
        Collections.sort(b.personnes, (x, y) -> {
            if (x.soldee() != y.soldee()) return x.soldee() ? 1 : -1;
            if (!x.soldee()) return Long.compare(Math.abs(y.net()), Math.abs(x.net()));
            return Long.compare(y.derniere, x.derniere);
        });
        return b;
    }

    /** Libellé d'un mouvement, vu par l'utilisateur. */
    public static String libelle(Engagement e) {
        String base = CREANCE.equals(e.type) ? (e.sens > 0 ? "Prêt accordé" : "Remboursement reçu")
                : (e.sens > 0 ? "Emprunt reçu" : "Remboursement versé");
        return e.cle == null && e.sens > 0 ? base + " (ancien)" : base;
    }

    /** Personnes déjà connues dont le nom ou le numéro correspond (suggestions de saisie). */
    public static List<String> suggestions(Bilan b, String numero, int max) {
        List<String> out = new ArrayList<>();
        String n = Recharges.neuf(numero);
        if (!n.isEmpty()) for (Personne p : b.personnes) if (n.equals(Recharges.neuf(p.numero)) && !out.contains(p.nom)) out.add(p.nom);
        for (Personne p : b.personnes) {
            if (out.size() >= max) break;
            if (!out.contains(p.nom)) out.add(p.nom);
        }
        return out;
    }
}
