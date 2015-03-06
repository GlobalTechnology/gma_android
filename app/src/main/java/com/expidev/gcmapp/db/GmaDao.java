package com.expidev.gcmapp.db;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Pair;

import com.expidev.gcmapp.model.Assignment;
import com.expidev.gcmapp.model.Church;
import com.expidev.gcmapp.model.Ministry;
import com.expidev.gcmapp.model.measurement.MeasurementType;
import com.expidev.gcmapp.model.measurement.MinistryMeasurement;
import com.expidev.gcmapp.utils.DatabaseOpenHelper;

import org.ccci.gto.android.common.db.AbstractDao;
import org.ccci.gto.android.common.db.Mapper;

public class GmaDao extends AbstractDao
{
    private static final Object instanceLock = new Object();
    private static GmaDao instance;

    private static final Mapper<Assignment> ASSIGNMENT_MAPPER = new AssignmentMapper();
    private static final Mapper<Ministry> MINISTRY_MAPPER = new MinistryMapper();
    private static final Mapper<MeasurementType> MEASUREMENT_TYPE_MAPPER = new MeasurementTypeMapper();
    private static final Mapper<MinistryMeasurement> MINISTRY_MEASUREMENT_MAPPER = new MinistryMeasurementMapper();
    private static final Mapper<Church> CHURCH_MAPPER = new ChurchMapper();

    private GmaDao(final Context context)
    {
        super(DatabaseOpenHelper.getInstance(context));
    }

    public static GmaDao getInstance(Context context)
    {
        synchronized(instanceLock)
        {
            if(instance == null)
            {
                instance = new GmaDao(context.getApplicationContext());
            }
        }

        return instance;
    }

    @NonNull
    @Override
    protected String getTable(@NonNull final Class<?> clazz)
    {
        if (Ministry.class.equals(clazz)) {
            return Contract.Ministry.TABLE_NAME;
        } else if (Assignment.class.equals(clazz)) {
            return Contract.Assignment.TABLE_NAME;
        } else if (MeasurementType.class.equals(clazz)) {
            return Contract.MeasurementType.TABLE_NAME;
        } else if (MinistryMeasurement.class.equals(clazz)) {
            return Contract.MinistryMeasurement.TABLE_NAME;
        } else if(Church.class.equals(clazz)) {
            return Contract.Church.TABLE_NAME;
        }

        return super.getTable(clazz);
    }

    @NonNull
    @Override
    public String[] getFullProjection(@NonNull final Class<?> clazz) {
        if (Ministry.class.equals(clazz)) {
            return Contract.Ministry.PROJECTION_ALL;
        } else if (Assignment.class.equals(clazz)) {
            return Contract.Assignment.PROJECTION_ALL;
        } else if (MeasurementType.class.equals(clazz)) {
            return Contract.MeasurementType.PROJECTION_ALL;
        } else if (MinistryMeasurement.class.equals(clazz)) {
            return Contract.MinistryMeasurement.PROJECTION_ALL;
        } else if (Church.class.equals(clazz)) {
            return Contract.Church.PROJECTION_ALL;
        }

        return super.getFullProjection(clazz);
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    protected <T> Mapper<T> getMapper(@NonNull final Class<T> clazz)
    {
        if (Ministry.class.equals(clazz)) {
            return (Mapper<T>) MINISTRY_MAPPER;
        } else if (Assignment.class.equals(clazz)) {
            return (Mapper<T>) ASSIGNMENT_MAPPER;
        } else if (MeasurementType.class.equals(clazz)) {
            return (Mapper<T>) MEASUREMENT_TYPE_MAPPER;
        } else if (MinistryMeasurement.class.equals(clazz)) {
            return (Mapper<T>) MINISTRY_MEASUREMENT_MAPPER;
        } else if(Church.class.equals(clazz)) {
            return (Mapper<T>) CHURCH_MAPPER;
        }

        return super.getMapper(clazz);
    }

    @NonNull
    @Override
    protected Pair<String, String[]> getPrimaryKeyWhere(@NonNull final Class<?> clazz, @NonNull final Object... key)
    {
        final int keyLength;
        final String where;

        if (Ministry.class.equals(clazz)) {
            keyLength = 1;
            where = Contract.Ministry.SQL_WHERE_PRIMARY_KEY;
        } else if (Assignment.class.equals(clazz)) {
            keyLength = 2;
            where = Contract.Assignment.SQL_WHERE_PRIMARY_KEY;
        } else if(Church.class.equals(clazz)) {
            keyLength = 1;
            where = Contract.Church.SQL_WHERE_PRIMARY_KEY;
        } else if (MeasurementType.class.equals(clazz)) {
            keyLength = 1;
            where = Contract.MeasurementType.SQL_WHERE_PRIMARY_KEY;
        } else if (MinistryMeasurement.class.equals(clazz)) {
            keyLength = 4;
            where = Contract.MinistryMeasurement.SQL_WHERE_PRIMARY_KEY;
        }
        else
        {
            return super.getPrimaryKeyWhere(clazz, key);
        }

        // throw an error if the provided key is the wrong size
        if (key.length != keyLength) {
            throw new IllegalArgumentException("invalid key for " + clazz);
        }

        // return where clause pair
        return Pair.create(where, this.getBindValues(key));
    }

    @NonNull
    @Override
    protected Pair<String, String[]> getPrimaryKeyWhere(@NonNull final Object obj)
    {
        if(obj instanceof Ministry)
        {
            return getPrimaryKeyWhere(Ministry.class, ((Ministry) obj).getMinistryId());
        } else if (obj instanceof Assignment) {
            return getPrimaryKeyWhere(Assignment.class, ((Assignment) obj).getGuid(),
                                      ((Assignment) obj).getMinistryId());
        } else if (obj instanceof MeasurementType) {
            return getPrimaryKeyWhere(MeasurementType.class, ((MeasurementType) obj).getMeasurementId());
        } else if (obj instanceof MinistryMeasurement) {
            final MinistryMeasurement measurement = (MinistryMeasurement) obj;
            return getPrimaryKeyWhere(MinistryMeasurement.class, measurement.getMinistryId(), measurement.getMcc(),
                                      measurement.getMeasurementId(), measurement.getPeriod());
        } else if (obj instanceof Church) {
            return getPrimaryKeyWhere(Church.class, ((Church) obj).getId());
        }

        return super.getPrimaryKeyWhere(obj);
    }
}