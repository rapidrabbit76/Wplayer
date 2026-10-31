# UniFFI bindings call into JNA, which reflects over these types.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class dev.wplayer.core.ffi.** { *; }
-dontwarn java.awt.**
# JNI entry points resolved by name from libwplayer_core.so.
-keepclasseswithmembernames class dev.wplayer.core.player.SurfaceBridge {
    native <methods>;
}
