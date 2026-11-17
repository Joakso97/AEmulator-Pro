
package app.aemu.core


internal object NativeAbiLists {
    data class Lists(val abilist: String, val abilist64: String, val abilist32: String)

    
    fun forArch(arch: GuestArch, has32: Boolean): Lists = when (arch) {
        GuestArch.ARM ->
            if (has32) Lists("arm64-v8a,armeabi-v7a,armeabi", "arm64-v8a", "armeabi-v7a,armeabi")
            else Lists("arm64-v8a", "arm64-v8a", "")
        GuestArch.X86_64 ->
            if (has32) Lists("x86_64,x86", "x86_64", "x86")
            else Lists("x86_64", "x86_64", "")
        
        GuestArch.X86 -> Lists("x86", "", "x86")
    }

    
    fun pure32Fallback(arch: GuestArch): String = if (arch == GuestArch.X86) "x86" else "armeabi-v7a,armeabi"

    
    fun pure32FromAbi(arch: GuestArch, abi: String): String =
        if (arch == GuestArch.ARM && abi != "armeabi") "$abi,armeabi" else if (arch == GuestArch.ARM) "armeabi-v7a,armeabi" else abi
}
