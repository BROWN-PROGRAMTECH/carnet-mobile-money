package cm.carnet.mobilemoney;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Copie locale des SMS OM et MoMo. Elle garde l'historique même si l'utilisateur
 * supprime ensuite les SMS de sa messagerie. Les autres SMS ne sont jamais enregistrés.
 */
public final class Store extends SQLiteOpenHelper {
    private static Store instance;

    public static synchronized Store get(Context ctx) {
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    private Store(Context ctx) {
        super(ctx, "carnet.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sms (hash TEXT PRIMARY KEY, address TEXT, body TEXT, date INTEGER, sub TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    /** Enregistre un SMS ; renvoie true s'il est nouveau. */
    public boolean insert(SmsParser.Sms s) {
        ContentValues v = new ContentValues();
        v.put("hash", hash(s.address, s.body));
        v.put("address", s.address);
        v.put("body", s.body);
        v.put("date", s.date);
        v.put("sub", s.sub);
        return getWritableDatabase().insertWithOnConflict("sms", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<SmsParser.Sms> all() {
        List<SmsParser.Sms> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sms", new String[] {"address", "body", "date", "sub"}, null, null, null, null, "date ASC");
        try {
            while (c.moveToNext()) out.add(new SmsParser.Sms(c.getString(0), c.getString(1), c.getLong(2), c.getString(3)));
        } finally {
            c.close();
        }
        return out;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sms", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void clear() {
        getWritableDatabase().delete("sms", null, null);
    }

    static String hash(String address, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SmsParser.senderKey(address) + "|" + (body == null ? "" : body.trim())).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf((address + "|" + body).hashCode());
        }
    }

    // ---------- Réglages ----------

    public static SmsParser.Config config(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE);
        return SmsParser.Config.of(p.getString("om", SmsParser.DEFAULT_OM), p.getString("momo", SmsParser.DEFAULT_MOMO), p.getBoolean("auto", true));
    }

    public static String pref(Context ctx, String key, String def) {
        return ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).getString(key, def);
    }

    public static boolean prefBool(Context ctx, String key, boolean def) {
        return ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).getBoolean(key, def);
    }

    public static void saveConfig(Context ctx, String om, String momo, boolean auto) {
        ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE).edit()
                .putString("om", om).putString("momo", momo).putBoolean("auto", auto).apply();
    }
}
