package com.expidev.gcmapp.activity;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;

import com.expidev.gcmapp.R;

public class SettingsActivity extends ActionBarActivity {
    /* BEGIN lifecycle */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
    }

    /* END lifecycle */
}