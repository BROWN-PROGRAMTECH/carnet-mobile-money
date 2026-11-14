package cm.carnet.mobilemoney;

/**
 * Connexion au projet Supabase.
 * À remplir avec les valeurs de votre projet : Supabase → Project Settings → API.
 *  - URL : « Project URL », par exemple https://abcdefghijkl.supabase.co
 *  - ANON_KEY : clé publique, appelée « publishable » (sb_publishable_…) ou « anon public » selon l'âge du projet.
 *    Elle est faite pour être dans l'application ; la sécurité repose sur les règles d'accès par ligne
 *    du fichier supabase/schema.sql.
 * Ne mettez JAMAIS ici la clé secrète (« secret » sb_secret_… ou « service_role »).
 * Tant que ces deux valeurs sont vides, l'application fonctionne sans sauvegarde en ligne.
 */
public final class CloudConfig {
    private CloudConfig() {}

    public static final String URL = "https://saxnpugntqgiobpvrgry.supabase.co";
    public static final String ANON_KEY = "sb_publishable_4jfvUCG4J_Z890eUfZ-rVw_6hP7I4On";
}
