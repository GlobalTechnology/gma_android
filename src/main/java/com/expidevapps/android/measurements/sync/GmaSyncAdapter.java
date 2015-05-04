package com.expidevapps.android.measurements.sync;

import static com.expidevapps.android.measurements.sync.BaseSyncTasks.baseExtras;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.ccci.gto.android.common.api.ApiException;

import me.thekey.android.lib.accounts.AccountUtils;

public class GmaSyncAdapter extends AbstractThreadedSyncAdapter {
    static final String EXTRA_SYNCTYPE = GmaSyncAdapter.class.getName() + ".EXTRA_SYNCTYPE";

    // supported sync types
    static final int SYNCTYPE_NONE = 0;
    static final int SYNCTYPE_ALL = 1;
    static final int SYNCTYPE_MINISTRIES = 2;
    static final int SYNCTYPE_ASSIGNMENTS = 3;
    static final int SYNCTYPE_SAVE_ASSIGNMENTS = 4;
    static final int SYNCTYPE_CHURCHES = 5;
    static final int SYNCTYPE_DIRTY_CHURCHES = 6;
    static final int SYNCTYPE_MEASUREMENT_TYPES = 7;
    static final int SYNCTYPE_MEASUREMENTS = 8;
    static final int SYNCTYPE_DIRTY_MEASUREMENTS = 9;
    static final int SYNCTYPE_MEASUREMENT_DETAILS = 10;

    private static final Object INSTANCE_LOCK = new Object();
    private static GmaSyncAdapter INSTANCE = null;

    @NonNull
    private final Context mContext;

    private GmaSyncAdapter(@NonNull final Context context, final boolean autoInitialize) {
        super(context, autoInitialize);
        mContext = context;
    }

    @NonNull
    public static GmaSyncAdapter getInstance(@NonNull final Context context) {
        synchronized (INSTANCE_LOCK) {
            if (INSTANCE == null) {
                INSTANCE = new GmaSyncAdapter(context.getApplicationContext(), true);
            }

            return INSTANCE;
        }
    }

    @Override
    public void onPerformSync(@NonNull final Account account, @Nullable final Bundle extras,
                              @NonNull final String authority, final ContentProviderClient provider,
                              @NonNull final SyncResult result) {
        final String guid = AccountUtils.getGuid(mContext, account);

        if (guid != null) {
            if (extras != null) {
                dispatchSync(guid, extras.getInt(EXTRA_SYNCTYPE, SYNCTYPE_ALL), extras, result);
            } else {
                dispatchSync(guid, SYNCTYPE_ALL, Bundle.EMPTY, result);
            }
        }
    }

    void dispatchSync(@NonNull final String guid, final int type, @NonNull final Bundle extras,
                      @NonNull final SyncResult result) {
        try {
            switch (type) {
                case SYNCTYPE_MINISTRIES:
                    MinistrySyncTasks.syncMinistries(mContext, guid, extras);
                    break;
                case SYNCTYPE_ASSIGNMENTS:
                    AssignmentSyncTasks.syncAssignments(mContext, guid, extras);
                    break;
                case SYNCTYPE_SAVE_ASSIGNMENTS:
                    AssignmentSyncTasks.saveAssignments(mContext, guid, extras, result);
                    break;
                case SYNCTYPE_MEASUREMENT_TYPES:
                    MeasurementSyncTasks.syncMeasurementTypes(mContext, guid, extras);
                    break;
                case SYNCTYPE_ALL:
                    syncAll(guid, extras, result);
                    break;
                case SYNCTYPE_NONE:
                default:
                    break;
            }
        } catch (final ApiException e) {
            result.stats.numIoExceptions++;
        }
    }

    private void syncAll(@NonNull final String guid, @NonNull final Bundle extras, @NonNull final SyncResult result) {
        final boolean force = BaseSyncTasks.isForced(extras);

        // sync assignments for this user
        dispatchSync(guid, SYNCTYPE_ASSIGNMENTS, baseExtras(guid, force), result);

        // sync measurement types
        dispatchSync(guid, SYNCTYPE_MEASUREMENT_TYPES, baseExtras(guid, force), result);
    }
}