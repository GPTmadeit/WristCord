# Shrink, do not rename: crash traces stay readable and nothing resolved by name can break.
-dontobfuscate

-keep class com.wristcord.wear.MainActivity { *; }
-keep class com.wristcord.wear.tile.WristCordTileService { *; }
-keep class com.wristcord.wear.tile.TileActionActivity { *; }
-keep class com.wristcord.wear.tile.TileActionReceiver { *; }
-keep class com.wristcord.shared.InstallResultReceiver { *; }
-keep class com.wristcord.wear.voice.VoiceService { *; }

# JNI: libwristcord_dave.so binds to these by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.wristcord.wear.voice.NativeDave { *; }
-keep class com.wristcord.wear.tile.WristCordComplicationService { *; }

# Complication data sources are bound by the watch face across a binder.
-keep class * extends androidx.wear.watchface.complications.datasource.ComplicationDataSourceService { *; }


# Tiles/ProtoLayout are resolved by the system renderer across a binder; keep the whole
# service surface and the protobuf-backed builders they hand over.
-keep class * extends androidx.wear.tiles.TileService { *; }
-keep class androidx.wear.protolayout.proto.** { *; }
-keep class androidx.wear.tiles.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Ambient support talks to an on-device shared library that is not on the compile classpath.
-dontwarn com.google.wear.**
