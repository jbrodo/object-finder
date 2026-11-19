package it.objectfinder.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifratura autenticata AES-GCM; formato: [len iv][iv][ciphertext+tag]. Pura JCE, testabile su JVM. */
object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array()
    }

    /** Lancia [GeneralSecurityException] se il dato è alterato o la chiave è diversa. */
    fun open(key: SecretKey, data: ByteArray): ByteArray {
        if (data.isEmpty()) throw GeneralSecurityException("Dato vuoto")
        val ivLength = data[0].toInt()
        if (ivLength !in 12..16 || data.size <= 1 + ivLength) throw GeneralSecurityException("Formato non valido")
        val iv = data.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(data, 1 + ivLength, data.size - 1 - ivLength)
    }
}

/**
 * Chiave AES-256 nell'Android Keystore: non lascia mai il dispositivo e non è estraibile.
 * Se viene persa (es. ripristino su un altro telefono) i dati cifrati non sono più leggibili:
 * per una sessione basta rifare l'accesso.
 */
class KeystoreKey(private val alias: String) {
    fun get(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
