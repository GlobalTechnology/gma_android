package com.expidev.gcmapp.sql;

import android.content.Context;

/**
 * Created by William.Randall on 1/19/2015.
 */
public class QueryHelper
{
    public static void retrieveMinistries(
        Context context,
        RetrieveMinistriesDatabaseTask.RetrieveMinistriesDatabaseTaskHandler taskHandler)
    {
        new RetrieveMinistriesDatabaseTask(taskHandler).execute(context, "associated_ministries");
    }
}