package com.expidev.gcmapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.expidev.gcmapp.model.AssociatedMinistry;
import com.expidev.gcmapp.model.Ministry;
import com.expidev.gcmapp.model.measurement.Measurement;
import com.expidev.gcmapp.service.MeasurementsService;
import com.expidev.gcmapp.service.MinistriesService;
import com.expidev.gcmapp.service.Type;
import com.expidev.gcmapp.support.v4.content.CurrentMinistryLoader;
import com.expidev.gcmapp.support.v4.content.MeasurementsLoader;
import com.expidev.gcmapp.utils.BroadcastUtils;
import com.expidev.gcmapp.utils.ViewUtils;
import com.expidev.gcmapp.view.TextHeaderView;

import org.ccci.gto.android.common.support.v4.app.SimpleLoaderCallbacks;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Created by William.Randall on 2/3/2015.
 */
public class MeasurementsActivity extends ActionBarActivity
{
    private final String TAG = this.getClass().getSimpleName();

    private final String PREF_NAME = "gcm_prefs";

    private final MeasurementsLoaderCallbacks measurementsLoaderCallbacks = new MeasurementsLoaderCallbacks();
    private final AssociatedMinistryLoaderCallbacks ministryLoaderCallbacks = new AssociatedMinistryLoaderCallbacks();

    private LocalBroadcastManager broadcastManager;
    private BroadcastReceiver broadcastReceiver;
    private SharedPreferences preferences;
    private Ministry chosenMinistry;
    private String chosenMcc;
    private String currentPeriod = null;

