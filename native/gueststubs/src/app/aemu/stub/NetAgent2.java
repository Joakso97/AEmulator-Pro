package app.aemu.stub;

import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.os.Looper;

 
 
 
 
public class NetAgent2 extends NetworkAgent {
    NetAgent2(Context c, Looper l, NetworkCapabilities nc, LinkProperties lp, NetworkAgentConfig cfg) {
        super(c, l, "AEmuNet", nc, lp, 60, cfg, null);
    }

    @Override
    protected void unwanted() { }

    static void start(Context ctx, Looper l, NetworkCapabilities nc, LinkProperties lp) throws Exception {
         
        for (int cap : new int[]{18  , 20  , 21  , 28  }) {
            try { NetStub.call(nc, "addCapability", new Class<?>[]{int.class}, cap); } catch (Throwable t) {   }
        }
        Object b = NetStub.newObj("android.net.NetworkAgentConfig$Builder", new Class<?>[0]);
        NetStub.call(b, "setLegacyType", new Class<?>[]{int.class}, 9  );
        NetStub.call(b, "setLegacyTypeName", new Class<?>[]{String.class}, "ETHERNET");
        NetworkAgentConfig cfg = (NetworkAgentConfig) b.getClass().getMethod("build").invoke(b);
        NetAgent2 a = new NetAgent2(ctx, l, nc, lp, cfg);
        a.register();
        a.markConnected();
    }
}
