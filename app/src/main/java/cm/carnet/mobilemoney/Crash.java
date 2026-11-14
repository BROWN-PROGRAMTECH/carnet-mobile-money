package cm.carnet.mobilemoney;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Mémorise tout plantage (pile d'appels, modèle, version) pour l'afficher au prochain lancement et l'envoyer au
 * support avec la prochaine sauvegarde. Aucune donnée de caisse ni aucun SMS n'est inclus.
 */
public final class Crash {
    private Crash() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("plantage", Context.MODE_PRIVATE);
    }

    /** Installe le capteur de plantages (une seule fois par processus). */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof Capteur) return;
        Thread.setDefaultUncaughtExceptionHandler(new Capteur(app, current));
    }

    private static final class Capteur implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler suivant;

        Capteur(Context app, Thread.UncaughtExceptionHandler suivant) {
            this.app = app;
            this.suivant = suivant;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try { enregistrer(app, t, e, true); } catch (Throwable ignored) {}
            if (suivant != null) suivant.uncaughtException(t, e);
            else System.exit(10);
        }
    }

    /** Enregistre une erreur ; fatale = l'application s'est fermée. Écriture immédiate (commit). */
    public static void enregistrer(Context c, Thread t, Throwable e, boolean fatale) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = (fatale ? "PLANTAGE" : "ERREUR AFFICHAGE") + " · v" + Cloud.VERSION_APP + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + " · " + (t != null ? t.getName() : "?") + "\n" + sw;
        if (trace.length() > 6000) trace = trace.substring(0, 6000);
        prefs(c).edit()
                .putString("trace", trace)
                .putLong("quand", System.currentTimeMillis())
                .putBoolean("afficher", fatale)
                .putBoolean("envoye", false)
                .commit();
    }

    /** Trace à montrer à l'utilisateur au lancement (après un plantage), sinon null. */
    public static String aAfficher(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("afficher", false) ? p.getString("trace", null) : null;
    }

    public static void marquerAffiche(Context c) {
        prefs(c).edit().putBoolean("afficher", false).commit();
    }

    /** Trace pas encore envoyée au support, sinon null. */
    static String aEnvoyer(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("envoye", true) ? null : p.getString("trace", null);
    }

    static void marquerEnvoye(Context c) {
        prefs(c).edit().putBoolean("envoye", true).commit();
    }
}
