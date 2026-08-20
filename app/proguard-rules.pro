# ---------- NexusSSH ProGuard / R8 configuration ----------

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# ---- SSHJ ----
-keep class net.schmizz.sshj.** { *; }
-keep interface net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
# SSHJ resolves transport components (ciphers, MACs, KEX) reflectively via Factory.Named
-keep class * implements net.schmizz.sshj.common.Factory$Named { *; }

# ---- BouncyCastle ----
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# ---- EdDSA (net.i2p.crypto) ----
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# ---- SLF4J ----
-dontwarn org.slf4j.**
-keep class uk.uuid.slf4j.android.** { *; }

# ---- Java/JMX/AWT references pulled in by desktop-oriented libraries ----
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**

# ---- Room ----
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }

# ---- kotlinx.serialization ----
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Hilt ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ---- App model classes used in backup import/export ----
-keep class com.nikro.nexusssh.data.backup.** { *; }
-keep class com.nikro.nexusssh.domain.model.** { *; }

# Keep enum values (used by Room converters and serialization)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
