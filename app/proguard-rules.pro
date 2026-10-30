# Jakarta Mail / Java Activation rules
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.auth.callback.**

# Room Database
-keep class androidx.room.** { *; }

# Coil
-keep class io.coilkt.** { *; }
