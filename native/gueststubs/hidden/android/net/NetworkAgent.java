package android.net;

import android.content.Context;
import android.os.Looper;

 
public abstract class NetworkAgent extends android.os.Handler {
    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni, NetworkCapabilities nc,
                        LinkProperties lp, int score) { super(looper); }
     
    public NetworkAgent(Context context, Looper looper, String logTag, NetworkCapabilities nc, LinkProperties lp,
                        int score, NetworkAgentConfig config, NetworkProvider provider) { super(looper); }
    public Network register() { return null; }
    public void markConnected() {}
    public void sendNetworkInfo(NetworkInfo networkInfo) {}
    public void sendNetworkCapabilities(NetworkCapabilities networkCapabilities) {}
    public void sendLinkProperties(LinkProperties linkProperties) {}
    public void sendNetworkScore(int score) {}
    protected abstract void unwanted();
}
