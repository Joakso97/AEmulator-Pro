package app.aemu.stub;

import android.os.Looper;

 
 
 
 
 
public final class BtService {
    private BtService() { }

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "bluetooth_manager";
        Looper.prepare();
        BtCore core = new BtCore();
        BtUtil.primeDeviceService(core);
        BtUtil.addService(name, new BtManager(core));
        BtLog.i("service " + name + " registered (host bridge client, API 19)");
        core.start();
        Looper.loop();
    }
}
