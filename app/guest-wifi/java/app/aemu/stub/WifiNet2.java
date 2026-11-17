/* Modified for AEmulator Plus, 2026-11-01: Android 11 - 17. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.os.Looper;

 
 
 
 
 
final class WifiNet2 extends NetworkAgent implements WifiLink {
    private WifiNet2(Context c, Looper l, NetworkCapabilities nc, LinkProperties lp, NetworkAgentConfig cfg, WifiNet.Identity id) {
        super(c, l, id == WifiNet.Identity.WIFI ? "AEmuWifi" : "AEmuNet", nc, lp, 60, cfg, null);
    }

    @Override
    protected void unwanted() {
        Refl.log("the system no longer wants the network");
    }

    @Override
    public void disconnect() {
        try {
            unregister();
        } catch (Throwable t) {
            Refl.log("disconnect: " + t);
        }
    }

    static WifiNet2 start(Context ctx, Looper l, NetworkCapabilities nc, LinkProperties lp, WifiNet.Identity id) throws Exception {
         
        for (int cap : new int[] { 18  , 20  , 21  , 28   }) {
            Refl.call(nc, "addCapability", Integer.valueOf(cap));
        }
        Object b = Refl.newNoArg("android.net.NetworkAgentConfig$Builder");
        if (b == null) throw new IllegalStateException("no NetworkAgentConfig.Builder");
        Refl.call(b, "setLegacyType", Integer.valueOf(id.type));
        Refl.call(b, "setLegacyTypeName", id.legacyName);
        NetworkAgentConfig cfg = (NetworkAgentConfig) Refl.call(b, "build");
        if (cfg == null) throw new IllegalStateException("NetworkAgentConfig.Builder.build failed");
        WifiNet2 a = new WifiNet2(ctx, l, nc, lp, cfg, id);
        a.register();
        a.markConnected();
        return a;
    }
}
