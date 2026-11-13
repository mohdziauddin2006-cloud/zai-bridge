# The guest runtime resolves native methods by name through the bridge, so the
# Java-facing core class and everything reached reflectively must survive.
-keep class com.ziauddin.zaibridge.core.UnicornBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
