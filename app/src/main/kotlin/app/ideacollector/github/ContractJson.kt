package app.ideacollector.github

import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal val contractJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }
