package tech.mrzeapple.ciphercodex

import android.app.Application
import java.io.File
import tech.mrzeapple.ciphercodex.data.BookRepository
import tech.mrzeapple.ciphercodex.data.LibraryRepository
import tech.mrzeapple.ciphercodex.data.db.AppDatabase
import tech.mrzeapple.ciphercodex.data.prefs.UserPrefs
import tech.mrzeapple.ciphercodex.sync.KosyncApi
import tech.mrzeapple.ciphercodex.sync.KosyncClient
import tech.mrzeapple.ciphercodex.sync.KosyncSyncManager
import tech.mrzeapple.ciphercodex.sync.SyncManager
import tech.mrzeapple.ciphercodex.sync.SyncWorker
import tech.mrzeapple.ciphercodex.sync.webdav.WebDavSyncManager

/** Manual DI container. ViewModels reach this via
 *  `application as CipherCodexApp`. */
class CipherCodexApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val prefs: UserPrefs by lazy { UserPrefs(this) }
    val kosync: KosyncApi by lazy { KosyncClient() }
    val syncManager: SyncManager by lazy { KosyncSyncManager(prefs, database.bookDao(), kosync) }
    val repository: LibraryRepository by lazy {
        BookRepository(this, database.bookDao(), database.statsDao(), database.syncDao())
    }
    val webdavSync: WebDavSyncManager by lazy {
        WebDavSyncManager(prefs, database, repository, cacheDir, File(filesDir, "notebooks"))
    }

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }
}
