/* Modified for AEmulator Plus, 2026-11-01: Android 8 - 17. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

 
 
 
 
 
 
 
 
 
 
 
public final class WifiStub {
    private static WifiLink net;

    private WifiStub() { }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
        }
    }


    private static IBinder getService(String name) {
        try {
            return (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"), "getService", name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static IBinder checkService(String name) {
        try {
            return (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"), "checkService", name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOurs(IBinder b, WifiService svc) {
        if (b == svc) return true;
        if (b == null) return false;
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            return b.transact(WifiService.PROBE, data, reply, 0) && reply.readInt() == WifiService.PROBE_ANSWER;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void addService(String name, IBinder b) {
        Refl.callStatic(Refl.cls("android.os.ServiceManager"), "addService", name, b);
    }

    private static void run() throws Exception {
        Looper.prepare();
        final Looper looper = Looper.myLooper();
        final WifiConfig cfg = WifiConfig.load();
        if (cfg == null) {
            Refl.log("no settings file, nothing to do");
            return;
        }
        WifiService made = null;
        try {
            made = new WifiService(cfg, looper);
        } catch (Throwable e) {
             
            Refl.log("no IWifiManager service: " + e);
            e.printStackTrace(System.out);
            System.out.flush();
        }
        final WifiService svc = made;
        final Handler main = new Handler(looper);
        final boolean agent = Build.VERSION.SDK_INT >= 21;
        final LegacyWifi legacy = svc != null && Build.VERSION.SDK_INT < 21 ? new LegacyWifi(cfg, svc) : null;
        if (svc != null) svc.setLegacy(legacy);

        if (agent && svc != null) {
            svc.setListener(new WifiService.Listener() {
                @Override public void onConnectionChanged(final boolean connected) {
                    main.post(new Runnable() { @Override public void run() { applyConnection(looper, svc, cfg, connected); } });
                }
            });
        }

        Thread t = new Thread("aemu-wifi-register") {
            @Override public void run() {
                try {
                    watch(svc, main, looper, cfg, agent, legacy);
                } catch (Throwable e) {
                    e.printStackTrace(System.out);
                    System.out.flush();
                }
            }
        };
        t.setDaemon(true);
        t.start();
        Looper.loop();
    }

     
    private static volatile IBinder connectivity;

    private static boolean bootCompleted() {
        try {
            Object v = Refl.callStatic(Refl.cls("android.os.SystemProperties"), "get", "sys.boot_completed");
            return "1".equals(v);
        } catch (Throwable t) {
            return true;
        }
    }

     
    private static void watch(final WifiService svc, Handler main, final Looper looper, final WifiConfig cfg, boolean agent, LegacyWifi legacy) throws Exception {
         
        final boolean modern = Build.VERSION.SDK_INT >= 26;
        long agentDeadline = System.currentTimeMillis() + (modern ? 900000L : 180000L);
        boolean agentDone = !agent;
        int takeovers = 0;
        int refused = 0;
         
         
         
         
         
        final boolean leaveName = Build.VERSION.SDK_INT < 14;
        if (leaveName) Refl.log("Gingerbread: \"wifi\" is left to the framework's own service (its servicemanager refuses a second one)");
        for (;;) {
            if (svc != null && !leaveName && refused < 5 && !isOurs(checkService("wifi"), svc)) {
                try {
                    addService("wifi", svc);
                    if (takeovers++ == 0) Refl.log("service \"wifi\" registered");
                    else if (takeovers <= 3) Refl.log("service \"wifi\" taken back from the framework's own");
                } catch (Throwable e) {
                     
                    if (++refused <= 5) Refl.log("service \"wifi\" refused: " + e);
                }
            }
            if (!agentDone) {
                IBinder cs = connectivity != null ? connectivity : getService("connectivity");
                if (cs != null && (!modern || bootCompleted())) {
                    connectivity = cs;
                    agentDone = true;
                    main.post(new Runnable() { @Override public void run() { applyConnection(looper, svc, cfg, svc == null || svc.isConnected()); } });
                } else if (System.currentTimeMillis() > agentDeadline) {
                    Refl.log("ConnectivityService did not appear, no Wi-Fi network registered");
                    agentDone = true;
                }
            }
            if (legacy != null) legacy.tick(checkService("connectivity") != null);
            Thread.sleep(agentDone ? 1000L : 500L);
        }
    }

     
    private static void applyConnection(Looper looper, WifiService svc, WifiConfig cfg, boolean connected) {
        if (connectivity == null) return;  
        try {
            if (connected && net == null) {
                Object info = svc != null ? svc.wifiInfo() : null;
                try {
                    net = WifiNet.register(looper, connectivity, cfg, WifiNet.Identity.WIFI, info);
                } catch (Throwable t) {
                    if (Build.VERSION.SDK_INT < 26) throw t;
                     
                    Refl.log("registering as Wi-Fi failed (" + t + "), registering the Ethernet placeholder instead");
                    t.printStackTrace(System.out);
                    net = WifiNet.register(looper, connectivity, cfg, WifiNet.Identity.ETHERNET, null);
                }
            } else if (!connected && net != null) {
                net.disconnect();
                net = null;
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
        }
    }
}
