package cm.carnet.mobilemoney;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;

import java.util.HashSet;
import java.util.Set;

/** Messagerie du téléphone : empreintes des SMS OM et MoMo présents, pour savoir quels SMS sont à ce téléphone. */
final class Boite {
    private Boite() {}

    static boolean lisible(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Empreintes de tous les SMS mobile money de la messagerie ; null si la messagerie est illisible. */
    static Set<String> empreintes(Context ctx) {
        if (!lisible(ctx)) return null;
        SmsParser.Config cfg = Store.config(ctx);
        Set<String> out = new HashSet<>();
        Cursor c;
        try {
            c = ctx.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, new String[] {"address", "body"}, null, null, null);
        } catch (Exception e) {
            return null;
        }
        if (c == null) return null;
        try {
            int n = 0;
            while (c.moveToNext() && n++ < 200000) {
                String address = c.getString(0), body = c.getString(1);
                if (body == null) continue;
                if (SmsParser.detectOp(address, body, cfg) == null) continue;
                out.add(SmsParser.hashOf(address, body));
            }
        } finally {
            c.close();
        }
        return out;
    }
}
