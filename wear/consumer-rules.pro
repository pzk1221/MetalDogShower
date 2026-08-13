# The Wear module is currently an application. Keep this file for a future
# extraction of its controller/provisioning API into a library without changing
# the security-sensitive wire names during that refactor.
-keepclassmembers class com.panzhikun.metaldogshower.wear.provisioning.** {
    <fields>;
}
