import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Google Maps key: -PGOOGLE_MAPS_API_KEY=... (CI) oppure GOOGLE_MAPS_API_KEY in local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapsApiKey: String = providers.gradleProperty("GOOGLE_MAPS_API_KEY").orNull
    ?: localProperties.getProperty("GOOGLE_MAPS_API_KEY")
    ?: ""

// Firma release: variabili d'ambiente (CI) oppure local.properties. Se assenti, la build
// release resta possibile ma firmata con la chiave debug (non distribuibile).
fun releaseSetting(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProperties.getProperty(name)
val releaseKeystore = releaseSetting("RELEASE_KEYSTORE_PATH")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// Versione: la CI la ricava dal tag (v1.2.3) e dal numero di run.
val appVersionName = providers.gradleProperty("VERSION_NAME").orNull ?: "0.2.0"
val appVersionCode = providers.gradleProperty("VERSION_CODE").orNull?.toIntOrNull() ?: 2

android {
    namespace = "it.objectfinder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.objectfinder.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = mapsApiKey
        buildConfigField("boolean", "HAS_GOOGLE_MAPS_KEY", mapsApiKey.isNotBlank().toString())
    }

    signingConfigs {
        // Chiave debug fissa e versionata: tutte le build debug (CI e locali) hanno la stessa firma,
        // quindi un APK nuovo si installa sopra il precedente. Non è un segreto: le release usano
        // una chiave separata, fuori dal repo.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseSetting("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = releaseSetting("RELEASE_KEY_ALIAS")
                keyPassword = releaseSetting("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 disattivato finché non ci sono regole verificate per Ktor/serialization/Room.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Supabase: Auth (GoTrue) + PostgREST via Ktor.
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Foto: rendering immagini locali e correzione orientamento EXIF.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Etichette QR: generazione (ZXing core), scansione (Google code scanner, senza permesso
    // fotocamera: l'interfaccia è fornita da Play services), stampa.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.print:print:1.0.0")

    // Google Maps Compose provider
    implementation("com.google.maps.android:maps-compose:6.4.1")
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // OpenStreetMap MVP provider (OSMDroid); production tile policy must be configured.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
