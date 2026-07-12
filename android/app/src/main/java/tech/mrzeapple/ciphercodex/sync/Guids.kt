package tech.mrzeapple.ciphercodex.sync

import java.util.UUID

object Guids {
    /** Cross-device row identity: UUIDv4 hex, dashes stripped (contract format). */
    fun new(): String = UUID.randomUUID().toString().replace("-", "")
}
