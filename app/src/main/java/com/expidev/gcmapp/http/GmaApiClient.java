package com.expidev.gcmapp.http;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.util.Log;

import com.expidev.gcmapp.BuildConfig;
import com.expidev.gcmapp.json.MinistryJsonParser;
import com.expidev.gcmapp.model.Ministry;
import com.expidev.gcmapp.utils.JsonStringReader;

import org.apache.http.HttpStatus;
import org.ccci.gto.android.common.api.AbstractApi;
import org.ccci.gto.android.common.api.ApiException;
import org.ccci.gto.android.common.util.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import me.thekey.android.TheKey;
import me.thekey.android.lib.TheKeyImpl;

import static com.expidev.gcmapp.BuildConfig.THEKEY_CLIENTID;

/**
 * Created by matthewfrederick on 1/23/15.
 */
public class GmaApiClient extends AbstractApi<AbstractApi.Request> {
    private final String TAG = getClass().getSimpleName();

    private static final String MINISTRIES = "ministries";
    private static final String TOKEN = "token";
    private static final String TRAINING = "training";

    private final String PREF_NAME = "gcm_prefs";

    private final TheKey theKey;

    private Context context;
    
    private SharedPreferences preferences;
    private SharedPreferences.Editor prefEditor;

    public GmaApiClient(final Context context)
    {
        super(BuildConfig.GCM_BASE_URI);
        theKey = TheKeyImpl.getInstance(context, THEKEY_CLIENTID);
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefEditor = preferences.edit();
    }

    @NonNull
    @Override
    protected HttpURLConnection prepareRequest(@NonNull final Request request, @NonNull final HttpURLConnection conn)
            throws ApiException, IOException {
        final HttpURLConnection conn2 = prepareRequest(super.prepareRequest(request, conn));
        conn2.setConnectTimeout(10000);
        conn2.setReadTimeout(10000);
        return conn2;
    }

    @Override
    protected void processResponse(@NonNull Request request, @NonNull HttpURLConnection conn)
            throws ApiException, IOException {
        super.processResponse(request, conn);
        processResponse(conn);
    }

    private HttpURLConnection prepareRequest(HttpURLConnection connection) {
        String cookie = preferences.getString("Cookie", "");
        
        if (!cookie.isEmpty())
        {
            Log.i(TAG, "Cookie added: " + cookie);
            connection.addRequestProperty("Cookie", cookie);
        }
        else
        {
            Log.w(TAG, "No Cookies found");
        }
        
        return connection;
    }

    private HttpURLConnection processResponse(HttpURLConnection connection) throws IOException {
        if (connection.getHeaderFields() != null)
        {
            String headerName;
            StringBuilder stringBuilder = new StringBuilder();
            for (int i = 1; (headerName = connection.getHeaderFieldKey(i)) != null; i++)
            {
                if (headerName.equals("Set-Cookie"))
                {
                    String cookie = connection.getHeaderField(i);
                    cookie = cookie.split("\\;")[0] + "; ";
                    stringBuilder.append(cookie);
                }
            }

            // cookie store is not retrieving cookie so it will be saved to preferences
            if (!stringBuilder.toString().isEmpty())
            {
                prefEditor.putString("Cookie", stringBuilder.toString());
                prefEditor.apply();
            }
            
        }
        return connection;
    }

    public JSONObject authorizeUser()
    {
        HttpURLConnection conn = null;
        try
        {
            final String ticket = theKey.getTicket(BuildConfig.GCM_BASE_URI + TOKEN);
            Log.i(TAG, "Ticket: " + ticket);

            if (ticket == null) return null;

            // build request
            final Request request = new Request(TOKEN);
            request.accept = Request.MediaType.APPLICATION_JSON;
            request.params.add(param("st", ticket));
            request.params.add(param("refresh", "true"));

            // send request (tickets are one time use only, so we can't retry)
            conn = this.sendRequest(request, 0);

            // parse valid responses
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return new JSONObject(IOUtils.readString(conn.getInputStream()));
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(conn);
        }
        
        return null;
    }

    public List<Ministry> getAllMinistries(String sessionToken)
    {
        String reason;
        String urlString = BuildConfig.GCM_BASE_URI + MINISTRIES + "?token=" + sessionToken;

        try
        {
            String json = httpGet(new URL(urlString));

            if(json == null)
            {
                Log.e(TAG, "Failed to retrieve ministries, most likely cause is a bad session ticket");
                return null;
            }
            else
            {
                if(json.startsWith("["))
                {
                    JSONArray jsonArray = new JSONArray(json);
                    return MinistryJsonParser.parseMinistriesJson(jsonArray);
                }
                else
                {
                    JSONObject jsonObject = new JSONObject(json);
                    reason = jsonObject.optString("reason");
                    Log.e(TAG, reason);
                    return null;
                }
            }
        }
        catch(Exception e)
        {
            reason = e.getMessage();
            Log.e(TAG, "Problem occurred while retrieving ministries: " + reason);
            return null;
        }
    }

    public JSONArray searchTraining(String ministryId, String mcc, String sessionTicket)
    {
        try
        {
            String urlString = BuildConfig.GCM_BASE_URI + TRAINING +
                    "?token=" + sessionTicket + "&ministry_id=" + ministryId +
                    "&mcc=" + mcc;

            Log.i(TAG, "Url: " + urlString);

            URL url = new URL(urlString);

            return new JSONArray(httpGet(url));
        }
        catch (Exception e)
        {
            Log.e(TAG, e.getMessage(), e);
        }

        return null;
    }

    public JSONArray searchMeasurements(String ministryId, String mcc, String period, String sessionTicket)
    {
        try
        {
            String urlString = BuildConfig.GCM_BASE_URI + "measurements" +
                "?token=" + sessionTicket + "&ministry_id=" + ministryId + "&mcc=" + mcc;

            if(period != null)
            {
                urlString += "&period=" + period;
            }

            Log.i(TAG, "Url: " + urlString);

            return new JSONArray(httpGet(new URL(urlString)));
        }
        catch(Exception e)
        {
            Log.e(TAG, e.getMessage(), e);
        }

        return null;
    }

    public JSONObject getDetailsForMeasurement(
        String measurementId,
        String sessionTicket,
        String ministryId,
        String mcc,
        String period)
    {
        try
        {
            String urlString = BuildConfig.GCM_BASE_URI + "measurements/" + measurementId +
                "?token=" + sessionTicket +
                "&ministry_id=" + ministryId +
                "&mcc=" + mcc;

            if(period != null)
            {
                urlString += "&period=" + period;
            }

            Log.i(TAG, "Url: " + urlString);

            return new JSONObject(httpGet(new URL(urlString)));
        }
        catch(Exception e)
        {
            Log.e(TAG, e.getMessage(), e);
        }

        return null;
    }
    
    private String httpGet(URL url) throws IOException, JSONException, URISyntaxException
    {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        prepareRequest(connection);

        connection.setReadTimeout(10000);
        connection.setConnectTimeout(10000);

        connection.connect();
        processResponse(connection);

        if (connection.getResponseCode() == HttpStatus.SC_OK)
        {
            InputStream inputStream = connection.getInputStream();

            if (inputStream != null)
            {
                String jsonAsString = JsonStringReader.readFully(inputStream, "UTF-8");
                Log.i(TAG, jsonAsString);

                // instead of returning a JSONObject, a string will be returned. This is
                // because some endpoints return an object and some return an array.
                return jsonAsString;
            }
        }
        else
        {
            Log.d(TAG, "Status: " + connection.getResponseCode());
        }

        return null;
    }
}
