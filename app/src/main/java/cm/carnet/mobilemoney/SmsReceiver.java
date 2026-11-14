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
            if (op == null) return;
            boolean isNew = Store.get(ctx).insert(sms);
            ctx.sendBroadcast(new Intent(ACTION_NEW).setPackage(ctx.getPackageName()));
            if (!isNew) return;
            SyncJob.soon(ctx);

            sms.op = op;
            Txn t = SmsParser.parse(sms, cfg);
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
