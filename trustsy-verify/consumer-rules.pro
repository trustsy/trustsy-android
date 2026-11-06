# JMRTD and Bouncy Castle are reached through reflection (JCA providers); the
# sealed transport uses Bouncy Castle's HPKE directly.
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# MediaPipe tasks-vision ships no consumer rules: its native graph calls back
# into Java over JNI and parses options as protobuf-lite.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.auto.value.**
