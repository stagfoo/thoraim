# The accessibility service is named in the manifest and instantiated by the
# system, so R8 has no call site to trace and will strip it. That failure is
# invisible in a debug build and only shows up as the service never connecting.
-keep class com.thoraim.app.AimAccessibilityService { *; }

# Reached only from the service, but kept whole so a stack trace out of a
# background thread is readable.
-keep class com.thoraim.app.GestureDriver { *; }
-keep class com.thoraim.app.Settings { *; }
