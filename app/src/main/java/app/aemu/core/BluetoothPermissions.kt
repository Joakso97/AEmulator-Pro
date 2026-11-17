package app.aemu.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build


object BluetoothPermissions {
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= 31) listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN")
        else listOf("android.permission.ACCESS_FINE_LOCATION")   

    fun missing(ctx: Context, sdk: Int = Build.VERSION.SDK_INT): List<String> =
        required(sdk).filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
