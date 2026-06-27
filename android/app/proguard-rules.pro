# Services / receivers / activities / application are instantiated by the Android
# framework by name, so they must survive R8.
-keep class com.odininputmirror.InputMirrorSupervisorService { *; }
-keep class com.odininputmirror.InputMirrorBootReceiver { *; }
-keep class com.odininputmirror.MainActivity { *; }
-keep class com.odininputmirror.MainApplication { *; }
