package com.expidevapps.android.measurements.service;

import static com.expidevapps.android.measurements.BuildConfig.GMA_API_VERSION;
import static com.expidevapps.android.measurements.Constants.EXTRA_GUID;
import static com.expidevapps.android.measurements.Constants.EXTRA_MCC;
import static com.expidevapps.android.measurements.Constants.EXTRA_MINISTRY_ID;
import static com.expidevapps.android.measurements.Constants.EXTRA_PERIOD;
import static com.expidevapps.android.measurements.Constants.EXTRA_PERMLINK;
import static com.expidevapps.android.measurements.model.Task.UPDATE_MINISTRY_MEASUREMENTS;
import static com.expidevapps.android.measurements.model.Task.UPDATE_PERSONAL_MEASUREMENTS;
import static org.ccci.gto.android.common.db.AbstractDao.bindValues;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.util.ArrayMap;
import android.support.v4.util.LongSparseArray;
import android.util.Log;

import com.expidevapps.android.measurements.Constants;
import com.expidevapps.android.measurements.api.GmaApiClient;
import com.expidevapps.android.measurements.db.Contract;
import com.expidevapps.android.measurements.db.GmaDao;
import com.expidevapps.android.measurements.model.Assignment;
import com.expidevapps.android.measurements.model.Church;
import com.expidevapps.android.measurements.model.Measurement;
import com.expidevapps.android.measurements.model.MeasurementDetails;
import com.expidevapps.android.measurements.model.MeasurementType;
import com.expidevapps.android.measurements.model.MeasurementValue;
import com.expidevapps.android.measurements.model.Ministry;
import com.expidevapps.android.measurements.model.Ministry.Mcc;
import com.expidevapps.android.measurements.model.MinistryMeasurement;
import com.expidevapps.android.measurements.model.PersonalMeasurement;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Longs;

