package cm.carnet.mobilemoney;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * Reçoit chaque nouveau SMS, même application fermée. Ne garde que les SMS
 * Orange Money et MTN MoMo, les enregistre et affiche une notification.
 */
public class SmsReceiver extends BroadcastReceiver {
    public static final String ACTION_NEW = "cm.carnet.mobilemoney.NOUVEAU_SMS";
    static final String CHANNEL = "transactions";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0 || parts[0] == null) return;
            String address = parts[0].getDisplayOriginatingAddress();
            StringBuilder body = new StringBuilder();
            for (SmsMessage p : parts) if (p != null && p.getDisplayMessageBody() != null) body.append(p.getDisplayMessageBody());
            int sub = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1));

            SmsParser.Sms sms = new SmsParser.Sms(address, body.toString(), System.currentTimeMillis(), sub >= 0 ? String.valueOf(sub) : "");
            SmsParser.Config cfg = Store.config(ctx);
            String op = SmsParser.detectOp(sms.address, sms.body, cfg);
            if (op == null) {
                // Faux SMS de paiement (numéro ordinaire qui imite Orange Money ou MoMo) : alerte immédiate.
                String faux = FauxSms.suspect(sms.address, sms.body, false);
                if (faux != null) {
                    notify(ctx, "⚠ Faux SMS de paiement probable", faux);
                    Store.savePref(ctx, "faux_sms", System.currentTimeMillis() + "|" + faux);
                }
                return;
            }
            rappelEpargne(ctx);
            boolean isNew = Store.get(ctx).insert(sms);
            ctx.sendBroadcast(new Intent(ACTION_NEW).setPackage(ctx.getPackageName()));
            if (!isNew) return;
            SyncJob.soon(ctx);

            sms.op = op;
            Txn t = SmsParser.parse(sms, cfg);
            if (t.kind == Txn.TX) verifierPaiement(ctx, t);
            if (t.kind == Txn.TX && t.isBalanceCheck()) {
                notify(ctx, Txn.opName(op) + " : solde " + SmsParser.fmt(t.solde) + " FCFA", "Consultation de solde enregistrée.");
            } else if (t.kind == Txn.TX) {
                String sens = t.sens > 0 ? "+" : (t.sens < 0 ? "−" : "");
                String who = t.nom != null ? t.nom : (t.numero != null ? t.numero : "");
                notify(ctx, Txn.opName(op) + " : " + sens + SmsParser.fmt(t.montant) + " FCFA",
                        t.typeLabel() + (who.isEmpty() ? "" : " · " + who) + (t.solde != null ? " · solde " + SmsParser.fmt(t.solde) : ""));
            } else if (t.kind == Txn.UNKNOWN) {
                notify(ctx, Txn.opName(op) + " : SMS à vérifier", "L'application n'a pas su lire ce SMS.");
            }
        } catch (Exception ignored) {
            // Un SMS illisible ne doit jamais faire planter la réception.
        }
    }

    /**
     * Version 0.17 : paiement reçu qui ne suit pas la chaîne des soldes (faux SMS niveau 2), puis float agent bas.
     * Le dernier solde connu de chaque compte est tenu ici à chaque SMS, et recalé par l'application sur le relevé complet.
     */
    static void verifierPaiement(Context ctx, Txn t) {
        if (!Caisse.OM.equals(t.op) && !Caisse.MOMO.equals(t.op)) return;
        long now = System.currentTimeMillis();
        String ch = Store.pref(ctx, "chaine_" + t.op, "");
        Long prev = null;
        try { if (!ch.isEmpty()) prev = Long.parseLong(ch); } catch (NumberFormatException ignored) {}
        java.util.List<String> ids = new java.util.ArrayList<>(java.util.Arrays.asList(Store.pref(ctx, "ids_" + t.op, "").split(",")));
        ids.remove("");
        String doute = FauxSms.chaine(t, prev, ids);
        if (doute != null) {
            notify(ctx, "⚠ Paiement non confirmé : ne livrez pas", doute);
            Store.savePref(ctx, "faux_sms", now + "|" + doute);
            String d = Store.pref(ctx, "douteux", "");
            Store.savePref(ctx, "douteux", (d.isEmpty() ? "" : d + "\n") + Caisse.cle(t));
        } else if (t.solde != null) {
            Store.savePref(ctx, "chaine_" + t.op, String.valueOf(t.solde));
        }
        if (t.txnId != null && !ids.contains(t.txnId)) {
            ids.add(t.txnId);
            while (ids.size() > 100) ids.remove(0);
            Store.savePref(ctx, "ids_" + t.op, String.join(",", ids));
        }
        // Float agent : seuil choisi (FCFA), sinon une journée moyenne de dépôts (calculée par l'application).
        if (doute == null && t.solde != null && Store.prefBool(ctx, "agent" + t.op, false)) {
            long seuil = Store.prefLong(ctx, "seuil_float_" + t.op, 0) * 100;
            if (seuil <= 0) seuil = Store.prefLong(ctx, "float_auto_" + t.op, 0);
            String jour = Caisse.jourDe(now);
            if (seuil > 0 && t.solde < seuil && !jour.equals(Store.pref(ctx, "float_alerte_" + t.op, ""))) {
                Store.savePref(ctx, "float_alerte_" + t.op, jour);
                notify(ctx, "Float " + Txn.opName(t.op) + " bas", "Il reste " + SmsParser.fmt(t.solde) + " FCFA (seuil " + SmsParser.fmt(seuil)
                        + "). Rechargez votre compte agent pour servir les dépôts.");
            }
        }
    }

    /** Rappels d'épargne et de tontine, une fois par jour au plus. */
    static void rappelEpargne(Context ctx) {
        try {
            long now = System.currentTimeMillis();
            String jour = Caisse.jourDe(now);
            if (jour.equals(Store.pref(ctx, "rappel_epargne", ""))) return;
            Store st = Store.get(ctx);
            java.util.List<String> r = Epargne.rappels(st.projetsEpargne(), st.versementsEpargne(), now);
            if (r.isEmpty()) return;
            Store.savePref(ctx, "rappel_epargne", jour);
            notify(ctx, r.size() == 1 ? "Épargne et tontine" : "Épargne et tontine · " + r.size() + " rappels", String.join("\n", r));
        } catch (Exception ignored) {}
    }

    static void notify(Context ctx, String title, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Transactions mobile money", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
        } catch (SecurityException ignored) {
            // Notifications refusées par l'utilisateur.
        }
    }
}
