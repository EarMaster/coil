# JeroMQ reaches for a few optional pieces reflectively and ships classes that
# reference JDK-only APIs; keeping the package intact is simpler than chasing
# individual warnings, and it is small.
-keep class org.zeromq.** { *; }
-keep class zmq.** { *; }
-dontwarn org.zeromq.**
-dontwarn zmq.**

# kotlinx.serialization keeps generated serializers on companion objects.
-keepclassmembers class app.coilforphoniebox.** {
    *** Companion;
}
-keepclasseswithmembers class app.coilforphoniebox.** {
    kotlinx.serialization.KSerializer serializer(...);
}
