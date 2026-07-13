package tech.mrzeapple.ciphercodex.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class, ProgressEntity::class, ReadingSessionEntity::class,
        BookmarkEntity::class, HighlightEntity::class,
        CollectionEntity::class, BookCollectionCrossRef::class,
        NotebookEntity::class, NotebookPageEntity::class, PageTextEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun statsDao(): StatsDao
    abstract fun syncDao(): SyncDao
    abstract fun notesDao(): NotesDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` INTEGER NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER NOT NULL, " +
                        "`pagesTurned` INTEGER NOT NULL, " +
                        "`startPercentage` REAL NOT NULL, " +
                        "`endPercentage` REAL NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` INTEGER NOT NULL, " +
                        "`spineIndex` INTEGER NOT NULL, " +
                        "`charOffset` INTEGER NOT NULL, " +
                        "`percentage` REAL NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `highlights` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` INTEGER NOT NULL, " +
                        "`spineIndex` INTEGER NOT NULL, " +
                        "`startChar` INTEGER NOT NULL, " +
                        "`endChar` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_highlights_bookId_spineIndex` " +
                        "ON `highlights` (`bookId`, `spineIndex`)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_collections` (" +
                        "`collectionId` INTEGER NOT NULL, `bookId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionId`, `bookId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_book_collections_bookId` " +
                        "ON `book_collections` (`bookId`)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `highlights` ADD COLUMN `note` TEXT")
                db.execSQL("ALTER TABLE `highlights` ADD COLUMN `colorId` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val nowMs = System.currentTimeMillis()
                // guid-carrying tables: (table, updatedAt backfill expression)
                listOf(
                    "books" to "COALESCE(lastOpenedAt, addedAt)",
                    "bookmarks" to "createdAt",
                    "highlights" to "createdAt",
                    "collections" to "createdAt",
                    "reading_sessions" to "endedAt",
                ).forEach { (t, backfill) ->
                    db.execSQL("ALTER TABLE `$t` ADD COLUMN `guid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$t` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `$t` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE `$t` SET guid = lower(hex(randomblob(16))), updatedAt = $backfill")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${t}_guid` ON `$t` (`guid`)")
                }
                db.execSQL("ALTER TABLE `book_collections` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `book_collections` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `book_collections` SET updatedAt = $nowMs")
                db.execSQL("ALTER TABLE `progress` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notebooks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`guid` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notebooks_guid` ON `notebooks` (`guid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notebook_pages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`guid` TEXT NOT NULL, " +
                        "`notebookGuid` TEXT NOT NULL, " +
                        "`seq` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`contentStamp` INTEGER NOT NULL, " +
                        "`imagePath` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notebook_pages_guid` ON `notebook_pages` (`guid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notebook_pages_notebookGuid` ON `notebook_pages` (`notebookGuid`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // letter-for-letter what Room generates for PageTextEntity (v4->v5 lesson)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `page_texts` (`pageGuid` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `sourceStamp` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pageGuid`))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "ciphercodex.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                )
                .build()
    }
}
