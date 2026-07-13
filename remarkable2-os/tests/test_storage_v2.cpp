// Host-side v2 schema test: a fresh DB runs v1+v2, schema_version==2, every v2
// table exists, FK cascade wipes dependent rows, and the unique digest holds.
#include "storage.h"

#include <sqlite3.h>

#include <QTemporaryDir>

#include <cassert>
#include <cstdio>

static bool tableExists(sqlite3 *db, const char *name)
{
    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                              -1, &q, nullptr) == SQLITE_OK);
    sqlite3_bind_text(q, 1, name, -1, SQLITE_STATIC);
    const bool ok = sqlite3_step(q) == SQLITE_ROW;
    sqlite3_finalize(q);
    return ok;
}

static long long scalar(sqlite3 *db, const char *sql)
{
    sqlite3_stmt *q = nullptr;
    assert(sqlite3_prepare_v2(db, sql, -1, &q, nullptr) == SQLITE_OK);
    assert(sqlite3_step(q) == SQLITE_ROW);
    const long long v = sqlite3_column_int64(q, 0);
    sqlite3_finalize(q);
    return v;
}

static void exec(sqlite3 *db, const char *sql)
{
    char *err = nullptr;
    if (sqlite3_exec(db, sql, nullptr, nullptr, &err) != SQLITE_OK) {
        fprintf(stderr, "exec failed: %s -- %s\n", err ? err : "?", sql);
        assert(!"exec failed");
    }
}

int main()
{
    QTemporaryDir tmp;
    assert(tmp.isValid());
    QString err;
    Storage *st = Storage::open(tmp.path(), &err);
    assert(st);
    sqlite3 *db = st->handle();
    assert(db);

    // fresh DB ran v1, v2, v3, then v4 (page_text — recognized handwriting)
    assert(scalar(db, "SELECT version FROM schema_version") == 4);

    const char *const v2Tables[] = {"books", "progress", "reading_sessions", "bookmarks",
                                    "highlights", "collections", "book_collections", "settings"};
    for (const char *t : v2Tables)
        assert(tableExists(db, t));
    assert(tableExists(db, "sync_state"));  // v3 device-local sync bookkeeping
    assert(tableExists(db, "page_text"));   // v4 recognized handwriting

    // FK cascade: deleting the book removes its progress + highlight rows.
    exec(db, "INSERT INTO books(id,title,file_path,digest,size_bytes,format,added_at)"
             " VALUES(1,'T',' /p.pdf','deadbeef',10,0,1000)");
    exec(db, "INSERT INTO progress(book_id,spine_index,char_offset,percentage,updated_at)"
             " VALUES(1,0,0,0.5,1000)");
    exec(db, "INSERT INTO highlights(book_id,spine_index,start_char,end_char,text,created_at)"
             " VALUES(1,0,0,5,'hi',1000)");
    assert(scalar(db, "SELECT COUNT(*) FROM progress") == 1);
    assert(scalar(db, "SELECT COUNT(*) FROM highlights") == 1);

    exec(db, "DELETE FROM books WHERE id=1");
    assert(scalar(db, "SELECT COUNT(*) FROM books") == 0);
    assert(scalar(db, "SELECT COUNT(*) FROM progress") == 0);    // cascaded
    assert(scalar(db, "SELECT COUNT(*) FROM highlights") == 0);  // cascaded

    // unique digest index rejects a duplicate
    exec(db, "INSERT INTO books(id,title,file_path,digest,size_bytes,added_at)"
             " VALUES(2,'A','/a','dup',1,1)");
    char *e = nullptr;
    const int rc = sqlite3_exec(db, "INSERT INTO books(id,title,file_path,digest,size_bytes,added_at)"
                                    " VALUES(3,'B','/b','dup',1,1)",
                                nullptr, nullptr, &e);
    assert(rc != SQLITE_OK);
    sqlite3_free(e);

    // page_text FK cascade: deleting a page removes its page_text row (foreign_keys=ON
    // is set in Storage::open, so the raw hard DELETE below exercises the real FK).
    exec(db, "INSERT INTO notebooks(id,title,created_at,updated_at) VALUES(100,'PT',1,1)");
    exec(db, "INSERT INTO pages(id,notebook_id,seq) VALUES(200,100,1)");
    exec(db, "INSERT INTO page_text(id,page_id,text) VALUES(300,200,'hello world')");
    assert(scalar(db, "SELECT COUNT(*) FROM page_text") == 1);
    exec(db, "DELETE FROM pages WHERE id=200");
    assert(scalar(db, "SELECT COUNT(*) FROM page_text") == 0);  // cascaded

    // Storage::pageText(): empty when none, live text once a row exists.
    const qint64 nb = st->createNotebook(QStringLiteral("Recipes"));
    const qint64 pg = st->createPage(nb);
    assert(st->pageText(pg).isEmpty());
    char ptSql[160];
    std::snprintf(ptSql, sizeof ptSql,
                  "INSERT INTO page_text(page_id,text) VALUES(%lld,'flour sugar butter')",
                  static_cast<long long>(pg));
    exec(db, ptSql);
    assert(st->pageText(pg) == QStringLiteral("flour sugar butter"));

    // Storage::notebooks(query): matches by title OR live page text.
    assert(st->notebooks(QStringLiteral("Recipes")).size() == 1);
    assert(st->notebooks(QStringLiteral("sugar")).size() == 1);
    assert(st->notebooks(QStringLiteral("nomatch")).isEmpty());

    delete st;
    printf("ALL STORAGE V2 TESTS PASSED\n");
    return 0;
}
