# See the note in mobile/proguard-rules.pro - shrink, do not rename.
-dontobfuscate

-keep class com.wristcord.wear.MainActivity { *; }
-keep class com.wristcord.wear.tile.WristCordTileService { *; }
-keep class com.wristcord.wear.tile.TileActionActivity { *; }
-keep class com.wristcord.wear.tile.TileActionReceiver { *; }
-keep class com.wristcord.wear.link.WearListenerService { *; }
-keep class com.wristcord.shared.InstallResultReceiver { *; }

-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }

# Tiles/ProtoLayout are resolved by the system renderer across a binder; keep the whole
# service surface and the protobuf-backed builders they hand over.
-keep class * extends androidx.wear.tiles.TileService { *; }
-keep class androidx.wear.protolayout.proto.** { *; }
-keep class androidx.wear.tiles.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Ambient support talks to an on-device shared library that is not on the compile classpath.
-dontwarn com.google.wear.**
