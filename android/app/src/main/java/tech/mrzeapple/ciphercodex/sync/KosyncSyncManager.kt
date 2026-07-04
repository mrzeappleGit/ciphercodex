package tech.mrzeapple.ciphercodex.sync

import tech.mrzeapple.ciphercodex.data.db.BookDao
import tech.mrzeapple.ciphercodex.data.db.BookEntity
import tech.mrzeapple.ciphercodex.data.prefs.Settings
import tech.mrzeapple.ciphercodex.data.prefs.UserPrefs
import kotlin.math.abs

class KosyncSyncManager(
    private val prefs: UserPrefs,
    private val dao: BookDao,
    private val api: KosyncApi,
) : SyncManager {

    override suspend fun pullOnOpen(book: BookEntity): PullResult {
        val settings = prefs.current()
        if (!settings.syncUsable()) return PullResult.Disabled
        val record = when (val r = api.getProgress(settings.account(), book.digest)) {
            is KosyncResult.Err -> return PullResult.Failed(r.message)
            is KosyncResult.Ok -> r.value ?: return PullResult.NoRemote
        }
        val local = dao.progressFor(book.id)
        // Our own last push echoed back; nothing worth jumping to. The deviceId is
        // the identity — the device NAME is user-editable and must not participate.
        // Only short-circuit while local progress still exists: if the book was
        // deleted and re-imported, our own remote record is what we want to restore.
        if (local != null && record.deviceId.equals(prefs.deviceId(), ignoreCase = true)) {
            return PullResult.UpToDate
        }
        val remoteNewer = when {
            local == null -> true
            // 2s slack: server timestamps are whole seconds and clocks drift.
            record.timestamp != null -> record.timestamp * 1000 > local.updatedAt + 2000
            else -> record.percentage > local.percentage &&
                abs(record.percentage - local.percentage) > 0.0005f
        }
        if (!remoteNewer) return PullResult.UpToDate
        val decoded = ProgressCodec.decode(record.progress)
        // KOReader xpointer fallback: DocFragment[N] is the 1-based spine index.
        val xpointerSpine = if (decoded == null) {
            XPOINTER_SPINE_RE.find(record.progress.trim())
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it > 0 }?.minus(1)
        } else null
        return PullResult.RemoteNewer(
            spineIndex = decoded?.first ?: xpointerSpine,
            charOffset = decoded?.second,
            percentage = record.percentage,
            fromDevice = record.device,
        )
    }

    override suspend fun pushProgress(book: BookEntity): PushResult {
        val settings = prefs.current()
        if (!settings.syncUsable()) return PushResult.Disabled
        val row = dao.progressFor(book.id) ?: return PushResult.NothingToPush
        val syncedAt = row.syncedAt
        if (syncedAt != null && syncedAt >= row.updatedAt) return PushResult.NothingToPush
        val remote = RemoteProgress(
            document = book.digest,
            progress = ProgressCodec.encode(row.spineIndex, row.charOffset),
            percentage = row.percentage,
            device = settings.deviceName,
            deviceId = prefs.deviceId().uppercase(), // wire convention: uppercase hex
            timestamp = null,
        )
        return when (val r = api.updateProgress(settings.account(), remote)) {
            is KosyncResult.Err -> PushResult.Failed(r.message)
            is KosyncResult.Ok -> {
                // Conditional UPDATE, not a row write-back: a save that landed while
                // the PUT was in flight must not be clobbered or marked clean.
                dao.markSynced(book.id, row.updatedAt)
                PushResult.Pushed
            }
        }
    }

    override suspend fun syncAllDirty(): SyncSummary {
        if (!prefs.current().syncUsable()) return SyncSummary(0, 0, 0)
        var pushed = 0
        var failed = 0
        var skipped = 0
        for (row in dao.dirtyProgress()) {
            val book = dao.bookById(row.bookId) ?: continue
            when (pushProgress(book)) {
                PushResult.Pushed -> pushed++
                is PushResult.Failed -> failed++
                else -> skipped++
            }
        }
        prefs.setLastSyncAt(System.currentTimeMillis())
        return SyncSummary(pushed, failed, skipped)
    }

    override suspend fun testConnection(account: KosyncAccount, register: Boolean): KosyncResult<Unit> =
        if (register) api.register(account) else api.authorize(account)

    private fun Settings.syncUsable(): Boolean =
        syncEnabled && username.isNotBlank() && userKey.isNotBlank() && serverUrl.isNotBlank()

    private fun Settings.account(): KosyncAccount = KosyncAccount(serverUrl, username, userKey)

    private companion object {
        val XPOINTER_SPINE_RE = Regex("""^/body/DocFragment\[(\d+)\]""")
    }
}
