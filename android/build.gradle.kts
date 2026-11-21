// Toolchain: AGP 9 integra il supporto Kotlin (niente plugin org.jetbrains.kotlin.android).
// Le versioni di Kotlin, plugin Compose/serialization e KSP vanno aggiornate insieme
// (gruppo "kotlin-toolchain" di Dependabot).
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
    // Screenshot delle @Preview su JVM (Robolectric), senza emulatore.
    id("io.github.takahirom.roborazzi") version "1.79.0" apply false
}
