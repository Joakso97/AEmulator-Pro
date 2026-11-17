package android.net;

 
 
 
 
public abstract class NetworkAgent {
     
    public NetworkAgent(android.os.Looper looper, android.content.Context context, String logTag, NetworkInfo ni,
                        NetworkCapabilities nc, LinkProperties lp, int score) { }
     
    public NetworkAgent(android.content.Context context, android.os.Looper looper, String logTag, NetworkCapabilities nc,
                        LinkProperties lp, int score, NetworkAgentConfig config, NetworkProvider provider) { }
    protected abstract void unwanted();
    public void sendNetworkInfo(NetworkInfo networkInfo) { }
    public Network register() { return null; }
    public void markConnected() { }
    public void unregister() { }
}
