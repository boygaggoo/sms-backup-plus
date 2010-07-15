package tv.studer.smssync;

import android.app.Service;
import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import com.android.email.mail.Folder;
import com.android.email.mail.Folder.FolderType;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.store.ImapStore;

import java.net.URLEncoder;

public abstract class ServiceBase extends Service {

    // the activity
    public static SmsSync smsSync;

    enum SmsSyncState {
        IDLE, CALC, LOGIN, SYNC, RESTORE, AUTH_FAILED, GENERAL_ERROR, CANCELED;
    }

    /**
     * A state change listener interface that provides a callback that is called
     * whenever the state of the {@link SmsSyncService} changes.
     *
     * @see SmsSyncService#setStateChangeListener(StateChangeListener)
     */
    public interface StateChangeListener {

        /**
         * Called whenever the sync state of the service changed.
         */
        public void stateChanged(SmsSyncState oldState, SmsSyncState newState);
    }


    public static final Uri SMS_PROVIDER = Uri.parse("content://sms");

    /**
     * A wakelock held while this service is working.
     */
    protected PowerManager.WakeLock sWakeLock;
    /**
     * A wifilock held while this service is working.
     */
    protected WifiManager.WifiLock sWifiLock;

    public static String getHeader(Message msg, String header) {
        try {
            String[] hdrs = msg.getHeader(header);
            if (hdrs != null && hdrs.length > 0) {
                return hdrs[0];
            }
        } catch (MessagingException e) {
        }
        return null;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    protected Folder getBackupFolder()
            throws AuthenticationErrorException {
        try {
            ImapStore imapStore =  getImapStore();

            String label = PrefStore.getImapFolder(this);
            if (label == null)
                throw new IllegalArgumentException("label is null");

            Folder folder = imapStore.getFolder(label);

            if (!folder.exists()) {
                Log.i(Consts.TAG, "Label '" + label + "' does not exist yet. Creating.");
                folder.create(FolderType.HOLDS_MESSAGES);
            }
            folder.open(OpenMode.READ_WRITE);
            return folder;
        } catch (MessagingException e) {
            throw new AuthenticationErrorException(e);
        }
    }

    protected ImapStore getImapStore()
        throws MessagingException {
        String username = PrefStore.getLoginUsername(this);
        String password = PrefStore.getLoginPassword(this);

        if (username == null)
            throw new IllegalArgumentException("username is null");
        if (password == null)
            throw new IllegalArgumentException("password is null");

        String imapUri = "imap+ssl+://%s:%s@imap.gmail.com:993";

        return new ImapStore(String.format(imapUri, URLEncoder.encode(username),
                URLEncoder.encode(password).replace("+", "%20")));
    }

    protected void acquireWakeLock() {
        if (sWakeLock == null) {
            PowerManager pMgr = (PowerManager) getSystemService(POWER_SERVICE);
            sWakeLock = pMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "SmsSyncService.sync() wakelock.");

            WifiManager wMgr = (WifiManager) getSystemService(WIFI_SERVICE);
            sWifiLock = wMgr.createWifiLock("SMS Backup");
        }
        sWakeLock.acquire();
        sWifiLock.acquire();
    }

    protected void releaseWakeLock() {
        sWakeLock.release();
        sWifiLock.release();
    }

    /**
     * Returns the maximum date of all SMS messages (except for drafts).
     */
    protected long getMaxItemDate() {
        ContentResolver r = getContentResolver();
        String selection = SmsConsts.TYPE + " <> ?";
        String[] selectionArgs = new String[] {
            String.valueOf(SmsConsts.MESSAGE_TYPE_DRAFT)
        };
        String[] projection = new String[] {
            SmsConsts.DATE
        };
        Cursor result = r.query(SMS_PROVIDER, projection, selection, selectionArgs,
                SmsConsts.DATE + " DESC LIMIT 1");

        try
        {
            if (result.moveToFirst()) {
                return result.getLong(0);
            } else {
                return PrefStore.DEFAULT_MAX_SYNCED_DATE;
            }
        }
        catch (RuntimeException e)
        {
            result.close();
            throw e;
        }
    }

    /**
     * Returns the largest date of all messages that have successfully been synced
     * with the server.
     */
    protected long getMaxSyncedDate() {
        return PrefStore.getMaxSyncedDate(this);
    }

    /**
     * Persists the provided ID so it can later on be retrieved using
     * {@link #getMaxSyncedDate()}. This should be called when after each
     * successful sync request to a server.
     *
     * @param maxSyncedId
     */
    protected void updateMaxSyncedDate(long maxSyncedDate) {
        PrefStore.setMaxSyncedDate(this, maxSyncedDate);
        Log.d(Consts.TAG, "Max synced date set to: " + maxSyncedDate);
    }

    /**
     * Exception indicating an error while synchronizing.
     */
    public static class GeneralErrorException extends Exception {
        private static final long serialVersionUID = 1L;

        public GeneralErrorException(String msg, Throwable t) {
            super(msg, t);
        }

        public GeneralErrorException(Context ctx, int msgId, Throwable t) {
            super(ctx.getString(msgId), t);
        }
    }

    public static class AuthenticationErrorException extends Exception {
        private static final long serialVersionUID = 1L;

        public AuthenticationErrorException(Throwable t) {
            super(t.getLocalizedMessage(), t);
        }
    }

}