import org.ccci.gto.android.common.api.ApiException;
import org.ccci.gto.android.common.app.ThreadedIntentService;
import org.ccci.gto.android.common.db.Transaction;
import org.ccci.gto.android.common.util.ThreadUtils;
import org.ccci.gto.android.common.util.ThreadUtils.GenericKey;
import org.joda.time.YearMonth;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GmaSyncService extends ThreadedIntentService {
    private static final String TAG = GmaSyncService.class.getSimpleName();

    private static final String PREFS_SYNC = "gma_sync";
    private static final String PREF_SYNC_TIME_ASSIGNMENTS = "last_synced.assignments";
    private static final String PREF_SYNC_TIME_MINISTRIES = "last_synced.ministries";

    private static final String EXTRA_SYNCTYPE = GmaSyncService.class.getName() + ".EXTRA_SYNCTYPE";
    private static final String EXTRA_FORCE = GmaSyncService.class.getName() + ".EXTRA_FORCE";
    private static final String EXTRA_ASSIGNMENTS = GmaSyncService.class.getName() + ".EXTRA_ASSIGNMENTS";

    // supported sync types
    private static final int SYNCTYPE_MINISTRIES = 1;
    private static final int SYNCTYPE_ASSIGNMENTS = 2;
    private static final int SYNCTYPE_SAVE_ASSIGNMENTS = 3;
    private static final int SYNCTYPE_CHURCHES = 4;
    private static final int SYNCTYPE_DIRTY_CHURCHES = 5;
    private static final int SYNCTYPE_MEASUREMENT_TYPES = 6;
    private static final int SYNCTYPE_MEASUREMENTS = 7;
    private static final int SYNCTYPE_DIRTY_MEASUREMENTS = 8;
    private static final int SYNCTYPE_MEASUREMENT_DETAILS = 9;

    // various stale data durations
    private static final long HOUR_IN_MS = 60 * 60 * 1000;
    private static final long DAY_IN_MS = 24 * HOUR_IN_MS;
    private static final long STALE_DURATION_ASSIGNMENTS = DAY_IN_MS;
    private static final long STALE_DURATION_MINISTRIES = 7 * DAY_IN_MS;
    private static final long STALE_DURATION_MEASUREMENT_DETAILS_CURRENT = 1 * DAY_IN_MS;
    private static final long STALE_DURATION_MEASUREMENT_DETAILS_PREVIOUS = 2 * DAY_IN_MS;
    private static final long STALE_DURATION_MEASUREMENT_DETAILS_OLD = 7 * DAY_IN_MS;

    // locks to synchronize various sync types
    private final Object mLockDirtyChurches = new Object();
    private final Map<GenericKey, Object> mLocksDirtyMeasurements = new ArrayMap<>();

    @NonNull
    private /* final */ GmaDao mDao;
    private LocalBroadcastManager broadcastManager;

    public GmaSyncService() {
        super("GmaSyncService", 10);
    }

    public static void syncMinistries(final Context context) {
        syncMinistries(context, false);
    }

    public static void syncMinistries(final Context context, final boolean force) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_MINISTRIES);
        intent.putExtra(EXTRA_FORCE, force);
        context.startService(intent);
    }

    public static void syncAssignments(@NonNull final Context context, @NonNull final String guid) {
        syncAssignments(context, guid, false);
    }

    public static void syncAssignments(@NonNull final Context context, @NonNull final String guid,
                                       final boolean force) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_ASSIGNMENTS);
        intent.putExtra(EXTRA_GUID, guid);
        intent.putExtra(EXTRA_FORCE, force);
        context.startService(intent);
    }

    public static void saveAssignments(@NonNull final Context context, @NonNull final String guid,
                                       @Nullable final JSONArray assignments) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_SAVE_ASSIGNMENTS);
        intent.putExtra(EXTRA_GUID, guid);
        intent.putExtra(EXTRA_ASSIGNMENTS, assignments != null ? assignments.toString() : null);
        context.startService(intent);
    }

    public static void syncChurches(@NonNull final Context context, @NonNull final String ministryId) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_CHURCHES);
        intent.putExtra(EXTRA_MINISTRY_ID, ministryId);
        context.startService(intent);
    }

    public static void syncDirtyChurches(@NonNull final Context context) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_DIRTY_CHURCHES);
        context.startService(intent);
    }

    public static void syncMeasurementTypes(@NonNull final Context context) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_MEASUREMENT_TYPES);
        context.startService(intent);
    }

    public static void syncMeasurements(@NonNull final Context context, @NonNull final String ministryId,
                                        @NonNull final Mcc mcc, @Nullable final YearMonth period) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_MEASUREMENTS);
        intent.putExtra(EXTRA_MINISTRY_ID, ministryId);
        intent.putExtra(EXTRA_MCC, mcc.toString());
        intent.putExtra(EXTRA_PERIOD, (period != null ? period : YearMonth.now()).toString());
        context.startService(intent);
    }

    public static void syncDirtyMeasurements(@NonNull final Context context, @NonNull final String guid,
                                             @NonNull final String ministryId, @NonNull final Mcc mcc,
                                             @NonNull final YearMonth period) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_DIRTY_MEASUREMENTS);
        intent.putExtra(EXTRA_GUID, guid);
        intent.putExtra(EXTRA_MINISTRY_ID, ministryId);
        intent.putExtra(EXTRA_MCC, mcc.toString());
        intent.putExtra(EXTRA_PERIOD, period.toString());
        context.startService(intent);
    }

    public static void syncMeasurementDetails(@NonNull final Context context, @NonNull final String guid,
                                              @NonNull final String ministryId, @NonNull final Mcc mcc,
                                              @NonNull final String permLink, @NonNull final YearMonth period,
                                              final boolean force) {
        final Intent intent = new Intent(context, GmaSyncService.class);
        intent.putExtra(EXTRA_SYNCTYPE, SYNCTYPE_MEASUREMENT_DETAILS);
        intent.putExtra(EXTRA_GUID, guid);
        intent.putExtra(EXTRA_MINISTRY_ID, ministryId);
        intent.putExtra(EXTRA_MCC, mcc.toString());
        intent.putExtra(EXTRA_PERMLINK, permLink);
        intent.putExtra(EXTRA_PERIOD, period.toString());
        intent.putExtra(EXTRA_FORCE, force);
        context.startService(intent);
    }

    /* BEGIN lifecycle */

    @Override
    public void onCreate()
    {
        super.onCreate();
        mDao = GmaDao.getInstance(this);
        broadcastManager = LocalBroadcastManager.getInstance(this);
    }

    @Override
    public void onHandleIntent(@NonNull final Intent intent) {
        final GmaApiClient api = GmaApiClient.getInstance(this, intent.getStringExtra(EXTRA_GUID));

        try {
            switch (intent.getIntExtra(EXTRA_SYNCTYPE, 0)) {
                case SYNCTYPE_MINISTRIES:
                    syncMinistries(api, intent);
                    break;
                case SYNCTYPE_SAVE_ASSIGNMENTS:
                    saveAssignments(intent);
                    break;
                case SYNCTYPE_ASSIGNMENTS:
                    syncAssignments(api, intent);
                    break;
                case SYNCTYPE_CHURCHES:
                    syncChurches(api, intent);
                    break;
                case SYNCTYPE_DIRTY_CHURCHES:
                    syncDirtyChurches(api);
                    break;
                case SYNCTYPE_MEASUREMENT_TYPES:
                    syncMeasurementTypes(api, intent);
                    break;
                case SYNCTYPE_MEASUREMENTS:
                    syncMeasurements(api, intent);
                    break;
                case SYNCTYPE_DIRTY_MEASUREMENTS:
                    syncDirtyMeasurements(api, intent);
                    break;
                case SYNCTYPE_MEASUREMENT_DETAILS:
                    syncMeasurementDetails(api, intent);
                    break;
                default:
                    break;
            }
        } catch (final ApiException e) {
            // XXX: ignore for now, maybe eventually broadcast something on specific ApiExceptions
        }
    }

    /* END lifecycle */

    /* BEGIN Assignment sync */

    private void syncAssignments(@NonNull final GmaApiClient api, final Intent intent) throws ApiException {
        final SharedPreferences prefs = this.getSharedPreferences(PREFS_SYNC, MODE_PRIVATE);
        final String guid = intent.getStringExtra(EXTRA_GUID);
        final boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
        final boolean stale =
                System.currentTimeMillis() - prefs.getLong(PREF_SYNC_TIME_ASSIGNMENTS, 0) > STALE_DURATION_ASSIGNMENTS;

        if (force || stale) {
            // fetch raw data from API & parse it
            final List<Assignment> assignments = api.getAssignments();
            if (assignments != null) {
                this.updateAllAssignments(guid, assignments);
            }
        }
    }

    private void saveAssignments(@NonNull final Intent intent) {
        final String guid = intent.getStringExtra(EXTRA_GUID);
        final String raw = intent.getStringExtra(EXTRA_ASSIGNMENTS);
        if (guid != null && raw != null) {
            try {
                final List<Assignment> assignments = Assignment.listFromJson(new JSONArray(raw), guid);

                this.updateAllAssignments(guid, assignments);
            } catch (final JSONException ignored) {
            }
        }
    }

    private void updateAllAssignments(@NonNull final String guid, @NonNull final List<Assignment> assignments) {
        // wrap entire update in a transaction
        final Transaction tx = mDao.newTransaction();
        try {
            tx.beginTransactionNonExclusive();

            // load pre-existing Assignments (ministry_id => assignment)
            final Map<String, Assignment> existing = new HashMap<>();
            for (final Assignment assignment : mDao
                    .get(Assignment.class, Contract.Assignment.SQL_WHERE_GUID, bindValues(guid))) {
                existing.put(assignment.getMinistryId(), assignment);
            }

            // column projections for updates
            final String[] PROJECTION_ASSIGNMENT = {Contract.Assignment.COLUMN_ROLE, Contract.Assignment.COLUMN_ID,
                    Contract.Assignment.COLUMN_LAST_SYNCED};
            final String[] PROJECTION_MINISTRY =
                    {Contract.Ministry.COLUMN_NAME, Contract.Ministry.COLUMN_MIN_CODE, Contract.Ministry.COLUMN_MCCS,
                            Contract.Ministry.COLUMN_LATITUDE, Contract.Ministry.COLUMN_LONGITUDE,
                            Contract.Ministry.COLUMN_LOCATION_ZOOM, Contract.Ministry.COLUMN_PARENT_MINISTRY_ID,
                            Contract.Ministry.COLUMN_LAST_SYNCED};

            // update assignments in local database
            final LinkedList<Assignment> toProcess = new LinkedList<>(assignments);
            while (toProcess.size() > 0) {
                final Assignment assignment = toProcess.pop();

                // update the ministry
                final Ministry ministry = assignment.getMinistry();
                if (ministry != null) {
                    mDao.updateOrInsert(ministry, PROJECTION_MINISTRY);
                }

                // now update the actual assignment
                mDao.updateOrInsert(assignment, PROJECTION_ASSIGNMENT);

                // queue up sub assignments for processing
                toProcess.addAll(assignment.getSubAssignments());

                // remove it from the list of existing assignments
                existing.remove(assignment.getMinistryId());
            }

            // delete any remaining assignments, we don't have them anymore
            for (final Assignment assignment : existing.values()) {
                mDao.delete(assignment);
            }

            tx.setSuccessful();

            // update the sync time
            this.getSharedPreferences(PREFS_SYNC, MODE_PRIVATE).edit()
                    .putLong(PREF_SYNC_TIME_ASSIGNMENTS, System.currentTimeMillis()).apply();

            // send broadcasts for updated data
            broadcastManager.sendBroadcast(BroadcastUtils.updateAssignmentsBroadcast(guid));
            if (assignments.isEmpty()) {
                broadcastManager.sendBroadcast(BroadcastUtils.noAssignmentsBroadcast(guid));
            }
        } catch (final SQLException e) {
            Log.d(TAG, "error updating assignments", e);
        } finally {
            tx.end();
        }
    }

    /* END Assignment sync */

    /* BEGIN Ministry sync */

    private void syncMinistries(@NonNull final GmaApiClient api, final Intent intent) throws ApiException {
        final SharedPreferences prefs = this.getSharedPreferences(PREFS_SYNC, MODE_PRIVATE);
        final boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
        final boolean stale =
                System.currentTimeMillis() - prefs.getLong(PREF_SYNC_TIME_MINISTRIES, 0) > STALE_DURATION_MINISTRIES;

        // only sync if being forced or the data is stale
        if (force || stale) {
            // refresh the list of ministries if the load is being forced
            final List<Ministry> ministries = api.getMinistries();

            // only update the saved ministries if we received any back
            if (ministries != null) {
                // load current ministries
                final Map<String, Ministry> current = new HashMap<>();
                for (final Ministry ministry : mDao.get(Ministry.class)) {
                    current.put(ministry.getMinistryId(), ministry);
                }

                // update all the ministry names
                for (final Ministry ministry : ministries) {
                    // this is only a very minimal update, so don't log last synced for new ministries
                    ministry.setLastSynced(0);
                    mDao.updateOrInsert(ministry, new String[] {Contract.Ministry.COLUMN_NAME});

                    // remove from the list of current ministries
                    current.remove(ministry.getMinistryId());
                }

                // remove any current ministries we didn't see, we can do this because we just retrieved a complete list
                for (final Ministry ministry : current.values()) {
                    mDao.delete(ministry);
                }

                // update the synced time
                prefs.edit().putLong(PREF_SYNC_TIME_MINISTRIES, System.currentTimeMillis()).apply();

                // send broadcasts that data has been updated in the database
                broadcastManager.sendBroadcast(BroadcastUtils.updateMinistriesBroadcast());
            }
        }
    }

    /* END Ministry sync */

    /* BEGIN Church sync */

    private static String[] PROJECTION_GET_CHURCHES_DATA = {Contract.Church.COLUMN_MINISTRY_ID,
            Contract.Church.COLUMN_NAME, Contract.Church.COLUMN_CONTACT_NAME,
            Contract.Church.COLUMN_CONTACT_EMAIL, Contract.Church.COLUMN_LATITUDE,
            Contract.Church.COLUMN_LONGITUDE, Contract.Church.COLUMN_SIZE,
            Contract.Church.COLUMN_DEVELOPMENT, Contract.Church.COLUMN_SECURITY,
            Contract.Church.COLUMN_LAST_SYNCED};

    private void syncChurches(@NonNull final GmaApiClient api, final Intent intent) throws ApiException {
        final String ministryId = intent.getStringExtra(EXTRA_MINISTRY_ID);
        if (ministryId == null) {
            return;
        }

        final List<Church> churches = api.getChurches(ministryId);

        // only update churches if we get data back
        if (churches != null) {
            final Transaction tx = mDao.newTransaction();
            try {
                tx.beginTransactionNonExclusive();

                // load current churches
                final LongSparseArray<Church> current = new LongSparseArray<>();
                for (final Church church : mDao
                        .get(Church.class, Contract.Church.SQL_WHERE_MINISTRY, bindValues(ministryId))) {
                    current.put(church.getId(), church);
                }

                // process all fetched churches
                long[] ids = new long[current.size() + churches.size()];
                int j = 0;
                for (final Church church : churches) {
                    final long id = church.getId();
                    final Church existing = current.get(id);

                    // persist church in database (if it doesn't exist or (isn't new and isn't dirty))
                    if (existing == null || (!existing.isNew() && !existing.isDirty())) {
                        church.setLastSynced(new Date());
                        mDao.updateOrInsert(church, PROJECTION_GET_CHURCHES_DATA);

                        // mark this id as having been changed
                        ids[j++] = id;
                    }

                    // remove this church from the list of churches
                    current.remove(id);
                }

                // delete any remaining churches that weren't returned from the API
                for (int i = 0; i < current.size(); i++) {
                    final Church church = current.valueAt(i);
                    // only delete the church if it isn't new
                    if (!church.isNew()) {
                        mDao.delete(church);

                        // mark these ids as being updated as well
                        ids[j++] = church.getId();
                    }
                }

                // mark transaction successful
                tx.setSuccessful();

                // send broadcasts that data has been updated
                broadcastManager.sendBroadcast(
                        BroadcastUtils.updateChurchesBroadcast(ministryId, Arrays.copyOf(ids, j)));
            } finally {
                tx.end();
            }
        }
    }

    @SuppressWarnings("AccessToStaticFieldLockedOnInstance")
    private void syncDirtyChurches(@NonNull final GmaApiClient api) throws ApiException {
        synchronized (mLockDirtyChurches) {
            final List<Church> churches = mDao.get(Church.class, Contract.Church.SQL_WHERE_DIRTY, null);

            // ministry_id => church_id
            final Multimap<String, Long> broadcasts = HashMultimap.create();

            // process all churches that are dirty
            for (final Church church : churches) {
                try {
                    if (church.isNew()) {
                        // try creating the church
                        final Church newChurch = api.createChurch(church);

                        // update id of church
                        if (newChurch != null) {
                            mDao.delete(church);
                            newChurch.setLastSynced(new Date());
                            mDao.updateOrInsert(newChurch, PROJECTION_GET_CHURCHES_DATA);

                            // add church to list of broadcasts
                            broadcasts.put(church.getMinistryId(), church.getId());
                            broadcasts.put(church.getMinistryId(), newChurch.getId());
                        }
                    } else if (church.isDirty()) {
                        // generate dirty JSON
                        final JSONObject json = church.dirtyToJson();

                        // update the church
                        final boolean success = api.updateChurch(church.getId(), json);

                        // was successful update?
                        if (success) {
                            // clear dirty attributes
                            church.setDirty(null);
                            mDao.update(church, new String[] {Contract.Church.COLUMN_DIRTY});

                            // add church to list of broadcasts
                            broadcasts.put(church.getMinistryId(), church.getId());
                        }
                    }
                } catch (final JSONException ignored) {
                    // this shouldn't happen when generating json
                }
            }

            // send broadcasts for each ministryId with churches that were changed
            for (final String ministryId : broadcasts.keySet()) {
                broadcastManager.sendBroadcast(
                        BroadcastUtils.updateChurchesBroadcast(ministryId, Longs.toArray(broadcasts.get(ministryId))));
            }
        }
    }

    /* END Church sync */

    /* BEGIN Measurement sync */

    private static final String[] PROJECTION_SYNC_MEASUREMENT_TYPES_TYPE =
            {Contract.MeasurementType.COLUMN_NAME, Contract.MeasurementType.COLUMN_DESCRIPTION,
                    Contract.MeasurementType.COLUMN_SECTION, Contract.MeasurementType.COLUMN_COLUMN,
                    Contract.MeasurementType.COLUMN_SORT_ORDER, Contract.MeasurementType.COLUMN_PERSONAL_ID,
                    Contract.MeasurementType.COLUMN_LOCAL_ID, Contract.MeasurementType.COLUMN_TOTAL_ID,
                    Contract.MeasurementType.COLUMN_LAST_SYNCED};

    private void syncMeasurementTypes(@NonNull final GmaApiClient api, final Intent intent) throws ApiException {
        final List<MeasurementType> types = api.getMeasurementTypes();
        if (types != null) {
            final List<String> updatedTypes = new ArrayList<>();

            for (final MeasurementType type : types) {
                mDao.updateOrInsert(type, PROJECTION_SYNC_MEASUREMENT_TYPES_TYPE);
                updatedTypes.add(type.getPermLinkStub());
            }

            // send broadcasts
            if (!updatedTypes.isEmpty()) {
                broadcastManager.sendBroadcast(BroadcastUtils.updateMeasurementTypesBroadcast(
                        updatedTypes.toArray(new String[updatedTypes.size()])));
            }
        }
    }

    private static final String[] PROJECTION_SYNC_MEASUREMENTS_TYPE =
            {Contract.MeasurementType.COLUMN_NAME, Contract.MeasurementType.COLUMN_PERM_LINK_STUB,
                    Contract.MeasurementType.COLUMN_DESCRIPTION, Contract.MeasurementType.COLUMN_SECTION,
                    Contract.MeasurementType.COLUMN_COLUMN, Contract.MeasurementType.COLUMN_SORT_ORDER,
                    Contract.MeasurementType.COLUMN_LOCAL_ID, Contract.MeasurementType.COLUMN_PERSONAL_ID,
                    Contract.MeasurementType.COLUMN_TOTAL_ID, Contract.MeasurementType.COLUMN_LAST_SYNCED};
    private static final String[] PROJECTION_SYNC_MEASUREMENTS_MINISTRY_MEASUREMENT =
            {Contract.MinistryMeasurement.COLUMN_VALUE, Contract.MinistryMeasurement.COLUMN_LAST_SYNCED};
    private static final String[] PROJECTION_SYNC_MEASUREMENTS_PERSONAL_MEASUREMENT =
            {Contract.PersonalMeasurement.COLUMN_VALUE, Contract.PersonalMeasurement.COLUMN_LAST_SYNCED};

    private void syncMeasurements(@NonNull final GmaApiClient api, final Intent intent) throws ApiException {
        // get parameters for sync from the intent & sanitize
        final String guid = intent.getStringExtra(EXTRA_GUID);
        final String ministryId = intent.getStringExtra(EXTRA_MINISTRY_ID);
        final Mcc mcc = Mcc.fromRaw(intent.getStringExtra(Constants.ARG_MCC));
        final String rawPeriod = intent.getStringExtra(Constants.ARG_PERIOD);
        final YearMonth period = rawPeriod != null ? YearMonth.parse(rawPeriod) : YearMonth.now();
        if (guid == null) {
            return;
        }
        if (ministryId == null || ministryId.equals(Ministry.INVALID_ID)) {
            return;
        }
        if (mcc == Mcc.UNKNOWN) {
            return;
        }

        // fetch the requested measurements from the api
        final List<Measurement> measurements = api.getMeasurements(ministryId, mcc, period);
        if (measurements != null) {
            saveMeasurements(measurements, guid, ministryId, mcc, period, true);
        }
    }

    private void syncDirtyMeasurements(@NonNull final GmaApiClient api, @NonNull final Intent intent)
            throws ApiException {
        // get parameters for sync from the intent & sanitize
        final String guid = intent.getStringExtra(EXTRA_GUID);
        final String ministryId = intent.getStringExtra(EXTRA_MINISTRY_ID);
        final Mcc mcc = Mcc.fromRaw(intent.getStringExtra(Constants.ARG_MCC));
        final String rawPeriod = intent.getStringExtra(Constants.ARG_PERIOD);
        final YearMonth period = rawPeriod != null ? YearMonth.parse(rawPeriod) : YearMonth.now();
        if (guid == null) {
            return;
        }
        if (ministryId == null || ministryId.equals(Ministry.INVALID_ID)) {
            return;
        }
        if (mcc == Mcc.UNKNOWN) {
            return;
        }

        // synchronize on updating this ministry, mcc & period
        synchronized (ThreadUtils.getLock(mLocksDirtyMeasurements, new GenericKey(guid, ministryId, mcc, period))) {
            // get the current assignment
            final Assignment assignment = mDao.find(Assignment.class, guid, ministryId);
            if (assignment == null) {
                return;
            }

            // check for dirty measurements to update
            List<MeasurementValue> dirty = getDirtyMeasurements(assignment, mcc, period);
            if (dirty.isEmpty()) {
                return;
            }

            // update measurements from server before submitting updates
            List<Measurement> measurements = api.getMeasurements(ministryId, mcc, period);
            if (measurements == null) {
                return;
            }
            saveMeasurements(measurements, guid, ministryId, mcc, period, false);

            // refresh the list of dirty measurements
            dirty = getDirtyMeasurements(assignment, mcc, period);
            if (dirty.isEmpty()) {
                return;
            }

            // populate dirty measurement objects with the MeasurementType and Assignment
            final Map<String, MeasurementType> types =
                    Maps.uniqueIndex(mDao.get(MeasurementType.class), MeasurementType.FUNCTION_PERMLINK);
            for (final MeasurementValue value : dirty) {
                value.setType(types.get(value.getPermLinkStub()));
                if (value instanceof PersonalMeasurement) {
                    ((PersonalMeasurement) value).setAssignment(assignment);
                }
            }

            // sync the measurements
            final boolean synced = api.updateMeasurements(dirty.toArray(new MeasurementValue[dirty.size()]));

            // update database for any synced measurements
            if (synced) {
                // clear dirty values for the measurements updated
                for (final MeasurementValue value : dirty) {
                    mDao.updateMeasurementValueDelta(value, 0 - value.getDelta());

                    // Force a details sync for this updated measurement
                    syncMeasurementDetails(this, guid, ministryId, mcc, value.getPermLinkStub(), period, true);
                }

                // update the measurements one last time
                measurements = api.getMeasurements(ministryId, mcc, period);
                if (measurements != null) {
                    saveMeasurements(measurements, guid, ministryId, mcc, period, true);
                }
            }
        }
    }

    @NonNull
    private List<MeasurementValue> getDirtyMeasurements(@NonNull final Assignment assignment, @NonNull final Mcc mcc,
                                                        @NonNull final YearMonth period) {
        final List<MeasurementValue> dirty = new ArrayList<>();
        if (assignment.can(UPDATE_PERSONAL_MEASUREMENTS)) {
            dirty.addAll(mDao.get(PersonalMeasurement.class, Contract.PersonalMeasurement.SQL_WHERE_DIRTY + " AND " +
                                          Contract.PersonalMeasurement.SQL_WHERE_GUID_MINISTRY_MCC_PERIOD,
                                  bindValues(assignment.getGuid(), assignment.getMinistryId(), mcc, period)));
        }
        if (assignment.can(UPDATE_MINISTRY_MEASUREMENTS)) {
            dirty.addAll(mDao.get(MinistryMeasurement.class, Contract.MinistryMeasurement.SQL_WHERE_DIRTY + " AND " +
                                          Contract.MinistryMeasurement.SQL_WHERE_MINISTRY_MCC_PERIOD,
                                  bindValues(assignment.getMinistryId(), mcc, period)));
        }
        return dirty;
    }

    private void saveMeasurements(@NonNull final List<Measurement> measurements, @NonNull final String guid,
                                  @NonNull final String ministryId, @NonNull final Mcc mcc,
                                  @NonNull final YearMonth period, final boolean sendBroadcasts) {
        final List<String> updatedTypes = new ArrayList<>();
        final Set<String> updatedValues = new HashSet<>();

        // update measurement data in the database
        for (final Measurement measurement : measurements) {
            // update the measurement type data
            final MeasurementType type = measurement.getType();
            if (type != null) {
                type.setLastSynced();
                mDao.updateOrInsert(type, PROJECTION_SYNC_MEASUREMENTS_TYPE);
                updatedTypes.add(type.getPermLinkStub());
            }

            // update ministry measurements
            final MinistryMeasurement ministryMeasurement = measurement.getMinistryMeasurement();
            if (ministryMeasurement != null) {
                ministryMeasurement.setLastSynced();
                mDao.updateOrInsert(ministryMeasurement, PROJECTION_SYNC_MEASUREMENTS_MINISTRY_MEASUREMENT);
                updatedValues.add(ministryMeasurement.getPermLinkStub());
            }

            // update personal measurements
            final PersonalMeasurement personalMeasurement = measurement.getPersonalMeasurement();
            if (personalMeasurement != null) {
                personalMeasurement.setLastSynced();
                mDao.updateOrInsert(personalMeasurement, PROJECTION_SYNC_MEASUREMENTS_PERSONAL_MEASUREMENT);
                updatedValues.add(personalMeasurement.getPermLinkStub());
            }
        }

        if (sendBroadcasts) {
            if (!updatedTypes.isEmpty()) {
                broadcastManager.sendBroadcast(BroadcastUtils.updateMeasurementTypesBroadcast(
                        updatedTypes.toArray(new String[updatedTypes.size()])));
            }

            if (!updatedValues.isEmpty()) {
                broadcastManager.sendBroadcast(BroadcastUtils.updateMeasurementValuesBroadcast(
                        ministryId, mcc, period, guid, updatedValues.toArray(new String[updatedValues.size()])));
            }
        }
    }

    private void syncMeasurementDetails(@NonNull final GmaApiClient api, @NonNull final Intent intent)
            throws ApiException {
        // get parameters for sync from the intent & sanitize
        final String guid = intent.getStringExtra(EXTRA_GUID);
        final String ministryId = intent.getStringExtra(EXTRA_MINISTRY_ID);
        final Mcc mcc = Mcc.fromRaw(intent.getStringExtra(Constants.ARG_MCC));
        final String permLink = intent.getStringExtra(EXTRA_PERMLINK);
        final String rawPeriod = intent.getStringExtra(Constants.ARG_PERIOD);
        final YearMonth period = rawPeriod != null ? YearMonth.parse(rawPeriod) : YearMonth.now();
        boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
        if (guid == null) {
            return;
        }
        if (ministryId == null || ministryId.equals(Ministry.INVALID_ID)) {
            return;
        }
        if (mcc == Mcc.UNKNOWN) {
            return;
        }
        if (permLink == null) {
            return;
        }

        // is the cached data stale?
        boolean stale = false;
        if (!force) {
            // calculate the stale duration for the requested period
            final long staleDuration;
            final int comparison = period.compareTo(YearMonth.now().minusMonths(1));
            if (comparison > 0) {
                staleDuration = STALE_DURATION_MEASUREMENT_DETAILS_CURRENT;
            } else if (comparison == 0) {
                staleDuration = STALE_DURATION_MEASUREMENT_DETAILS_PREVIOUS;
            } else {
                staleDuration = STALE_DURATION_MEASUREMENT_DETAILS_OLD;
            }

            // check if the currently cached measurement details are stale unless we are already planning on syncing
            final MeasurementDetails details =
                    mDao.find(MeasurementDetails.class, guid, ministryId, mcc, permLink, period);
            stale = details == null || details.getVersion() < GMA_API_VERSION ||
                    System.currentTimeMillis() - details.getLastSynced() > staleDuration;
        }

        if (force || stale) {
            // fetch details from the API
            final MeasurementDetails details = api.getMeasurementDetails(ministryId, mcc, permLink, period);
            if (details != null) {
                details.setLastSynced();
                mDao.updateOrInsert(details, new String[] {Contract.MeasurementDetails.COLUMN_JSON,
                        Contract.MeasurementDetails.COLUMN_LAST_SYNCED});

                // broadcast the measurement details sync
                broadcastManager.sendBroadcast(
                        BroadcastUtils.updateMeasurementDetailsBroadcast(ministryId, mcc, period, guid, permLink));
            }
        }
    }

    /* END Measurements sync */
}