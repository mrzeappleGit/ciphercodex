package tech.mrzeapple.ciphercodex.sync

import tech.mrzeapple.ciphercodex.data.db.BookEntity

/** Outcome of a bulk push of every dirty progress row. */
data class SyncSummary(val pushed: Int, val failed: Int, val skipped: Int) {
    val attempted: Int get() = pushed + failed + skipped
}

/** Sync policy: pull when a book is opened, push when reading pauses/stops.
 *  Implemented by [KosyncSyncManager]. */
interface SyncManager {

    /** Pushes every dirty progress row (manual "Sync now" + background worker),
     *  stamping the last-sync time. No-op when sync is not configured. */
    suspend fun syncAllDirty(): SyncSummary
    /** Fetch the remote position for [book]. Returns RemoteNewer only when the
     *  remote record is worth offering a jump to (different device AND further
     *  than / different from the local position). */
    suspend fun pullOnOpen(book: BookEntity): PullResult

    /** Push the local progress row for [book] if dirty; stamps syncedAt. */
    suspend fun pushProgress(book: BookEntity): PushResult

    /** Used by the settings screen: authorize (or register when [register])
     *  against the configured server with the given credentials. */
    suspend fun testConnection(account: KosyncAccount, register: Boolean): KosyncResult<Unit>
}
