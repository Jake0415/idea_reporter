package app.ideacollector.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.io.IOException
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val GITHUB_TOKEN = "GITHUB_TOKEN"

internal class SecretStorage(file: File) {
    @Volatile var registering = false

    val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            if (!registering) throw error
            if (file.exists() && !file.delete()) throw IOException("Could not delete corrupted secret storage")
            emptyPreferences()
        }
    ) { file }
}
private val keyAccess = Mutex()

enum class SecretStatus { MISSING, AVAILABLE, NEEDS_REGISTRATION }

class KeystoreSecretProvider internal constructor(
    private val secretStorage: SecretStorage,
    private val onRegistered: suspend (String) -> Unit = {}
) {
    constructor(context: Context, onRegistered: suspend (String) -> Unit = {}) :
        this(sharedStorage(context.applicationContext), onRegistered)

    private val storage = secretStorage.dataStore

    suspend fun set(name: String, value: String): Boolean {
        if (value.isBlank()) return false
        val registered = keyAccess.withLock {
            val resetKey = readLocked(name).first == SecretStatus.NEEDS_REGISTRATION
            secretStorage.registering = true
            try {
                write(name, value, resetKey)
            } finally {
                secretStorage.registering = false
            }
        }
        if (registered) onRegistered(name)
        return registered
    }

    private suspend fun write(name: String, value: String, resetKey: Boolean): Boolean = try {
        if (resetKey) store().deleteEntry(alias(name))
        val key = key(name) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(alias(name), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        storage.edit { it[stringPreferencesKey(name)] = encoder.encodeToString(cipher.iv) + "." + encoder.encodeToString(encrypted) }
        true
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: ProviderException) {
        false
    } catch (_: IOException) {
        false
    }

    suspend fun clear(name: String) = keyAccess.withLock {
        storage.edit { it.remove(stringPreferencesKey(name)) }
        store().deleteEntry(alias(name))
    }

    suspend fun has(name: String): Boolean = status(name) == SecretStatus.AVAILABLE
    suspend fun last4(name: String): String? = read(name).second?.takeLast(4)
    suspend fun status(name: String): SecretStatus = read(name).first
    internal suspend fun get(name: String): String? = read(name).second

    private suspend fun read(name: String): Pair<SecretStatus, String?> = keyAccess.withLock {
        readLocked(name)
    }

    private suspend fun readLocked(name: String): Pair<SecretStatus, String?> {
        val value = try {
            val blob = storage.data.first()[stringPreferencesKey(name)] ?: return SecretStatus.MISSING to null
            val fields = blob.split('.')
            require(fields.size == 2)
            val decoder = Base64.getDecoder()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(name) ?: throw GeneralSecurityException(),
                GCMParameterSpec(128, decoder.decode(fields[0])))
            cipher.doFinal(decoder.decode(fields[1])).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ProviderException) {
            null
        } catch (_: IOException) {
            null
        }
        return if (value == null) SecretStatus.NEEDS_REGISTRATION to null else SecretStatus.AVAILABLE to value
    }

    private fun key(name: String) = store().getKey(alias(name), null) as? SecretKey
    private fun store() = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private fun alias(name: String) = "app.ideacollector.secret.$name"

    private companion object {
        private var shared: SecretStorage? = null

        @Synchronized
        fun sharedStorage(context: Context): SecretStorage =
            shared ?: SecretStorage(context.preferencesDataStoreFile("secrets")).also { shared = it }

        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
