package com.liteplus.app;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

/**
 * ProxyVpnService — establishes a VPN interface so Android shows the
 * VPN key icon in the status bar.  Actual traffic routing for the WebView
 * is handled by androidx.webkit ProxyController (in MainActivity); the TUN
 * interface here carries no routes so no traffic is diverted through it.
 */
public class ProxyVpnService extends VpnService {

    static final String ACTION_START = "com.liteplus.app.ACTION_START_VPN";
    static final String ACTION_STOP  = "com.liteplus.app.ACTION_STOP_VPN";

    static volatile ProxyVpnService instance = null;

    private ParcelFileDescriptor vpnInterface = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            teardown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (vpnInterface == null) {
            setup();
        }
        return START_STICKY;
    }

    private void setup() {
        try {
            // Build a minimal VPN interface.
            // No addRoute() → no traffic is intercepted; only the icon appears.
            // ProxyController (in MainActivity) handles actual WebView routing.
            vpnInterface = new Builder()
                .setSession("Multi Plus Proxy")
                .addAddress("10.44.0.1", 32)
                .addDnsServer("8.8.8.8")
                .establish();
            if (vpnInterface != null) {
                instance = this;
            }
        } catch (Exception e) {
            teardown();
            stopSelf();
        }
    }

    private void teardown() {
        instance = null;
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onRevoke() {
        teardown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }
}
