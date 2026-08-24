# Keep names. R8 still tree-shakes (which is where the size and start-up win comes from), but
# nothing gets renamed - so a stack trace from a sideloaded build is still readable, and any
# component resolved by name from the manifest or by the Data Layer cannot be broken by
# obfuscation. Given this ships straight to a watch with no crash reporting, that trade is
# worth the few hundred KB.
-dontobfuscate

# Manifest components. AGP keeps these already; being explicit means a typo in the manifest
# fails loudly at build time instead of at 2am on the wrist.
-keep class com.wristcord.mobile.discord.DiscordNotificationListener { *; }
-keep class com.wristcord.mobile.discord.DiscordAccessibilityService { *; }
-keep class com.wristcord.mobile.link.PhoneWearableListenerService { *; }
-keep class com.wristcord.mobile.ui.MainActivity { *; }
-keep class com.wristcord.shared.InstallResultReceiver { *; }

# Wearable Data Layer reflects over listener services when Play services binds them.
-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }
