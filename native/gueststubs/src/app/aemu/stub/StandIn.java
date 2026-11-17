package app.aemu.stub;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.lang.reflect.Method;

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
public final class StandIn {
     
    private static final int KEYSTORE_NO_ERROR = 1;

     
    private static int keystoreGetState() {
        try {
            java.lang.reflect.Field f = Class.forName("android.security.keystore.IKeystoreService$Stub").getDeclaredField("TRANSACTION_getState");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return IBinder.FIRST_CALL_TRANSACTION;
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method check = sm.getMethod("checkService", String.class);
        Method add = sm.getMethod("addService", String.class, IBinder.class);
        for (final String name : args) {
            try {
                if (check.invoke(null, name) != null) continue;
                final int getState = "android.security.keystore".equals(name) ? keystoreGetState() : -1;
                add.invoke(null, name, new Binder() {
                    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(name); return true; }
                        if (reply != null) {
                            reply.writeNoException();
                            if (code == getState) reply.writeInt(KEYSTORE_NO_ERROR);
                        }
                        return true;
                    }
                });
                System.out.println("aemu-standin: " + name);
            } catch (Throwable t) { t.printStackTrace(System.out); }
        }
        System.out.flush();
        Looper.loop();
    }
}
