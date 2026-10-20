import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Gemini API key is never committed. Resolution order:
//   1. GEMINI_API_KEY environment variable
//   2. GEMINI_API_KEY=... line in local.properties at the repo root (gitignored, for PC builds)
//   3. empty string — the app still builds, but shows "API key नहीं मिली" and does nothing
val geminiApiKey: String = run {
    System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return@run it }
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val props = Properties().apply { lp.inputStream().use { load(it) } }
        return@run props.getProperty("GEMINI_API_KEY").orEmpty()
    }
    ""
}

// troubleshoot_app's last rung calls a helper. Both settings use the API key's resolution order:
// env var, then local.properties, then empty.
//   HELPER_PHONE_NUMBER  — always dialled directly when set.
//   HELPER_CONTACT_NAME  — spoken label; looked up in contacts (exact name only) when no number.
// Both empty → the last rung just says "call someone at home" instead of offering a call.
fun envOrLocalProperty(key: String): String {
    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val props = Properties().apply { lp.inputStream().use { load(it) } }
        return props.getProperty(key).orEmpty().trim()
    }
    return ""
}
val helperContactName: String = envOrLocalProperty("HELPER_CONTACT_NAME").replace("\"", "")
// Keep only dialable characters so a stray space or dash in the setting can't break the tel: URI.
val helperPhoneNumber: String = envOrLocalProperty("HELPER_PHONE_NUMBER").filter { it.isDigit() || it == '+' }

android {
    namespace = "com.family.motherassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.family.motherassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "0.7.4-troubleshoot-number-first"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "HELPER_CONTACT_NAME", "\"$helperContactName\"")
        buildConfigField("String", "HELPER_PHONE_NUMBER", "\"$helperPhoneNumber\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
