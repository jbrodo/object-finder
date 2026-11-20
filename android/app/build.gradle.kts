import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
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
val appVersionName = providers.gradleProperty("VERSION_NAME").orNull ?: "1.8.1-dev"
val appVersionCode = providers.gradleProperty("VERSION_CODE").orNull?.toIntOrNull() ?: 2

android {
    namespace = "it.objectfinder.app"
    // Compose 1.13 richiede le API Android 37.1 (versione minore della piattaforma).
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "it.objectfinder.app"
        minSdk = 26
        externalNativeBuild {
            cmake {
                arguments += "-DCACTUS_LIB_ROOT=${layout.buildDirectory.dir("generated/cactus").get().asFile.absolutePath}"
            }
        }
        targetSdk = 37
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
            // Librerie native (MediaPipe) solo per i telefoni: x86 serve unicamente agli emulatori.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        debug {
            // Telefono (arm64) ed emulatore (x86_64): APK di CI installabile su entrambi.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    // APK scaricato fuori dallo Store: librerie native compresse (download molto più piccolo).
    packaging { jniLibs { useLegacyPackaging = true } }

    // Ponte JNI verso il motore Cactus (Mirage): src/main/cpp. NDK r28 = stessa libc++ della release.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        // Digital Asset Links servono solo a condividere le password con un sito web dello stesso
        // dominio (Credential Manager); Object Finder non ne ha. Da riattivare se nascerà un sito.
        disable += "CredManMissingDal"
        // Release solo ARM (telefoni): x86_64 per i Chromebook costerebbe ~19 MB di librerie MediaPipe.
        disable += "ChromeOsAbiSupport"
    }
}

// Modello per il riconoscimento visivo (MediaPipe Image Embedder, MobileNetV3 large, Apache-2.0):
// scaricato dalla build con versione e SHA-256 fissati invece di tenere 11 MB di binario nel repository.
abstract class DownloadEmbedderModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val target = outputDir.file("image_embedder.tflite").get().asFile
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (target.exists() && digest(target.readBytes()) == sha256.get()) return
        val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
        check(digest(bytes) == sha256.get()) { "Checksum del modello non valido: ${url.get()}" }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}

val downloadEmbedderModel = tasks.register<DownloadEmbedderModel>("downloadEmbedderModel") {
    url.set("https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_large/float32/1/mobilenet_v3_large.tflite")
    sha256.set("11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d")
    outputDir.set(layout.buildDirectory.dir("generated/embedderModel"))
}

// Motore Cactus (Mirage) per Android: librerie statiche ARM della release 4.1.4, stessa revisione
// usata dal pacchetto ufficiale cactus-compute; versione e SHA-256 fissati.
abstract class DownloadCactusEngine : DefaultTask() {
    @get:Input abstract val files: MapProperty<String, String>
    @get:Input abstract val baseUrl: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        files.get().forEach { (abi, sha) ->
            val source = mapOf("arm64-v8a" to "android-arm64", "armeabi-v7a" to "android-armv7").getValue(abi)
            val target = outputDir.file("$abi/libcactus.a").get().asFile
            if (target.exists() && digest(target.readBytes()) == sha) return@forEach
            val bytes = URI("${baseUrl.get()}/$source/libcactus.a").toURL().openStream().use { it.readBytes() }
            check(digest(bytes) == sha) { "Checksum di libcactus.a non valido per $abi" }
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }
}

val cactusEngineDir = layout.buildDirectory.dir("generated/cactus")
val downloadCactusEngine = tasks.register<DownloadCactusEngine>("downloadCactusEngine") {
    baseUrl.set("https://huggingface.co/Cactus-Compute/cactus/resolve/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4")
    files.put("arm64-v8a", "67dbd7b856a3e9d8b0fbb68f0c59592e7572cf859238ba7f3074f74b6fa9a5da")
    files.put("armeabi-v7a", "6f7daade8587c714a2f46fdccf90535660b78d22e3c4a89518d53ea857568afa")
    outputDir.set(cactusEngineDir)
}
// La configurazione CMake legge le librerie: devono esserci prima.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(downloadCactusEngine) }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadEmbedderModel, DownloadEmbedderModel::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // Supabase: Auth (GoTrue) + PostgREST via Ktor.
    implementation("io.ktor:ktor-client-core:3.6.1")
    implementation("io.ktor:ktor-client-okhttp:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Credential Manager: salvataggio/recupero password con il gestore password di sistema.
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")

    // Foto: rendering immagini locali e correzione orientamento EXIF.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Fotocamera in-app (anteprima, scatti in serie, flash).
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-compose:1.6.2")

    // Riconoscimento visivo on-device (impronte delle foto, confronto per somiglianza).
    implementation("com.google.mediapipe:tasks-vision:1.2.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // Etichette QR: generazione (ZXing core), scansione (Google code scanner, senza permesso
    // fotocamera: l'interfaccia è fornita da Play services), stampa.
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.print:print:1.1.0")

    // Google Maps Compose provider
    implementation("com.google.maps.android:maps-compose:9.0.1")
    implementation("com.google.android.gms:play-services-maps:20.1.0")

    // OpenStreetMap MVP provider (OSMDroid); production tile policy must be configured.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
