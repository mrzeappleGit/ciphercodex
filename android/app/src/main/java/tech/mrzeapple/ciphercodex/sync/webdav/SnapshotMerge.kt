package tech.mrzeapple.ciphercodex.sync.webdav

object SnapshotMerge {

    data class Merged(
        val books: Map<String, SnapBook>,
        val progress: Map<String, SnapProgress>,
        val bookmarks: Map<String, SnapBookmark>,
        val highlights: Map<String, SnapHighlight>,
        val collections: Map<String, SnapCollection>,
        val bookCollections: Map<Pair<String, String>, SnapBookCollection>,
        val sessions: Map<String, SnapSession>,
    )

    fun wins(remoteUpdatedAt: Long, remoteDeleted: Int, localUpdatedAt: Long, localDeleted: Int): Boolean =
        remoteUpdatedAt > localUpdatedAt ||
            (remoteUpdatedAt == localUpdatedAt && remoteDeleted == 1 && localDeleted == 0)

    private fun <K, V> lww(
        snapshots: List<Snapshot>, rows: (Snapshot) -> List<V>,
        key: (V) -> K, updatedAt: (V) -> Long, deleted: (V) -> Int,
    ): Map<K, V> {
        val out = HashMap<K, V>()
        for (snap in snapshots) for (r in rows(snap)) {
            val cur = out[key(r)]
            if (cur == null || wins(updatedAt(r), deleted(r), updatedAt(cur), deleted(cur))) out[key(r)] = r
        }
        return out
    }

    fun merge(snapshots: List<Snapshot>): Merged = Merged(
        books = lww(snapshots, { it.books }, { it.digest }, { it.updatedAt }, { it.deleted }),
        progress = lww(snapshots, { it.progress }, { it.bookDigest }, { it.updatedAt }, { it.deleted }),
        bookmarks = lww(snapshots, { it.bookmarks }, { it.guid }, { it.updatedAt }, { it.deleted }),
        highlights = lww(snapshots, { it.highlights }, { it.guid }, { it.updatedAt }, { it.deleted }),
        collections = lww(snapshots, { it.collections }, { it.guid }, { it.updatedAt }, { it.deleted }),
        bookCollections = lww(snapshots, { it.bookCollections },
            { Pair(it.collectionGuid, it.bookDigest) }, { it.updatedAt }, { it.deleted }),
        sessions = lww(snapshots, { it.sessions }, { it.guid }, { it.updatedAt }, { it.deleted }),
    )
}
