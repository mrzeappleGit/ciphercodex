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

    /** LWW per stroke guid first (a tombstone beats an older live copy), then
     *  group the surviving live strokes by page. */
    fun liveStrokesByPage(snapshots: List<InkSnapshot>): Map<String, List<InkStroke>> =
        lww(snapshots, { it.strokes }, { it.guid }, { it.updatedAt }, { it.deleted })
            .values.filter { it.deleted == 0 }
            .groupBy { it.pageGuid }

    fun contentStamp(liveStrokes: List<InkStroke>): Long =
        if (liveStrokes.isEmpty()) 0L
        else liveStrokes.maxOf { it.updatedAt } * 31 + liveStrokes.size
}
