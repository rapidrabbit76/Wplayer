# Navigation 3 keys are restored through kotlinx.serialization.
-keepclassmembers @kotlinx.serialization.Serializable class dev.wplayer.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
