# --- WebView JavaScript-Bridge ---
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Room und Hilt bringen eigene Consumer-Regeln mit.
