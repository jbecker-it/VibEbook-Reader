# --- WebView JavaScript-Bridge ---
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- smbj / SMB-Stack ---
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**

# --- BouncyCastle ---
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- SLF4J ---
-dontwarn org.slf4j.**

# --- ASN.1 / Buffer-Bibliotheken von smbj ---
-dontwarn com.lmax.disruptor.**
-dontwarn javax.annotation.**

# Room und Hilt bringen eigene Consumer-Regeln mit.
