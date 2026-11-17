package app.aemu.stub;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

 
 
 
 
 
 
 
 
 
 
 
 
public class BtStub extends Binder {
    private final String descriptor;

    BtStub(String name) {
         
        descriptor = "bluetooth".equals(name) ? "android.bluetooth.IBluetooth" : "android.bluetooth.IBluetoothManager";
    }

    private static final int FIRST_CALL = 1, LAST_CALL = 0x00ffffff;

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code < FIRST_CALL || code > LAST_CALL) {
            try { return super.onTransact(code, data, reply, flags); } catch (Exception e) { return false; }
        }
        if (reply != null) {
            reply.writeNoException();
             
            reply.writeInt(0);
            reply.writeInt(0);
        }
        return true;
    }

    @Override
    public String getInterfaceDescriptor() { return descriptor; }

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "bluetooth_manager";
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method check = sm.getMethod("checkService", String.class);
        if (check.invoke(null, name) != null) {
            System.out.println("aemu-bt: служба " + name + " уже есть — заглушка не нужна");
            return;
        }
        Method add = sm.getMethod("addService", String.class, IBinder.class);
        add.invoke(null, name, new BtStub(name));
        System.out.println("aemu-bt: служба " + name + " зарегистрирована");
         
        Object lock = new Object();
        synchronized (lock) { while (true) lock.wait(); }
    }
}
