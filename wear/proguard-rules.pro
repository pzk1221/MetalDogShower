-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# Provisioning DTO field names are part of the phone/watch wire contract.
-keepclassmembers class com.panzhikun.metaldogshower.wear.provisioning.** {
    <fields>;
}