    private static final int LOADER_CURRENT_MINISTRY = 1;
    private static final int LOADER_MEASUREMENTS = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_measurements);

        preferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        chosenMcc = preferences.getString("chosen_mcc", null);
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        currentPeriod = preferences.getString("currentPeriod", null);
        setupBroadcastReceivers();
        startLoaders();
        MinistriesService.retrieveMinistries(this);
    }

    private void startLoaders()
    {
        final LoaderManager manager = this.getSupportLoaderManager();

        manager.initLoader(LOADER_CURRENT_MINISTRY, null, ministryLoaderCallbacks);
    }

    private void restartMeasurementLoader(String ministryId, String mcc, String period)
    {
        final LoaderManager manager = this.getSupportLoaderManager();

        Bundle args = new Bundle(3);
        args.putString("ministryId", ministryId);
        args.putString("mcc", mcc);
        args.putString("period", period);

        manager.restartLoader(LOADER_MEASUREMENTS, args, measurementsLoaderCallbacks);
    }

    private void setupBroadcastReceivers()
    {
        broadcastManager = LocalBroadcastManager.getInstance(this);

        broadcastReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                if (BroadcastUtils.ACTION_START.equals(intent.getAction()))
                {
                    Log.i(TAG, "Action Started");
                }
                else if (BroadcastUtils.ACTION_RUNNING.equals(intent.getAction()))
                {
                    Log.i(TAG, "Action Running");
                }
                else if (BroadcastUtils.ACTION_STOP.equals(intent.getAction()))
                {
                    Type type = (Type) intent.getSerializableExtra(BroadcastUtils.ACTION_TYPE);

                    switch(type)
                    {
                        case SEARCH_MEASUREMENTS:
                            Serializable measurementsData = intent.getSerializableExtra("measurements");

                            if(measurementsData != null)
                            {
                                List<Measurement> measurements = (ArrayList<Measurement>) measurementsData;
                                drawLayout(chosenMinistry, chosenMcc, measurements);

                                // Save the measurements to the database for quicker loading next time
                                MeasurementsService.saveMeasurementsToDatabase(MeasurementsActivity.this, measurements);
                            }
                            else
                            {
                                Log.w(TAG, "No measurement data");
                            }
                            break;
                        case SAVE_MEASUREMENTS:
                            Log.i(TAG, "Measurements saved to the database");
                            break;
                        default:
                            Log.i(TAG, "Unhandled Type: " + type);
                            break;
                    }
                }
            }
        };

        broadcastManager.registerReceiver(broadcastReceiver, BroadcastUtils.startFilter());
        broadcastManager.registerReceiver(broadcastReceiver, BroadcastUtils.runningFilter());
        broadcastManager.registerReceiver(broadcastReceiver, BroadcastUtils.stopFilter());
    }

    private void drawLayout(Ministry selectedMinistry, String mcc, List<Measurement> measurements)
    {
        TextView titleView = (TextView) findViewById(R.id.measurement_ministry_name);
        titleView.setText(selectedMinistry.getName() + " (" + mcc + ")");

        TextView periodView = (TextView) findViewById(R.id.currentPeriod);
        if(currentPeriod == null)
        {
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM", Locale.getDefault());
            Calendar thisMonth = Calendar.getInstance();

            currentPeriod = dateFormat.format(thisMonth.getTime());
            preferences.edit().putString("currentPeriod", currentPeriod).apply();
        }

        periodView.setText(currentPeriod);

        List<Measurement> sortedMeasurements = sortMeasurements(measurements);

        LinearLayout dataContainer = (LinearLayout) findViewById(R.id.measurement_data_Layout);

        // Clear out the data container in case the user is coming back from the measurement details page
        dataContainer.removeAllViews();

        String previousColumn = sortedMeasurements.get(0).getColumn();
        String firstMeasurementId = sortedMeasurements.get(0).getMeasurementId();

        for(Measurement measurement : sortedMeasurements)
        {
            String column = measurement.getColumn();

            if(!column.equals(previousColumn) || measurement.getMeasurementId().equals(firstMeasurementId))
            {
                // Add the new header and the data
                TextHeaderView headerView = new TextHeaderView(this);
                headerView.setText(column);
                dataContainer.addView(headerView);

                previousColumn = column;
            }
            LinearLayout row = createDataRow(
                measurement.getName(),
                measurement.getTotal(),
                measurement);

            dataContainer.addView(row);
        }
    }

    private LinearLayout createDataRow(String name, int total, final Measurement measurement)
    {
        TextView nameView = createNameView(name);
        TextView totalView = createTotalView(total);
        TextView arrowView = createArrowView();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(nameView);
        row.addView(totalView);
        row.addView(arrowView);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                drillIntoMeasurementDetails(measurement);
            }
        });

        return row;
    }

    private TextView createNameView(String name)
    {
        TextView nameView = new TextView(this);
        nameView.setText(name);

        LinearLayout.LayoutParams nameLayoutParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLayoutParams.weight = 1f;
        nameLayoutParams.setMargins(ViewUtils.dpToPixels(this, 10), 0, 0, 0);

        nameView.setLayoutParams(nameLayoutParams);

        return nameView;
    }

    private TextView createTotalView(int total)
    {
        TextView totalView = new TextView(this);
        totalView.setText(Integer.toString(total));

        LinearLayout.LayoutParams totalLayoutParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        totalLayoutParams.setMargins(0, 0, ViewUtils.dpToPixels(this, 5), 0);

        totalView.setLayoutParams(totalLayoutParams);

        return totalView;
    }

    private TextView createArrowView()
    {
        TextView arrowView = new TextView(this);
        arrowView.setText(">");

        LinearLayout.LayoutParams arrowLayoutParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arrowLayoutParams.setMargins(0, 0, ViewUtils.dpToPixels(this, 10), 0);

        arrowView.setLayoutParams(arrowLayoutParams);

        return arrowView;
    }

    /**
     * Group measurements together by column (alphabetically), then
     * sort within each group by section (win, build, send)
     */
    List<Measurement> sortMeasurements(List<Measurement> measurements)
    {
        List<Measurement> sortedMeasurements = new ArrayList<>(measurements.size());

        // First create a list of columns
        List<String> distinctColumns = new ArrayList<>();
        for(Measurement measurement : measurements)
        {
            if(!distinctColumns.contains(measurement.getColumn()))
            {
                distinctColumns.add(measurement.getColumn());
            }
        }

        // Sort the columns alphabetically
        Collections.sort(distinctColumns);

        // First group the measurements into their column groups
        for(String column : distinctColumns)
        {
            List<Measurement> measurementGroup = new ArrayList<>();

            for(Measurement measurement : measurements)
            {
                if(measurement.getColumn().equals(column))
                {
                    measurementGroup.add(measurement);
                }
            }

            // Now sort it according to section
            Collections.sort(measurementGroup, sectionComparator());
            sortedMeasurements.addAll(measurementGroup);
        }

        return sortedMeasurements;
    }

    /**
     * Sorts measurements first in order of section: win, build, send
     * Then alphabetically by name, if they have the same section
     */
    private Comparator<Measurement> sectionComparator()
    {
        return new Comparator<Measurement>()
        {
            @Override
            public int compare(Measurement lhs, Measurement rhs)
            {
                if("win".equals(lhs.getSection()))
                {
                    if(rhs.getSection().equals("win"))
                    {
                        return lhs.getName().compareTo(rhs.getName());
                    }
                    else
                    {
                        return -1;
                    }
                }
                else if(lhs.getSection().equals("build"))
                {
                    if(rhs.getSection().equals("win"))
                    {
                        return 1;
                    }
                    else if(rhs.getSection().equals("build"))
                    {
                        return lhs.getName().compareTo(rhs.getName());
                    }
                    else
                    {
                        return -1;
                    }
                }
                else
                {
                    if(rhs.getSection().equals("send"))
                    {
                        return lhs.getName().compareTo(rhs.getName());
                    }
                    else
                    {
                        return 1;
                    }
                }
            }
        };
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        cleanupBroadcastReceivers();
    }

    private void cleanupBroadcastReceivers()
    {
        broadcastManager = LocalBroadcastManager.getInstance(this);

        broadcastManager.unregisterReceiver(broadcastReceiver);
        broadcastReceiver = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_measurements, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_settings)
        {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void drillIntoMeasurementDetails(Measurement measurement)
    {
        Intent goToMeasurementDetails = new Intent(this, MeasurementDetailsActivity.class);
        goToMeasurementDetails.putExtra("measurementId", measurement.getMeasurementId());
        goToMeasurementDetails.putExtra("ministryId", chosenMinistry.getMinistryId());
        goToMeasurementDetails.putExtra("ministryName", chosenMinistry.getName());
        goToMeasurementDetails.putExtra("mcc", chosenMcc);
        goToMeasurementDetails.putExtra("measurementName", measurement.getName());

        if(currentPeriod != null)
        {
            goToMeasurementDetails.putExtra("period", currentPeriod);
        }

        startActivity(goToMeasurementDetails);
    }

    public void goToPreviousPeriod(View view)
    {
        TextView periodView = (TextView)findViewById(R.id.currentPeriod);
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM", Locale.getDefault());

        String currentPeriodString = periodView.getText().toString();

        int year = Integer.parseInt(currentPeriodString.substring(0, 4));
        // subtract 1 because months are 0 based in Calendar, but visually they are 1 based
        int month = Integer.parseInt(currentPeriodString.substring(5)) - 1;

        Calendar previousMonth = Calendar.getInstance();
        previousMonth.set(year, month, 1);
        previousMonth.add(Calendar.MONTH, -1);

        String previousPeriodString = dateFormat.format(previousMonth.getTime());

        // Change the period text to show the new period
        periodView.setText(previousPeriodString);
        currentPeriod = previousPeriodString;
        preferences.edit().putString("currentPeriod", currentPeriod).apply();

        // Clear the data, so the user knows when the new data is up
        LinearLayout dataContainer = (LinearLayout) findViewById(R.id.measurement_data_Layout);
        dataContainer.removeAllViews();

        restartMeasurementLoader(chosenMinistry.getMinistryId(), chosenMcc, previousPeriodString);
    }

    public void goToNextPeriod(View view)
    {
        TextView periodView = (TextView)findViewById(R.id.currentPeriod);
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM", Locale.getDefault());

        String currentPeriodString = periodView.getText().toString();

        int year = Integer.parseInt(currentPeriodString.substring(0, 4));
        // subtract 1 because months are 0 based in Calendar, but visually they are 1 based
        int month = Integer.parseInt(currentPeriodString.substring(5)) - 1;

        Calendar nextMonth = Calendar.getInstance();
        nextMonth.set(year, month, 1);
        nextMonth.add(Calendar.MONTH, 1);

        String nextPeriodString = dateFormat.format(nextMonth.getTime());

        // Change the period text to show the new period
        periodView.setText(nextPeriodString);
        currentPeriod = nextPeriodString;
        preferences.edit().putString("currentPeriod", currentPeriod).apply();

        // Clear the data, so the user knows when the new data is up
        LinearLayout dataContainer = (LinearLayout) findViewById(R.id.measurement_data_Layout);
        dataContainer.removeAllViews();

        restartMeasurementLoader(chosenMinistry.getMinistryId(), chosenMcc, nextPeriodString);
    }

    /**
     * This event is triggered when a new currentMinistry object is loaded
     *
     * @param ministry the new current ministry object
     */
    void onLoadCurrentMinistry(@Nullable final AssociatedMinistry ministry)
    {
        chosenMinistry = ministry;

        if(ministry != null)
        {
            Log.i(TAG, "Associated Ministries retrieved");
            restartMeasurementLoader(ministry.getMinistryId(), chosenMcc, currentPeriod);
        }
        else
        {
            Log.w(TAG, "No associated ministries");
        }
    }

    /**
     * This event is triggered when measurements are loaded from local storage
     *
     * @param measurements the list of measurements to display
     */
    void onLoadMeasurements(@Nullable final List<Measurement> measurements)
    {
        Log.i(TAG, "Measurements loaded from local storage");

        if(measurements != null && !measurements.isEmpty())
        {
            drawLayout(chosenMinistry, chosenMcc, measurements);
        }
        else
        {
            Log.w(TAG, "No measurement data in local database, try searching from API");

            MeasurementsService.searchMeasurements(
                MeasurementsActivity.this,
                chosenMinistry.getMinistryId(),
                chosenMcc,
                currentPeriod);
        }
    }

    private class AssociatedMinistryLoaderCallbacks extends SimpleLoaderCallbacks<AssociatedMinistry>
    {
        @Override
        public Loader<AssociatedMinistry> onCreateLoader(final int id, final Bundle bundle)
        {
            switch (id) {
                case LOADER_CURRENT_MINISTRY:
                    return new CurrentMinistryLoader(MeasurementsActivity.this);
                default:
                    return null;
            }
        }

        @Override
        public void onLoadFinished(
            @NonNull final Loader<AssociatedMinistry> loader,
            @Nullable final AssociatedMinistry ministry)
        {
            switch (loader.getId()) {
                case LOADER_CURRENT_MINISTRY:
                    onLoadCurrentMinistry(ministry);
                    break;
            }
        }
    }

    private class MeasurementsLoaderCallbacks extends SimpleLoaderCallbacks<List<Measurement>>
    {
        @Override
        public Loader<List<Measurement>> onCreateLoader(final int id, @Nullable final Bundle args)
        {
            switch(id)
            {
                case LOADER_MEASUREMENTS:
                    return new MeasurementsLoader(MeasurementsActivity.this, args);
                default:
                    return null;
            }
        }

        @Override
        public void onLoadFinished(
            @NonNull final Loader<List<Measurement>> loader,
            @Nullable final List<Measurement> measurements)
        {
            switch(loader.getId())
            {
                case LOADER_MEASUREMENTS:
                    onLoadMeasurements(measurements);
                    break;
            }
        }
    }
}
