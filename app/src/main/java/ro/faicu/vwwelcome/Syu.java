package ro.faicu.vwwelcome;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Comunicarea cu MainServer-ul FYT (com.syu.ms) prin AIDL-ul com.syu.ipc, scris direct cu
 * Parcel. Protocolul e documentat public de FYTCanbusMonitor si chrisuthe/7870-Projects:
 * IRemoteToolkit.getRemoteModule = 1; IRemoteModule.register = 3 (callback, cod, 1);
 * IModuleCallback.update = 1 (cod, int[], float[], String[]). Nu cere permisiuni.
 */
final class Syu {
    static final int MODULE_MAIN = 0;
    static final int MODULE_CANBUS = 7;

    private static final String TOOLKIT = "com.syu.ipc.IRemoteToolkit";
    private static final String MODULE = "com.syu.ipc.IRemoteModule";
    private static final String CALLBACK = "com.syu.ipc.IModuleCallback";

    private Syu() {}

    static Intent toolkitIntent() {
        return new Intent("com.syu.ms.toolkit")
                .setComponent(new ComponentName("com.syu.ms", "app.ToolkitService"));
    }

    /** Modulul cerut (ex. CANBUS), sau null daca MainServer nu il are. */
    static IBinder module(IBinder toolkit, int module) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOOLKIT);
            data.writeInt(module);
            toolkit.transact(1, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Abonare la un cod; valorile vin in Callback.onUpdate. */
    static void register(IBinder module, Callback cb, int code) throws RemoteException {
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(MODULE);
            d.writeStrongBinder(cb);
            d.writeInt(code);
            d.writeInt(1);
            module.transact(3, d, null, IBinder.FLAG_ONEWAY);
        } finally {
            d.recycle();
        }
    }

    /** Callback-ul apelat de MainServer; ruleaza pe firele binder. */
    abstract static class Callback extends Binder {
        Callback() {
            attachInterface(null, CALLBACK);
        }

        abstract void onUpdate(int code, int[] ints, float[] flts, String[] strs);

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CALLBACK);
                return true;
            }
            if (code != 1) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CALLBACK);
            int update = data.readInt();
            onUpdate(update, data.createIntArray(), data.createFloatArray(), data.createStringArray());
            return true;
        }
    }
}
