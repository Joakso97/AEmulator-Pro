/* Modified for AEmulator Super Plus, 2026-10-11: hidden setDetailedState reached by reflection (builds with the public SDK).
 * Modified for AEmulator Plus, 2026-11-01: Android 8 - 17 (NetworkAgent without NetworkInfo, Ethernet fallback). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Looper;

import java.net.InetAddress;

 
 
 
 
final class WifiNet extends NetworkAgent implements WifiLink {
     
    private static void setDetailedState(NetworkInfo ni, NetworkInfo.DetailedState st) throws Exception {
        NetworkInfo.class.getMethod("setDetailedState", NetworkInfo.DetailedState.class, String.class, String.class).invoke(ni, st, null, null);
    }
    private static final int TYPE_WIFI = 1;
    private static final int TRANSPORT_WIFI = 1;
    private static final int TYPE_ETHERNET = 9;
    private static final int TRANSPORT_ETHERNET = 3;

     
    static final class Identity {
        final int type, transport;
        final String legacyName, iface;
        private Identity(int type, int transport, String legacyName, String iface) {
            this.type = type; this.transport = transport; this.legacyName = legacyName; this.iface = iface;
        }
        static final Identity WIFI = new Identity(TYPE_WIFI, TRANSPORT_WIFI, "WIFI", "eth0");
        static final Identity ETHERNET = new Identity(TYPE_ETHERNET, TRANSPORT_ETHERNET, "ETHERNET", "eth0");
    }
     
    private static final int[] CAPABILITIES = { 12, 13, 14, 15, 11 };

    private final NetworkInfo info;

    private WifiNet(Looper looper, Context ctx, NetworkInfo ni, NetworkCapabilities nc, LinkProperties lp, Identity id) {
        super(looper, ctx, id == Identity.WIFI ? "AEmuWifi" : "AEmuNet", ni, nc, lp, 60);
        info = ni;
    }

    @Override
    protected void unwanted() {
        Refl.log("the system no longer wants the Wi-Fi network");
    }

     
    @Override
    public void disconnect() {
        try {
            setDetailedState(info, NetworkInfo.DetailedState.DISCONNECTED);
            sendNetworkInfo(info);
        } catch (Throwable t) {
            Refl.log("disconnect: " + t);
        }
    }

     
    private static final class Ctx extends ContextWrapper {
        Object cm;

        Ctx() {
            super(null);
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "android"; }
        @Override public Object getSystemService(String name) { return "connectivity".equals(name) ? cm : null; }
         
          public String getSystemServiceName(Class<?> c) { return "android.net.ConnectivityManager".equals(c.getName()) ? "connectivity" : null; }
    }

     
    private static Ctx contextFor(android.os.IBinder binder) throws Exception {
        Class<?> iface = Class.forName("android.net.IConnectivityManager");
        Object service = Class.forName("android.net.IConnectivityManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Ctx ctx = new Ctx();
        ctx.cm = Refl.create("android.net.ConnectivityManager", new Class<?>[] { Context.class, iface }, ctx, service);
        if (ctx.cm == null) ctx.cm = Refl.create("android.net.ConnectivityManager", new Class<?>[] { iface }, service);
        if (ctx.cm == null) throw new IllegalStateException("no ConnectivityManager");
        return ctx;
    }

    private static InetAddress addr(String s) throws Exception {
        return InetAddress.getByName(s);
    }

     
 
 
 
 
    static WifiLink register(Looper looper, android.os.IBinder connectivity, WifiConfig cfg, Identity id, Object wifiInfo) throws Exception {
        Context ctx = contextFor(connectivity);

        NetworkInfo ni = (NetworkInfo) Refl.create("android.net.NetworkInfo",
                new Class<?>[] { int.class, int.class, String.class, String.class },
                Integer.valueOf(id.type), Integer.valueOf(0), id.legacyName, "");
        if (ni == null) throw new IllegalStateException("no NetworkInfo");
        setDetailedState(ni, NetworkInfo.DetailedState.CONNECTED);
        Refl.call(ni, "setIsAvailable", Boolean.TRUE);

        NetworkCapabilities nc = (NetworkCapabilities) Refl.newNoArg("android.net.NetworkCapabilities");
        Refl.call(nc, "addTransportType", Integer.valueOf(id.transport));
        for (int c : CAPABILITIES) Refl.call(nc, "addCapability", Integer.valueOf(c));
        Refl.call(nc, "setLinkUpstreamBandwidthKbps", Integer.valueOf(100000));
        Refl.call(nc, "setLinkDownstreamBandwidthKbps", Integer.valueOf(100000));
        if (id == Identity.WIFI && wifiInfo != null) Refl.call(nc, "setTransportInfo", wifiInfo);  

        LinkProperties lp = (LinkProperties) Refl.newNoArg("android.net.LinkProperties");
        Refl.call(lp, "setInterfaceName", id.iface);
        Object la = Refl.create("android.net.LinkAddress", new Class<?>[] { InetAddress.class, int.class },
                addr(cfg.ip), Integer.valueOf(cfg.prefix));
        Refl.call(lp, "addLinkAddress", la);
        Object route = Refl.create("android.net.RouteInfo", new Class<?>[] { InetAddress.class }, addr(cfg.gateway));
        Refl.call(lp, "addRoute", route);
        Refl.call(lp, "addDnsServer", addr(cfg.dns1));
        Refl.call(lp, "addDnsServer", addr(cfg.dns2));

        WifiLink n;
        try {
            n = new WifiNet(looper, ctx, ni, nc, lp, id);
        } catch (NoSuchMethodError e) {
             
            n = WifiNet2.start(ctx, looper, nc, lp, id);
        }
        Refl.log((id == Identity.WIFI ? "Wi-Fi" : "Ethernet (fallback)") + " network registered with ConnectivityService");
        return n;
    }
}
