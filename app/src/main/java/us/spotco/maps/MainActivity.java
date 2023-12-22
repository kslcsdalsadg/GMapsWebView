/*
Copyright (c) 2017-2019 Divested Computing Group

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package us.spotco.maps;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView mapsWebView = null;
    private WebSettings mapsWebSettings = null;
    private CookieManager mapsCookieManager = null;
    private final Context context = this;
    private LocationManager locationManager;

    private static final ArrayList<String> allowedDomains = new ArrayList<String>();
    private static final ArrayList<String> allowedDomainsStart = new ArrayList<String>();
    private static final ArrayList<String> allowedDomainsEnd = new ArrayList<String>();
    private static final ArrayList<String> blockedURLs = new ArrayList<String>();

    private static final DateFormat consentDateFormat = new SimpleDateFormat("yyyyMMdd");
    private static final String TAG = "GMapsWV";
    private static LocationListener locationListenerGPS;
    private static final boolean canUseLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    private static int locationRequestCount = 0;

    @Override
    protected void onPause() {
        super.onPause();
        if (canUseLocation && locationListenerGPS != null) removeLocationListener();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (canUseLocation) {
            locationListenerGPS = getNewLocationListener();
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListenerGPS);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String urlToLoad = "https://www.google.com/maps";
        try {
            Intent intent = getIntent();
            Uri data = intent.getData();
            urlToLoad = data.toString();
            if (data.toString().startsWith("https://")) {
                urlToLoad = data.toString();
            } else if (data.toString().startsWith("geo:")) {
                urlToLoad = "https://www.google.com/maps/place/" + data.toString().substring(4);
            }
        } catch (Exception e) {
            Log.d(TAG, "No or Invalid URL passed. Opening homepage instead.");
        }

        //Create the WebView
        mapsWebView = findViewById(R.id.mapsWebView);

        //Set cookie options
        mapsCookieManager = CookieManager.getInstance();
        mapsCookieManager.setAcceptCookie(true);
        mapsCookieManager.setAcceptThirdPartyCookies(mapsWebView, false);

        //Delete anything from previous sessions
        resetWebView(false);

        //Set the consent cookie to prevent unnecessary redirections
        setConsentCookie();

        //Restrict what gets loaded
        initURLs();

        //Lister for Link sharing
        initShareLinkListener();

        //Give location access
        mapsWebView.setWebChromeClient(new WebChromeClient() {
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (canUseLocation) {
                    if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        if(locationRequestCount < 2) { //Don't annoy the user
                            new AlertDialog.Builder(context)
                                    .setTitle(R.string.title_location_permission)
                                    .setMessage(R.string.text_location_permission)
                                    .setNegativeButton(android.R.string.no, (dialogInterface, i) -> {
                                        //Disable prompts
                                        locationRequestCount = 100;
                                    }).setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                                        //Prompt the user once explanation has been shown
                                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 100);
                                    })
                                    .create()
                                    .show();
                        }
                        locationRequestCount++;
                    } else {
                        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                            Toast.makeText(context, R.string.error_no_gps, Toast.LENGTH_LONG).show();
                        }
                    }
                }
                if (origin.contains("google.com")) {
                    callback.invoke(origin, true, false);
                }
            }
        });

        mapsWebView.setWebViewClient(new WebViewClient() {
            //Keep these in sync!
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().equals("about:blank")) {
                    return null;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs that aren't HTTPS
                }
                if (request.getUrl().getHost().equals("maps.app.goo.gl")) {
                    return null;
                }
                boolean allowed = false;
                for (String url : allowedDomains) {
                    if (request.getUrl().getHost().equals(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsStart) {
                    if (request.getUrl().getHost().startsWith(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsEnd) {
                    if (request.getUrl().getHost().endsWith(url)) {
                        allowed = true;
                    }
                }
                if (!allowed) {
                    Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs not on ALLOWLIST
                }
                for (String url : blockedURLs) {
                    if (request.getUrl().toString().contains(url)) {
                        if (request.getUrl().toString().contains("/log204?")) {
                            Log.d(TAG, "[shouldInterceptRequest][ON DENYLIST] Blocked access to a log204 request");
                        } else {
                            Log.d(TAG, "[shouldInterceptRequest][ON DENYLIST] Blocked access to " + request.getUrl().toString());
                        }
                        return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs on DENYLIST
                    }
                }
                return null;
            }

            private boolean processMapsAppGooGlRedirect(WebResourceRequest request) {
                try {
                    Pattern pattern = Pattern.compile("^intent://maps\\.app\\.goo\\.gl/.+;S.browser_fallback_url=([^;]+);");
                    Matcher matcher = pattern.matcher(request.getUrl().toString());
                    if (matcher.find()) {
                        mapsWebView.loadUrl(URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.toString()));
                        return true;
                    }
                }
                catch (UnsupportedEncodingException e) {
                    Log.d(TAG, "[UnsupportedEncodingException] " + request.getUrl().toString());
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (processMapsAppGooGlRedirect(request)) {
                    return true;
                }
                if (request.getUrl().toString().equals("about:blank")) {
                    return false;
                }
                if (request.getUrl().toString().startsWith("tel:")) {
                    Intent dial = new Intent(Intent.ACTION_DIAL, request.getUrl());
                    startActivity(dial);
                    return true;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return true; //Deny URLs that aren't HTTPS
                }
                boolean allowed = false;
                for (String url : allowedDomains) {
                    if (request.getUrl().getHost().equals(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsStart) {
                    if (request.getUrl().getHost().startsWith(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsEnd) {
                    if (request.getUrl().getHost().endsWith(url)) {
                        allowed = true;
                    }
                }
                if (!allowed) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    return true; //Deny URLs not on ALLOWLIST
                }
                for (String url : blockedURLs) {
                    if (request.getUrl().toString().contains(url)) {
                        Log.d(TAG, "[shouldOverrideUrlLoading][ON DENYLIST] Blocked access to " + request.getUrl().toString());
                        return true; //Deny URLs on DENYLIST
                    }
                }
                return false;
            }
        });

        //Set more options
        mapsWebSettings = mapsWebView.getSettings();
        //Enable some WebView features
        mapsWebSettings.setJavaScriptEnabled(true);
        mapsWebSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        mapsWebSettings.setGeolocationEnabled(true);
        //Disable some WebView features
        mapsWebSettings.setAllowContentAccess(false);
        mapsWebSettings.setAllowFileAccess(false);
        mapsWebSettings.setBuiltInZoomControls(false);
        mapsWebSettings.setDatabaseEnabled(false);
        mapsWebSettings.setDisplayZoomControls(false);
        mapsWebSettings.setDomStorageEnabled(false);
        mapsWebSettings.setSaveFormData(false);
        //Change the User-Agent
        mapsWebSettings.setUserAgentString("Mozilla/5.0 (Linux; Android 12; Unspecified Device) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.79 Mobile Safari/537.36");

        //Load Google Maps
        mapsWebView.loadUrl(urlToLoad);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        resetWebView(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Credit (CC BY-SA 3.0): https://stackoverflow.com/a/6077173
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    if (mapsWebView.canGoBack() && !mapsWebView.getUrl().equals("about:blank")) {
                        mapsWebView.goBack();
                    } else {
                        finish();
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void resetWebView(boolean exit) {
        if (exit) {
            mapsWebView.loadUrl("about:blank");
            mapsWebView.removeAllViews();
            mapsWebSettings.setJavaScriptEnabled(false);
        }
        //mapsWebView.clearCache(true);
        mapsWebView.clearFormData();
        mapsWebView.clearHistory();
        mapsWebView.clearMatches();
        mapsWebView.clearSslPreferences();
        mapsCookieManager.removeSessionCookie();
        mapsCookieManager.removeAllCookie();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        if (exit) {
            mapsWebView.destroyDrawingCache();
            mapsWebView.destroy();
            mapsWebView = null;
        }
    }

    private void setConsentCookie() {
        String consentDate = consentDateFormat.format(System.currentTimeMillis());
        Random random = new Random();
        int random2digit = random.nextInt(2) + 15;
        int random3digit = random.nextInt(999);
        String consentCookie = "YES+cb." + consentDate + "-" + random2digit + "-p1.en+F+" + random3digit;
        mapsCookieManager.setCookie(".google.com", "CONSENT=" + consentCookie + ";");
        //mapsCookieManager.setCookie(".google.com", "CONSENT=PENDING+" + random3digit + ";"); //alternative
        mapsCookieManager.setCookie(".google.com", "ANID=OPT_OUT;");
    }

    private static void initURLs() {
        //Allowed Domains
        allowedDomains.add("apis.google.com");
        allowedDomains.add("fonts.gstatic.com");
        allowedDomains.add("maps.google.com");
        allowedDomains.add("maps.gstatic.com");
        allowedDomains.add("ssl.gstatic.com");
        allowedDomains.add("www.google.com");
        allowedDomains.add("www.gstatic.com");
        allowedDomains.add("consent.google.com");
        allowedDomains.add("consent.youtube.com"); //XXX: Maybe not required?
        allowedDomains.add("maps.google.com");
        allowedDomains.add("khms0.google.com");
        allowedDomains.add("khms1.google.com");
        allowedDomains.add("khms2.google.com");
        allowedDomains.add("khms3.google.com");
        allowedDomains.add("streetviewpixels-pa.googleapis.com");
        allowedDomainsStart.add("consent.google."); //TODO: better cctld handling
        allowedDomainsEnd.add(".googleusercontent.com");

        //Blocked Domains
        blockedURLs.add("analytics.google.com");
        blockedURLs.add("clientmetrics-pa.googleapis.com");
        blockedURLs.add("doubleclick.com");
        blockedURLs.add("doubleclick.net");
        blockedURLs.add("googleadservices.com");
        blockedURLs.add("google-analytics.com");
        blockedURLs.add("googlesyndication.com");
        blockedURLs.add("tpc.googlesyndication.com");
        blockedURLs.add("pagead.l.google.com");
        blockedURLs.add("partnerad.l.google.com");
        blockedURLs.add("video-stats.video.google.com");
        blockedURLs.add("wintricksbanner.googlepages.com");
        blockedURLs.add("www-google-analytics.l.google.com");
        blockedURLs.add("gstaticadssl.l.google.com");
        blockedURLs.add("csp.withgoogle.com");

        //Blocked URLs
        blockedURLs.add("google.com/maps/preview/log204");
        blockedURLs.add("google.com/gen_204");
        blockedURLs.add("play.google.com/log");
        blockedURLs.add("/gen_204?");
        blockedURLs.add("/log204?");
    }

    private LocationListener getNewLocationListener() {
        return new LocationListener() {
            @Override
            public void onLocationChanged(android.location.Location location) {
            }

            @Deprecated
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
    }

    private void removeLocationListener() {
        if (locationListenerGPS != null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationListenerGPS != null) locationManager.removeUpdates(locationListenerGPS);
        }
        locationListenerGPS = null;
    }

    private void initShareLinkListener() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.addPrimaryClipChangedListener(new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                String url = mapsWebView.getUrl();
                String regex = "@(-?d*\\d+.\\d+),(-?d*\\d+.\\d+)";
                Pattern p = Pattern.compile(regex);
                Matcher m = p.matcher(url);
                if (m.find()) {
                    String latlon = m.group(1) + "," + m.group(2);
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + latlon + "?q=" + latlon)));
                    } catch (ActivityNotFoundException ignored) {
                        Toast.makeText(context, R.string.no_app_installed, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }
}
