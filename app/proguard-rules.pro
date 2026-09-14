# Shizuku starts the user service in a separate process and finds it by class
# name, so R8 has no call site to trace and strips it — the app then builds,
# installs, and fails at the moment you press Start. Nothing but an explicit
# keep can prevent that.
-keep class com.thoraim.app.AimService { *; }

# The AIDL stub and proxy are likewise only ever reached across a Binder.
-keep class com.thoraim.app.IAimService { *; }
-keep class com.thoraim.app.IAimService$* { *; }

# Shizuku's provider is named in the manifest and instantiated by the system.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Settings crosses the process boundary as text and is rebuilt by name on the
# far side; its fields are read reflectively by neither, but keeping the class
# whole costs nothing and makes a stack trace from the service readable.
-keep class com.thoraim.app.Settings { *; }
