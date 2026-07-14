package tech.mrzeapple.ciphercodex.sync.webdav

/** Pure LWW merge of the ink arrays across device snapshots. Reuses the
 *  frozen tie rules from SnapshotMerge.wins. */
object InkMerge {

    private fun <V> lww(
        snapshots: List<InkSnapshot>, rows: (InkSnapshot) -> List<V>,
        key: (V) -> String, updatedAt: (V) -> Long, deleted: (V) -> Int,
    ): Map<String, V> {
        val out = HashMap<String, V>()
        for (snap in snapshots) for (r in rows(snap)) {
            val cur = out[key(r)]
            if (cur == null || SnapshotMerge.wins(updatedAt(r), deleted(r), updatedAt(cur), deleted(cur))) {
                out[key(r)] = r
            }
        }
        return out
    }

    fun mergeNotebooks(snapshots: List<InkSnapshot>): Map<String, InkNotebook> =
        lww(snapshots, { it.notebooks }, { it.guid }, { it.updatedAt }, { it.deleted })

    fun mergePages(snapshots: List<InkSnapshot>): Map<String, InkPage> =
        lww(snapshots, { it.pages }, { it.guid }, { it.updatedAt }, { it.deleted })

    /** LWW per stroke guid, tombstones INCLUDED — the caller persists winners so a
     *  tombstone can out-vote a stale live copy arriving later. */
    fun mergeStrokes(snapshots: List<InkSnapshot>): Map<String, InkStroke> =
        lww(snapshots, { it.strokes }, { it.guid }, { it.updatedAt }, { it.deleted })

    fun contentStamp(liveStrokes: List<InkStroke>): Long =
        if (liveStrokes.isEmpty()) 0L
        else liveStrokes.maxOf { it.updatedAt } * 31 + liveStrokes.size

    @JvmName("contentStampEntities")
    fun contentStamp(liveStrokes: List<tech.mrzeapple.ciphercodex.data.db.StrokeEntity>): Long =
        if (liveStrokes.isEmpty()) 0L
        else liveStrokes.maxOf { it.updatedAt } * 31 + liveStrokes.size
}
