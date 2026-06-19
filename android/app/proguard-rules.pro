# React Native and Hermes ship their own consumer ProGuard rules via the
# com.facebook.react:react-android artifact, so most framework keeps are applied
# automatically. The rules below cover this app's own reflection surface.

# This app's native bridge: React Native resolves modules, packages and
# @ReactMethod-annotated methods reflectively by name, so they must survive R8.
-keep class com.odininputmirror.InputMirrorModule { *; }
-keep class com.odininputmirror.InputMirrorPackage { *; }
-keepclassmembers class com.odininputmirror.** {
    @com.facebook.react.bridge.ReactMethod <methods>;
}

# Services / receivers / activities are instantiated by the Android framework by name.
-keep class com.odininputmirror.InputMirrorSupervisorService { *; }
-keep class com.odininputmirror.InputMirrorBootReceiver { *; }
-keep class com.odininputmirror.MainActivity { *; }
-keep class com.odininputmirror.MainApplication { *; }
