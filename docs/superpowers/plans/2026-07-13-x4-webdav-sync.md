# X4 WebDAV Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Full two-way WebDAV sync on the X4 CrossPoint firmware — snapshot push/pull plus epub upload/download against the frozen phase3b contract — so books and reading state converge with Android and the reMarkable 2.

**Architecture:** A new `lib/CcxSync/` module: pure host-tested LWW merge core and PROPFIND href scanner; an `esp_http_client` WebDAV client following `HttpDownloader`/`KOReaderSyncClient` patterns; per-book sync state as tiny JSON files inside each book's existing `.crosspoint/epub_<hash>/` cache dir (global config in a small `/.crosspoint/ccxsync.json`); snapshots stream-parsed with the repo's `StreamingJsonParser` straight off the HTTP stream (rM2 ink blobs never touch heap) and stream-written to an SD temp file for upload. Foreground-only: user-triggered SYNC NOW, Wi-Fi up → NTP → sync → Wi-Fi down (kosync's exact shape).

**Tech Stack:** Arduino C++ (gnu++2a, -fno-exceptions), esp_http_client + esp_crt_bundle, ArduinoJson 7.4.2 (small docs only), `StreamingJsonParser` (lib/JsonParser), GoogleTest host suite (test/ CMake), PlatformIO.

**Repo:** ALL code changes in `G:\nextcloud\projects\cipherCodex\x4-os` (separate git repo, remote `mrzeappleGit/ciphercodex-os`). Work on a new branch `ccx-webdav-sync` off `develop`. This plan file lives in the cipherCodex repo; commits land in x4-os.

## Global Constraints

- Wire contract FROZEN (`remarkable2-os/docs/phase3b-contracts.md` in the cipherCodex repo): camelCase field names; `deleted` is int 0/1; **`format` is an int: 1 = epub, 0 = pdf** (as emitted by the shipped rM2 firmware — the doc's parenthetical documents this); books merge by `digest` (kosync partialMD5 = `KOReaderDocumentId::calculate`); progress by `bookDigest`; bookmarks by `guid`; tombstone wins `updatedAt` ties; missing-parent records skipped. `points_b64`/`notebooks`/`pages`/`strokes` are never read or emitted by X4.
- Endpoint layout: `books/<digest>.epub` (immutable union), `state/<deviceId>.json` (full snapshot; PUT `state/<deviceId>.json.tmp` then MOVE with `Overwrite: T`).
- Owner decisions: full two-way (files + state); **SD files are NEVER deleted by sync** — tombstoned digests are respected in the snapshot (no resurrection, no re-upload, no re-download) but the local file stays; sessions/highlights/collections not emitted by X4 (omission is safe — merge spans all snapshots); X4 emits `charOffset: 0` and applies remote positions as spine-start (same approximation as kosync interop today).
- LWW is never fed a 1970 clock: sync runs only after `NtpTime::syncOnce()`; if `time(nullptr) < 1577836800` (2020-01-01) the sync aborts with an on-screen message. Dirty flags (not timestamps) mark local changes; the sync run assigns real `updatedAt` values.
- 380KB RAM hard ceiling, no PSRAM: everything streamed or SD-backed; bounded arrays with named caps (`log` when capped); `MIN_HEAP_FOR_TLS = 55000` guard before any HTTPS call (KOReaderSyncClient precedent).
- HAL routing: all SD I/O via `Storage` (HalStorage) and `HalFile` — never SdFat/FsFile directly. Heap: `makeUniqueNoThrow<T>()` or `new (std::nothrow)` only; no exceptions; `memcpy` for any raw-buffer struct reads (RISC-V unaligned faults).
- User-facing strings via `tr(STR_*)` (add to the i18n table the same way existing `STR_KOREADER_*` strings are defined); logs via `LOG_INF/LOG_DBG/LOG_ERR`; never log the WebDAV password.
- Creds stored plaintext in `/.crosspoint/ccxsync.json` like `/.crosspoint/koreader.json` (documented existing posture).
- Foreground-only sync (SCOPE.md Active-Connectivity rule): manual SYNC NOW; Wi-Fi connects for the transfer and the activity tears it down with the `WiFi.disconnect(false); delay(30); silentRestart()` pattern every other network activity uses.
- Build: `pio run` from the x4-os root (default env). Host tests: `cmake -S test -B build/test && cmake --build build/test && ctest --test-dir build/test --output-on-failure -j`.
- Class names PascalCase matching filenames, methods camelCase, `#pragma once`.

---

### Task 1: CcxMerge — pure LWW core (host-tested, TDD)

**Files (x4-os repo):**
- Create: `lib/CcxSync/CcxMerge.h` (header-only, dependency-free — the `CipherCodexProgress.h` pattern)
- Create: `test/ccx_merge/CMakeLists.txt`, `test/ccx_merge/CcxMergeTest.cpp`
- Modify: `test/CMakeLists.txt` (add `add_subdirectory(ccx_merge)` beside the existing six)

**Interfaces:**
- Produces (later tasks rely on these exact names):
```cpp
namespace ccxsync {
struct MergedBook     { std::string digest, guid, title; long long updatedAt = 0; int deleted = 0; int format = 1; };
struct MergedProgress { std::string bookDigest; int spineIndex = 0; float percentage = 0.f; long long updatedAt = 0; int deleted = 0; };
struct MergedBookmark { std::string guid, bookDigest, label; int spineIndex = 0; float percentage = 0.f; long long createdAt = 0, updatedAt = 0; int deleted = 0; };
bool wins(long long remoteUpdatedAt, int remoteDeleted, long long localUpdatedAt, int localDeleted);
class Accumulator {  // fold rows one at a time; snapshots never held whole
 public:
  static constexpr size_t BOOKS_CAP = 400;
  static constexpr size_t BOOKMARKS_CAP = 256;
  void setLocalDigestFilter(std::vector<std::string> digests); // progress/bookmarks for other digests are dropped at fold time
  void foldBook(const MergedBook& b);         // LWW by digest; format != 1 dropped; capped
  void foldProgress(const MergedProgress& p); // LWW by bookDigest; filtered
  void foldBookmark(const MergedBookmark& m); // LWW by guid; filtered; capped
  const std::vector<MergedBook>& books() const { return books_; }
  const std::vector<MergedProgress>& progress() const { return progress_; }
  const std::vector<MergedBookmark>& bookmarks() const { return bookmarks_; }
  size_t dropped() const { return dropped_; } // cap/filter drops, for the sync log
 private: /* vectors + linear find; counts are capped so O(n) find is fine */
};
}  // namespace ccxsync
```

- [ ] **Step 1: Write the failing tests**

`test/ccx_merge/CcxMergeTest.cpp`:

```cpp
#include <gtest/gtest.h>
#include "CcxSync/CcxMerge.h"

using namespace ccxsync;

namespace {
MergedBook book(const std::string& d, long long up, int del = 0, const std::string& g = "g") {
  MergedBook b; b.digest = d; b.guid = g; b.title = "t"; b.updatedAt = up; b.deleted = del; return b;
}
MergedBookmark bm(const std::string& g, long long up, int del = 0) {
  MergedBookmark m; m.guid = g; m.bookDigest = "d1"; m.label = "L"; m.updatedAt = up; m.deleted = del; return m;
}
}  // namespace

TEST(CcxMergeWins, LwwAndTombstoneTies) {
  EXPECT_TRUE(wins(20, 0, 10, 0));   // newer remote
  EXPECT_FALSE(wins(10, 0, 20, 0));  // never lower a newer local
  EXPECT_TRUE(wins(10, 1, 10, 0));   // tie: tombstone wins
  EXPECT_FALSE(wins(10, 0, 10, 1));  // tie: live does not beat tombstone
  EXPECT_FALSE(wins(10, 0, 10, 0));  // tie, both live: keep first
}

TEST(CcxMergeAccumulator, BooksLwwByDigestOrderIndependent) {
  Accumulator a, b;
  a.foldBook(book("d1", 10, 0, "guidA")); a.foldBook(book("d1", 20, 0, "guidB"));
  b.foldBook(book("d1", 20, 0, "guidB")); b.foldBook(book("d1", 10, 0, "guidA"));
  ASSERT_EQ(a.books().size(), 1u);
  EXPECT_EQ(a.books()[0].guid, "guidB");
  EXPECT_EQ(b.books()[0].guid, "guidB");
}

TEST(CcxMergeAccumulator, TombstoneBeatsOlderEditAndNoResurrection) {
  Accumulator a;
  a.foldBook(book("d1", 30, 1));
  a.foldBook(book("d1", 10, 0));  // older live copy must not revive
  ASSERT_EQ(a.books().size(), 1u);
  EXPECT_EQ(a.books()[0].deleted, 1);
}

TEST(CcxMergeAccumulator, PdfBooksDropped) {
  MergedBook pdf = book("d2", 10); pdf.format = 0;
  Accumulator a; a.foldBook(pdf);
  EXPECT_TRUE(a.books().empty());
  EXPECT_EQ(a.dropped(), 1u);
}

TEST(CcxMergeAccumulator, LocalDigestFilterDropsForeignChildren) {
  Accumulator a; a.setLocalDigestFilter({"d1"});
  MergedProgress p1; p1.bookDigest = "d1"; p1.updatedAt = 5;
  MergedProgress p2; p2.bookDigest = "dX"; p2.updatedAt = 5;
  a.foldProgress(p1); a.foldProgress(p2);
  ASSERT_EQ(a.progress().size(), 1u);
  EXPECT_EQ(a.progress()[0].bookDigest, "d1");
  EXPECT_EQ(a.dropped(), 1u);
}

TEST(CcxMergeAccumulator, BookmarksLwwByGuid) {
  Accumulator a; a.setLocalDigestFilter({"d1"});
  a.foldBookmark(bm("m1", 10)); a.foldBookmark(bm("m1", 20, 1)); a.foldBookmark(bm("m2", 5));
  ASSERT_EQ(a.bookmarks().size(), 2u);
  for (const auto& m : a.bookmarks())
    if (m.guid == "m1") EXPECT_EQ(m.deleted, 1);
}

TEST(CcxMergeAccumulator, BooksCapDropsAndCounts) {
  Accumulator a;
  for (size_t i = 0; i <= Accumulator::BOOKS_CAP; i++)
    a.foldBook(book("d" + std::to_string(i), 1));
  EXPECT_EQ(a.books().size(), Accumulator::BOOKS_CAP);
  EXPECT_EQ(a.dropped(), 1u);
}
```

`test/ccx_merge/CMakeLists.txt` (mirror `test/ciphercodex_progress/CMakeLists.txt` — copy its structure, changing names):

```cmake
add_executable(ccx_merge_test CcxMergeTest.cpp)
target_link_libraries(ccx_merge_test PRIVATE crosspoint_test_common GTest::gtest_main)
gtest_discover_tests(ccx_merge_test)
```
(If the sibling CMakeLists differs in form, mirror the sibling exactly.)

- [ ] **Step 2: Run to verify FAIL**

```bash
cmake -S test -B build/test && cmake --build build/test
```
Expected: compile FAILURE (`CcxSync/CcxMerge.h: No such file`).

- [ ] **Step 3: Implement `lib/CcxSync/CcxMerge.h`**

```cpp
#pragma once

#include <string>
#include <vector>

// Pure LWW merge decisions for the CipherCodex WebDAV sync (frozen phase3b
// contract). Header-only and dependency-free so the host GoogleTest suite
// covers it (CipherCodexProgress.h pattern). Rows are folded one at a time by
// the streaming snapshot parser; whole snapshots are never held in memory.
namespace ccxsync {

struct MergedBook     { std::string digest, guid, title; long long updatedAt = 0; int deleted = 0; int format = 1; };
struct MergedProgress { std::string bookDigest; int spineIndex = 0; float percentage = 0.f; long long updatedAt = 0; int deleted = 0; };
struct MergedBookmark { std::string guid, bookDigest, label; int spineIndex = 0; float percentage = 0.f; long long createdAt = 0, updatedAt = 0; int deleted = 0; };

// True when the remote row should replace a local row carrying
// (localUpdatedAt, localDeleted). Tombstone wins updatedAt ties.
inline bool wins(long long remoteUpdatedAt, int remoteDeleted, long long localUpdatedAt, int localDeleted) {
  return remoteUpdatedAt > localUpdatedAt ||
         (remoteUpdatedAt == localUpdatedAt && remoteDeleted == 1 && localDeleted == 0);
}

class Accumulator {
 public:
  static constexpr size_t BOOKS_CAP = 400;      // heap bound: ~150B/row worst case
  static constexpr size_t BOOKMARKS_CAP = 256;  // across all local books

  void setLocalDigestFilter(std::vector<std::string> digests) { filter_ = std::move(digests); hasFilter_ = true; }

  void foldBook(const MergedBook& b) {
    if (b.format != 1) { dropped_++; return; }  // epub-only on X4 (1 = epub on the wire)
    for (auto& cur : books_) {
      if (cur.digest == b.digest) {
        if (wins(b.updatedAt, b.deleted, cur.updatedAt, cur.deleted)) cur = b;
        return;
      }
    }
    if (books_.size() >= BOOKS_CAP) { dropped_++; return; }
    books_.push_back(b);
  }

  void foldProgress(const MergedProgress& p) {
    if (!inFilter(p.bookDigest)) { dropped_++; return; }
    for (auto& cur : progress_) {
      if (cur.bookDigest == p.bookDigest) {
        if (wins(p.updatedAt, p.deleted, cur.updatedAt, cur.deleted)) cur = p;
        return;
      }
    }
    progress_.push_back(p);  // bounded by filter size (local book count)
  }

  void foldBookmark(const MergedBookmark& m) {
    if (!inFilter(m.bookDigest)) { dropped_++; return; }
    for (auto& cur : bookmarks_) {
      if (cur.guid == m.guid) {
        if (wins(m.updatedAt, m.deleted, cur.updatedAt, cur.deleted)) cur = m;
        return;
      }
    }
    if (bookmarks_.size() >= BOOKMARKS_CAP) { dropped_++; return; }
    bookmarks_.push_back(m);
  }

  const std::vector<MergedBook>& books() const { return books_; }
  const std::vector<MergedProgress>& progress() const { return progress_; }
  const std::vector<MergedBookmark>& bookmarks() const { return bookmarks_; }
  size_t dropped() const { return dropped_; }

 private:
  bool inFilter(const std::string& digest) const {
    if (!hasFilter_) return true;
    for (const auto& d : filter_) if (d == digest) return true;
    return false;
  }
  std::vector<MergedBook> books_;
  std::vector<MergedProgress> progress_;
  std::vector<MergedBookmark> bookmarks_;
  std::vector<std::string> filter_;
  bool hasFilter_ = false;
  size_t dropped_ = 0;
};

}  // namespace ccxsync
```

- [ ] **Step 4: Run to verify PASS**

```bash
cmake --build build/test && ctest --test-dir build/test --output-on-failure -j
```
Expected: all ccx_merge tests PASS (existing suites still green).

- [ ] **Step 5: Commit (in x4-os)**

```bash
git add lib/CcxSync/CcxMerge.h test/ccx_merge/ test/CMakeLists.txt
git commit -m "feat(ccxsync): pure LWW merge core + host convergence tests"
```

---

### Task 2: CcxHrefScan — streaming PROPFIND href scanner (host-tested, TDD)

**Files (x4-os repo):**
- Create: `lib/CcxSync/CcxHrefScan.h` (header-only, dependency-free)
- Create: `test/ccx_href_scan/CMakeLists.txt`, `test/ccx_href_scan/CcxHrefScanTest.cpp`
- Modify: `test/CMakeLists.txt` (add subdirectory)

**Interfaces:**
- Produces: `ccxsync::CcxHrefScan(requestPath, sink)` — feed raw PROPFIND response chunks; sink receives each decoded child name (no trailing `/`, self excluded). Chunk boundaries may split tags anywhere.

```cpp
class CcxHrefScan {
 public:
  using Sink = void (*)(void* ctx, const char* name);
  CcxHrefScan(std::string requestPath, Sink sink, void* ctx);
  void feed(const char* data, size_t len);
};
```

- [ ] **Step 1: Write the failing tests**

```cpp
#include <gtest/gtest.h>
#include <string>
#include <vector>
#include "CcxSync/CcxHrefScan.h"

using ccxsync::CcxHrefScan;

namespace {
std::vector<std::string> scan(const std::string& xml, const std::string& reqPath, size_t chunk = 7) {
  std::vector<std::string> out;
  CcxHrefScan s(reqPath, [](void* ctx, const char* name) {
    static_cast<std::vector<std::string>*>(ctx)->push_back(name);
  }, &out);
  for (size_t i = 0; i < xml.size(); i += chunk) s.feed(xml.data() + i, std::min(chunk, xml.size() - i));
  return out;
}
const char* kDufs =
    "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">"
    "<D:response><D:href>/ccx/state/</D:href></D:response>"
    "<D:response><D:href>/ccx/state/aabb01.json</D:href></D:response>"
    "<D:response><D:href>/ccx/state/dead%20beef.json</D:href></D:response>"
    "</D:multistatus>";
}  // namespace

TEST(CcxHrefScan, ExtractsChildrenSkipsSelfDecodesPercent) {
  auto names = scan(kDufs, "/ccx/state/");
  ASSERT_EQ(names.size(), 2u);
  EXPECT_EQ(names[0], "aabb01.json");
  EXPECT_EQ(names[1], "dead beef.json");
}

TEST(CcxHrefScan, SurvivesTagSplitAcrossEveryChunkBoundary) {
  for (size_t chunk = 1; chunk <= 16; chunk++) {
    auto names = scan(kDufs, "/ccx/state/", chunk);
    ASSERT_EQ(names.size(), 2u) << "chunk=" << chunk;
  }
}

TEST(CcxHrefScan, LowercasePrefixAndDirTrailingSlash) {
  auto names = scan(
      "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/ccx/</d:href></d:response>"
      "<d:response><d:href>/ccx/books/</d:href></d:response></d:multistatus>",
      "/ccx/");
  ASSERT_EQ(names.size(), 1u);
  EXPECT_EQ(names[0], "books");
}
```

`test/ccx_href_scan/CMakeLists.txt`: mirror Task 1's.

- [ ] **Step 2: Run to verify FAIL** (same cmake commands; expect missing-header compile failure)

- [ ] **Step 3: Implement `lib/CcxSync/CcxHrefScan.h`**

```cpp
#pragma once

#include <cctype>
#include <cstring>
#include <string>

// Incremental scanner for WebDAV PROPFIND responses: finds <?:href>...</?:href>
// spans in a chunked byte stream (dufs/Nextcloud shape), percent-decodes, drops
// the request path itself, and reports each child's last path segment. No XML
// library on purpose: bounded state, arbitrary chunk splits, ~1KB worst case.
namespace ccxsync {

class CcxHrefScan {
 public:
  using Sink = void (*)(void* ctx, const char* name);

  CcxHrefScan(std::string requestPath, Sink sink, void* ctx)
      : self_(std::move(requestPath)), sink_(sink), ctx_(ctx) {
    while (!self_.empty() && self_.back() == '/') self_.pop_back();
  }

  void feed(const char* data, size_t len) {
    for (size_t i = 0; i < len; i++) step(static_cast<char>(std::tolower(static_cast<unsigned char>(data[i]))), data[i]);
  }

 private:
  static constexpr size_t HREF_MAX = 512;  // hrefs longer than this are dropped

  // Matches "<X:href>" or "<href>" then captures until '<'.
  void step(char lower, char raw) {
    if (!inHref_) {
      tag_ += lower;
      if (tag_.size() > 16) tag_.erase(0, tag_.size() - 16);
      // accept "<href>" with optional one-or-more alpha namespace prefix + ':'
      size_t pos = tag_.rfind("href>");
      if (pos != std::string::npos && pos >= 1) {
        size_t open = tag_.rfind('<', pos);
        if (open != std::string::npos) {
          bool ok = (open + 1 == pos);  // "<href>"
          if (!ok && tag_[pos - 1] == ':') {  // "<d:href>", "<D:href>", "<ns:href>"
            ok = true;
            for (size_t j = open + 1; j + 1 < pos; j++)
              if (!std::isalpha(static_cast<unsigned char>(tag_[j]))) { ok = false; break; }
          }
          if (ok && tag_[open] == '<') { inHref_ = true; href_.clear(); tag_.clear(); return; }
        }
      }
      return;
    }
    if (raw == '<') { emit(); inHref_ = false; tag_.clear(); tag_ += lower; return; }
    if (href_.size() < HREF_MAX) href_ += raw;
  }

  void emit() {
    std::string p = percentDecode(href_);
    while (!p.empty() && p.back() == '/') p.pop_back();
    if (p.empty() || p == self_) return;
    size_t slash = p.rfind('/');
    std::string name = (slash == std::string::npos) ? p : p.substr(slash + 1);
    if (!name.empty() && sink_) sink_(ctx_, name.c_str());
  }

  static std::string percentDecode(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); i++) {
      if (in[i] == '%' && i + 2 < in.size() && std::isxdigit(static_cast<unsigned char>(in[i + 1])) &&
          std::isxdigit(static_cast<unsigned char>(in[i + 2]))) {
        auto hex = [](char c) { return (c <= '9') ? c - '0' : (std::tolower(c) - 'a' + 10); };
        out += static_cast<char>(hex(in[i + 1]) * 16 + hex(in[i + 2]));
        i += 2;
      } else {
        out += in[i];
      }
    }
    return out;
  }

  std::string self_, tag_, href_;
  bool inHref_ = false;
  Sink sink_;
  void* ctx_;
};

}  // namespace ccxsync
```

- [ ] **Step 4: Run to verify PASS** (ctest; all suites green)

- [ ] **Step 5: Commit**

```bash
git add lib/CcxSync/CcxHrefScan.h test/ccx_href_scan/ test/CMakeLists.txt
git commit -m "feat(ccxsync): streaming PROPFIND href scanner + host tests"
```

---

### Task 3: CcxWebDav — esp_http_client WebDAV client

**Files (x4-os repo):**
- Create: `lib/CcxSync/CcxWebDav.h`, `lib/CcxSync/CcxWebDav.cpp`

**Interfaces:**
- Consumes: `CcxHrefScan` (Task 2), `Storage`/`HalFile` (HAL), `esp_http_client`, `esp_crt_bundle`.
- Produces:
```cpp
class CcxWebDav {
 public:
  CcxWebDav(std::string baseUrl, std::string user, std::string pass);  // baseUrl normalized to end with '/'
  bool test();                                                          // PROPFIND base, Depth 0
  bool list(const std::string& relDir, std::vector<std::string>& outNames, size_t cap);  // Depth 1, streamed scan; false on HTTP error
  bool getToFile(const std::string& relPath, const std::string& destPath);
  using DataFn = bool (*)(void* ctx, const char* data, size_t len);     // return false to abort
  bool getStreaming(const std::string& relPath, DataFn onData, void* ctx);
  bool putFile(const std::string& relPath, const std::string& srcPath, const char* contentType);
  bool mkcol(const std::string& relDir);                                // 2xx or 405 both ok
  bool move(const std::string& fromRel, const std::string& toRel);      // Overwrite: T
  int lastStatus() const { return lastStatus_; }
 private:
  bool request(const char* method, const std::string& relPath, /* headers/body/sink params */ ...);
  std::string base_, auth_;   // auth_ = "Basic " + base64(user:pass), built once
  int lastStatus_ = 0;
};
```

- [ ] **Step 1: Implement**

Follow `src/network/HttpDownloader.cpp` (manual `esp_http_client_open` → `fetch_headers` → `read` loop, `crt_bundle_attach = esp_crt_bundle_attach`, preemptive `Authorization` header via `esp_http_client_set_header`, `READ_CHUNK`-sized heap buffer via `makeUniqueNoThrow<char[]>`) and `lib/KOReaderSync/KOReaderSyncClient.cpp` (the `MIN_HEAP_FOR_TLS = 55000` free-heap guard before any `https://` call — replicate the guard with the same constant and a `LOG_ERR` on refusal). Core shape (one private worker; public methods are thin wrappers):

```cpp
// CcxWebDav.cpp — key parts (full file assembled by the implementer from these)
namespace {
constexpr int HTTP_TIMEOUT_MS = 30000;
constexpr size_t READ_CHUNK = 2048;
constexpr size_t MIN_HEAP_FOR_TLS = 55000;  // mbedTLS handshake can eat ~48KB (KOReaderSyncClient lesson)
constexpr char PROPFIND_BODY[] =
    "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>";

std::string base64(const std::string& in);  // implement with mbedtls_base64_encode (already linked) or a 20-line local encoder
}  // namespace

CcxWebDav::CcxWebDav(std::string baseUrl, std::string user, std::string pass) {
  base_ = std::move(baseUrl);
  if (base_.empty() || base_.back() != '/') base_ += '/';
  auth_ = "Basic " + base64(user + ":" + pass);
}
```

The private `request()` (used by every verb):
1. If URL starts with `https://` and `esp_get_free_heap_size() < MIN_HEAP_FOR_TLS` → `LOG_ERR("CCXDAV", "heap too low for TLS: %u", ...)`, return false.
2. `esp_http_client_config_t cfg = {}` — `.url`, `.timeout_ms = HTTP_TIMEOUT_MS`, `.crt_bundle_attach = esp_crt_bundle_attach`, `.buffer_size = 4096`, `.buffer_size_tx = 1024`.
3. `esp_http_client_set_method(client, method)` — custom verbs via `esp_http_client_set_method(client, HTTP_METHOD_*)` is enum-only, so PROPFIND/MKCOL/MOVE need `esp_http_client_init` + `esp_http_client_set_method` with the closest enum? **No** — esp_http_client supports arbitrary methods only through the enum; use the documented approach from this codebase's IDF version: check `esp_http_client.h` in the toolchain for `HTTP_METHOD_PROPFIND`, `HTTP_METHOD_MKCOL`, `HTTP_METHOD_MOVE`, `HTTP_METHOD_COPY` — ESP-IDF's enum includes the WebDAV verbs (PROPFIND/PROPPATCH/MKCOL/COPY/MOVE/LOCK/UNLOCK) since v4.3. Use them directly.
4. Headers: `Authorization: auth_`, plus per-verb extras — `Depth: 0|1` + `Content-Type: application/xml` (PROPFIND), `Destination: base_ + toRel` + `Overwrite: T` (MOVE).
5. Body: for PROPFIND, `esp_http_client_open(client, strlen(PROPFIND_BODY))` then `esp_http_client_write`; for `putFile`, open with the file's size and stream `READ_CHUNK` bytes from a `HalFile` opened via `Storage.openFileForRead`; for bodyless verbs open with 0.
6. `esp_http_client_fetch_headers`, then read loop into the chunk buffer, dispatching to the sink (discard for `test`/`mkcol`/`move`; `CcxHrefScan::feed` for `list`; `HalFile` write for `getToFile` — write to `destPath + ".tmp"` then `Storage.remove(destPath)` + `Storage.rename(tmp, destPath)`, the `ProgressFile::writeAtomic` pattern; the caller's `DataFn` for `getStreaming`, aborting the read loop if it returns false).
7. `lastStatus_ = esp_http_client_get_status_code(client)`; success = `2xx` (plus 405 for MKCOL, and 207 counts as 2xx-family for PROPFIND — accept `status >= 200 && status < 300` or `status == 207`); always `esp_http_client_close` + `cleanup`.
8. `list()` caps `outNames` at `cap` entries (stop appending, keep count, `LOG_INF` when capped).

No retries, no redirect-following (the dufs endpoint doesn't redirect; HttpDownloader's redirect loop is for OTA CDNs — YAGNI here).

- [ ] **Step 2: Build**

```bash
pio run
```
Expected: SUCCESS. (No host test — the pure scanner is already covered; HTTP paths get exercised on-device in Task 8.)

- [ ] **Step 3: Commit**

```bash
git add lib/CcxSync/CcxWebDav.h lib/CcxSync/CcxWebDav.cpp
git commit -m "feat(ccxsync): esp_http_client WebDAV verbs (PROPFIND/GET/PUT/MKCOL/MOVE)"
```

---

### Task 4: CcxSyncState — config + per-book sync state

**Files (x4-os repo):**
- Create: `lib/CcxSync/CcxSyncState.h`, `lib/CcxSync/CcxSyncState.cpp`

**Interfaces:**
- Consumes: `Storage`/`HalFile`, ArduinoJson (v7 `JsonDocument`), `esp_random()`.
- Produces:
```cpp
struct CcxBookState {                       // one small JSON per book, in the book's cache dir
  std::string path;                         // SD path of the epub (for orphan detection)
  long long fileSize = 0, fileMtime = 0;    // digest cache validity key
  std::string digest, guid;                 // 32-hex partialMD5; UUIDv4-hex row guid
  long long firstSeenAt = 0;                // book row updatedAt on the wire (constant after mint)
  long long localStamp = 0;                 // updatedAt of the last locally-authored progress
  int pendSpine = -1; float pendPct = 0;    // remote-won position awaiting reader apply
  long long pendAt = 0;
  std::vector<std::string> bmGuids;         // bookmark guids as of last sync (deletion detection), cap 32
  bool dirtyProgress = false, dirtyBookmarks = false;
};

class CcxSyncState {                        // singleton: CCXSYNC_STATE
 public:
  static CcxSyncState& getInstance();
  // global config: /.crosspoint/ccxsync.json
  bool loadGlobal(); bool saveGlobal();
  std::string url, user, pass, deviceId;    // deviceId minted (32-hex from esp_random) on first save
  long long lastSyncAt = 0;
  struct Tombstone { std::string guid; std::string digest; long long at = 0; };  // learned; guid empty = book tombstone
  std::vector<Tombstone> tombstones;        // cap 256, oldest dropped
  bool hasConfig() const { return !url.empty(); }
  // per-book state: <cacheDir>/ccxsync.json
  static bool loadBook(const std::string& cacheDir, CcxBookState& out);   // false = no state yet
  static bool saveBook(const std::string& cacheDir, const CcxBookState& s);
  static void markProgressDirty(const std::string& cacheDir);   // load→set→save (reader-exit hook)
  static void markBookmarksDirty(const std::string& cacheDir);  // bookmark-save hook
};
#define CCXSYNC_STATE CcxSyncState::getInstance()
```

- [ ] **Step 1: Implement**

Mirror `KOReaderCredentialStore`/`KOReaderJsonIO` (singleton, JSON on SD, `Storage.readFile`/write via `HalFile`). Global file `/.crosspoint/ccxsync.json`:

```json
{"url":"","user":"","pass":"","deviceId":"<hex32>","lastSyncAt":0,
 "tombstones":[{"guid":"","digest":"<hex32>","at":123}]}
```

Per-book file `<cacheDir>/ccxsync.json` (cacheDir = the book's existing `.crosspoint/epub_<hash>` dir, same place `progress.bin` lives):

```json
{"path":"/Books/x.epub","size":123,"mtime":456,"digest":"<hex32>","guid":"<hex32>",
 "firstSeenAt":0,"localStamp":0,"pendSpine":-1,"pendPct":0.0,"pendAt":0,
 "bmGuids":["<hex32>"],"dirtyP":false,"dirtyB":false}
```

Notes:
- One ArduinoJson `JsonDocument` per file, always small (global ≤ ~20KB with full tombstones; per-book ≤ ~2KB) — never one big document for all books.
- `deviceId` mint: 16 bytes from `esp_random()` rendered lowercase hex (32 chars). Guid mint helper `CcxSyncState::newGuid()` same way (public static — Task 6 uses it).
- `markProgressDirty`/`markBookmarksDirty` are load-modify-save of the small per-book file; if no state file exists yet, create one with only the dirty flag set (path/digest filled in at sync time). These run at reader-exit / bookmark-save — not on page turns.
- Never log `pass`. Writes atomic: write `.tmp` then remove+rename (ProgressFile pattern).

- [ ] **Step 2: Build** — `pio run` → SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add lib/CcxSync/CcxSyncState.h lib/CcxSync/CcxSyncState.cpp
git commit -m "feat(ccxsync): global config + per-book sync state (ccxsync.json)"
```

---

### Task 5: CcxSnapshotIO — streaming parse + snapshot export

**Files (x4-os repo):**
- Create: `lib/CcxSync/CcxSnapshotParse.h` (header-only: StreamingJsonParser callbacks → Accumulator)
- Create: `lib/CcxSync/CcxSnapshotWrite.h`, `lib/CcxSync/CcxSnapshotWrite.cpp`
- Create: `test/ccx_snapshot_parse/CMakeLists.txt`, `test/ccx_snapshot_parse/CcxSnapshotParseTest.cpp`
- Modify: `test/CMakeLists.txt`

**Interfaces:**
- Consumes: `StreamingJsonParser` (lib/JsonParser — host-buildable, already in the test tree), `CcxMerge::Accumulator`.
- Produces:
```cpp
// Parse: feed raw snapshot JSON bytes (straight off the HTTP stream); rows fold
// into the accumulator as they complete. Ink arrays are ignored structurally;
// >512-byte strings (points_b64) overflow StreamingJsonParser's token buffer and
// are silently dropped by it — they never allocate.
class CcxSnapshotParse {
 public:
  explicit CcxSnapshotParse(ccxsync::Accumulator& acc);
  void feed(const char* data, size_t len);
  bool failed() const;   // malformed JSON (parser error)
};
// Write: stream the X4's snapshot to an SD file, one row at a time.
class CcxSnapshotWrite {
 public:
  bool begin(const std::string& sdPath, const std::string& deviceId, long long generatedAt);
  void addBook(const ccxsync::MergedBook& b, long long addedAt);
  void addProgress(const ccxsync::MergedProgress& p);
  void addBookmark(const ccxsync::MergedBookmark& m);
  bool finish();  // closes arrays/object + file; false on any write error
};
```

- [ ] **Step 1: Write the failing parse test**

`test/ccx_snapshot_parse/CcxSnapshotParseTest.cpp`:

```cpp
#include <gtest/gtest.h>
#include <string>
#include "CcxSync/CcxMerge.h"
#include "CcxSync/CcxSnapshotParse.h"

namespace {
// rM2-shaped snapshot: ink arrays present (one points_b64 > 512 bytes to prove
// token-overflow tolerance), one pdf book, camelCase contract fields.
std::string rm2Snapshot() {
  std::string bigBlob(700, 'A');
  return std::string("{\"deviceId\":\"aabb01\",\"generatedAt\":1,")
    + "\"books\":[{\"digest\":\"d1\",\"guid\":\"g1\",\"title\":\"Dune\",\"author\":\"H\",\"format\":1,"
      "\"addedAt\":1,\"lastOpenedAt\":2,\"deleted\":0,\"updatedAt\":10},"
      "{\"digest\":\"d2\",\"guid\":\"g2\",\"title\":\"Scan\",\"format\":0,\"deleted\":0,\"updatedAt\":11}],"
    + "\"progress\":[{\"bookDigest\":\"d1\",\"spineIndex\":3,\"charOffset\":120,\"percentage\":0.25,"
      "\"deleted\":0,\"updatedAt\":11}],"
    + "\"bookmarks\":[{\"guid\":\"m1\",\"bookDigest\":\"d1\",\"spineIndex\":1,\"charOffset\":5,"
      "\"percentage\":0.1,\"label\":\"start\",\"createdAt\":3,\"deleted\":0,\"updatedAt\":12}],"
    + "\"highlights\":[{\"guid\":\"h1\",\"bookDigest\":\"d1\",\"text\":\"x\",\"deleted\":0,\"updatedAt\":13}],"
    + "\"notebooks\":[{\"guid\":\"n1\",\"title\":\"ink\",\"deleted\":0,\"updatedAt\":16}],"
    + "\"strokes\":[{\"guid\":\"s1\",\"pageGuid\":\"p1\",\"tool\":0,\"baseWidth\":2.0,"
      "\"points_b64\":\"" + bigBlob + "\",\"deleted\":0,\"updatedAt\":18}]}";
}
}  // namespace

TEST(CcxSnapshotParse, FoldsWantedRowsSkipsInkAndPdf) {
  ccxsync::Accumulator acc;
  acc.setLocalDigestFilter({"d1"});
  CcxSnapshotParse p(acc);
  std::string s = rm2Snapshot();
  for (size_t i = 0; i < s.size(); i += 13) p.feed(s.data() + i, std::min<size_t>(13, s.size() - i));
  EXPECT_FALSE(p.failed());
  ASSERT_EQ(acc.books().size(), 1u);          // pdf dropped
  EXPECT_EQ(acc.books()[0].digest, "d1");
  EXPECT_EQ(acc.books()[0].title, "Dune");
  ASSERT_EQ(acc.progress().size(), 1u);
  EXPECT_EQ(acc.progress()[0].spineIndex, 3);
  EXPECT_FLOAT_EQ(acc.progress()[0].percentage, 0.25f);
  ASSERT_EQ(acc.bookmarks().size(), 1u);
  EXPECT_EQ(acc.bookmarks()[0].label, "start");
}

TEST(CcxSnapshotParse, MissingArraysAndUnknownFieldsAreFine) {
  ccxsync::Accumulator acc;
  CcxSnapshotParse p(acc);
  const char* s = "{\"deviceId\":\"x\",\"future\":{\"nested\":[1,2]}}";
  p.feed(s, strlen(s));
  EXPECT_FALSE(p.failed());
  EXPECT_TRUE(acc.books().empty());
}
```

`test/ccx_snapshot_parse/CMakeLists.txt`: mirror the existing `test/streaming_json_parser/CMakeLists.txt` (it already compiles `lib/JsonParser/StreamingJsonParser.cpp` for the host — link the same way and add `CcxSnapshotParseTest.cpp`).

- [ ] **Step 2: Run to verify FAIL** (missing header)

- [ ] **Step 3: Implement `CcxSnapshotParse.h`**

```cpp
#pragma once

#include <cstring>
#include <string>

#include "CcxMerge.h"
#include "JsonParser/StreamingJsonParser.h"

// SAX fold of a device snapshot into the merge accumulator. Tracks which
// top-level array it is inside (books/progress/bookmarks — everything else,
// including rM2 ink arrays, is skipped structurally). Depth-aware so nested
// objects in future/unknown sections can't confuse section tracking.
class CcxSnapshotParse {
 public:
  explicit CcxSnapshotParse(ccxsync::Accumulator& acc) : acc_(acc), parser_(makeCallbacks()) {}

  void feed(const char* data, size_t len) { parser_.feed(data, len); }
  bool failed() const { return parser_.hasError(); }

 private:
  enum class Section : uint8_t { NONE, BOOKS, PROGRESS, BOOKMARKS, OTHER };

  JsonCallbacks makeCallbacks() {
    JsonCallbacks cb = {};
    cb.ctx = this;
    cb.onKey = [](void* c, const char* k, size_t n) { static_cast<CcxSnapshotParse*>(c)->onKey(k, n); };
    cb.onString = [](void* c, const char* v, size_t n) { static_cast<CcxSnapshotParse*>(c)->onValue(v, n, true); };
    cb.onNumber = [](void* c, const char* v, size_t n) { static_cast<CcxSnapshotParse*>(c)->onValue(v, n, false); };
    cb.onBool = [](void* c, bool) { static_cast<CcxSnapshotParse*>(c)->key_.clear(); };
    cb.onNull = [](void* c) { static_cast<CcxSnapshotParse*>(c)->key_.clear(); };
    cb.onObjectStart = [](void* c) { static_cast<CcxSnapshotParse*>(c)->onObjectStart(); };
    cb.onObjectEnd = [](void* c) { static_cast<CcxSnapshotParse*>(c)->onObjectEnd(); };
    cb.onArrayStart = [](void* c) { static_cast<CcxSnapshotParse*>(c)->onArrayStart(); };
    cb.onArrayEnd = [](void* c) { static_cast<CcxSnapshotParse*>(c)->onArrayEnd(); };
    return cb;
  }

  void onKey(const char* k, size_t n) {
    key_.assign(k, n);
    if (depth_ == 1) {  // top-level key names the upcoming section
      if (key_ == "books") pendingSection_ = Section::BOOKS;
      else if (key_ == "progress") pendingSection_ = Section::PROGRESS;
      else if (key_ == "bookmarks") pendingSection_ = Section::BOOKMARKS;
      else pendingSection_ = Section::OTHER;
    }
  }

  void onArrayStart() {
    depth_++;
    if (depth_ == 2) { section_ = pendingSection_; pendingSection_ = Section::OTHER; }
  }
  void onArrayEnd() {
    depth_--;
    if (depth_ == 1) section_ = Section::NONE;
  }
  void onObjectStart() {
    depth_++;
    if (depth_ == 3 && section_ != Section::NONE && section_ != Section::OTHER) resetRow();
  }
  void onObjectEnd() {
    if (depth_ == 3) emitRow();
    depth_--;
  }

  void onValue(const char* v, size_t n, bool isString) {
    if (depth_ != 3 || section_ == Section::NONE || section_ == Section::OTHER) { key_.clear(); return; }
    std::string val(v, n);
    if (isString) setStr(key_, val); else setNum(key_, val);
    key_.clear();
  }

  void resetRow() { book_ = {}; prog_ = {}; bm_ = {}; }

  void setStr(const std::string& k, const std::string& v) {
    switch (section_) {
      case Section::BOOKS:
        if (k == "digest") book_.digest = v;
        else if (k == "guid") book_.guid = v;
        else if (k == "title") book_.title = v.substr(0, 64);
        break;
      case Section::PROGRESS:
        if (k == "bookDigest") prog_.bookDigest = v;
        break;
      case Section::BOOKMARKS:
        if (k == "guid") bm_.guid = v;
        else if (k == "bookDigest") bm_.bookDigest = v;
        else if (k == "label") bm_.label = v.substr(0, 96);
        break;
      default: break;
    }
  }

  void setNum(const std::string& k, const std::string& v) {
    long long ll = std::strtoll(v.c_str(), nullptr, 10);
    float f = std::strtof(v.c_str(), nullptr);
    switch (section_) {
      case Section::BOOKS:
        if (k == "updatedAt") book_.updatedAt = ll;
        else if (k == "deleted") book_.deleted = static_cast<int>(ll);
        else if (k == "format") book_.format = static_cast<int>(ll);
        break;
      case Section::PROGRESS:
        if (k == "spineIndex") prog_.spineIndex = static_cast<int>(ll);
        else if (k == "percentage") prog_.percentage = f;
        else if (k == "updatedAt") prog_.updatedAt = ll;
        else if (k == "deleted") prog_.deleted = static_cast<int>(ll);
        break;
      case Section::BOOKMARKS:
        if (k == "spineIndex") bm_.spineIndex = static_cast<int>(ll);
        else if (k == "percentage") bm_.percentage = f;
        else if (k == "createdAt") bm_.createdAt = ll;
        else if (k == "updatedAt") bm_.updatedAt = ll;
        else if (k == "deleted") bm_.deleted = static_cast<int>(ll);
        break;
      default: break;
    }
  }

  void emitRow() {
    switch (section_) {
      case Section::BOOKS:     if (!book_.digest.empty()) acc_.foldBook(book_); break;
      case Section::PROGRESS:  if (!prog_.bookDigest.empty()) acc_.foldProgress(prog_); break;
      case Section::BOOKMARKS: if (!bm_.guid.empty()) acc_.foldBookmark(bm_); break;
      default: break;
    }
  }

  ccxsync::Accumulator& acc_;
  StreamingJsonParser parser_;
  Section section_ = Section::NONE, pendingSection_ = Section::OTHER;
  int depth_ = 0;
  std::string key_;
  ccxsync::MergedBook book_;
  ccxsync::MergedProgress prog_;
  ccxsync::MergedBookmark bm_;
};
```

Note on `depth_`: `{` of the snapshot itself is depth 0→1 via `onObjectStart` — the counters above treat the ROOT object as depth 1 after its start; verify against `StreamingJsonParserTest.cpp`'s event ordering and adjust the two threshold constants (2 for section arrays, 3 for row objects) if the root convention differs. The host test in Step 1 is the arbiter.

- [ ] **Step 4: Run parse test to verify PASS** (ctest)

- [ ] **Step 5: Implement `CcxSnapshotWrite`**

Stream one row at a time through a small per-row ArduinoJson doc into a `HalFile` (never a whole-snapshot doc). `begin` opens `sdPath` for write and emits `{"deviceId":"...","generatedAt":N,"books":[`; `addBook/addProgress/addBookmark` serialize one small `JsonDocument` per row (`serializeJson(doc, buf)` into a stack `char[512]`, then `file.write`), tracking commas and array transitions (`"],\"progress\":["`, `"],\"bookmarks\":["`); `finish()` writes `"]}"`, flushes, closes, returns write-error status. Emit per contract:

```cpp
// books row:    {"digest","guid","title","author":null omitted,"format":1,"addedAt":firstSeenAt,
//                "lastOpenedAt":0,"deleted":0|1,"updatedAt":firstSeenAt}
// progress row: {"bookDigest","spineIndex","charOffset":0,"percentage","deleted":0,"updatedAt":localStamp}
// bookmarks row:{"guid","bookDigest","spineIndex","charOffset":0,"percentage","label",
//                "createdAt","deleted":0|1,"updatedAt"}
```
Tombstones from `CCXSYNC_STATE.tombstones` are emitted by the ENGINE calling `addBook`/`addBookmark` with `deleted = 1` (Task 6); the writer itself is dumb.

- [ ] **Step 6: Build + full host suite** — `pio run` SUCCESS; ctest all green.

- [ ] **Step 7: Commit**

```bash
git add lib/CcxSync/CcxSnapshotParse.h lib/CcxSync/CcxSnapshotWrite.h lib/CcxSync/CcxSnapshotWrite.cpp test/ccx_snapshot_parse/ test/CMakeLists.txt
git commit -m "feat(ccxsync): streaming snapshot parse (ink-safe) + SD snapshot writer"
```

---

### Task 6: CcxSyncEngine + reader hooks

**Files (x4-os repo):**
- Create: `lib/CcxSync/CcxSyncEngine.h`, `lib/CcxSync/CcxSyncEngine.cpp`
- Modify: `src/activities/reader/EpubReaderActivity.cpp` (dirty hook at exit; pending-position apply at enter)
- Modify: the bookmark-save call sites (grep `saveBookmarks(` under `src/` — add `CcxSyncState::markBookmarksDirty(cacheDir)` after each save)

**Interfaces:**
- Consumes: everything from Tasks 1–5, `KOReaderDocumentId::calculate`, `NtpTime::syncOnce`, the `AllBooksActivity::loadBooks` SD-walk pattern, `JsonSettingsIO::loadBookmarks/saveBookmarks`, `BookmarkUtil::getBookmarkPath`.
- Produces:
```cpp
class CcxSyncEngine {
 public:
  struct Summary { int booksUp = 0, booksDown = 0, entities = 0, tombstones = 0; std::string error; bool ok() const { return error.empty(); } };
  enum class Phase : uint8_t { SCAN, UPLOAD, PULL, APPLY, DOWNLOAD, PUSH, DONE };
  using PhaseFn = void (*)(void* ctx, Phase phase, int itemsDone, int itemsTotal);
  static Summary run(PhaseFn onPhase, void* ctx, bool* cancelFlag);  // blocking; call from an activity loop task
};
```

- [ ] **Step 1: Implement `run()`** — phases in order:

1. **Preconditions:** `CCXSYNC_STATE.loadGlobal()`; no url → error "not configured". `time(nullptr) < 1577836800` → error "clock not set" (the activity runs `NtpTime::syncOnce()` before calling run — the check is the backstop).
2. **SCAN:** SD walk for epubs using the exact `AllBooksActivity::loadBooks` bounds (iterative dirStack, `MAX_DIRS=400`, `MAX_QUEUED_DIRS=512`, skip `.`-prefixed and `System Volume Information`, `FsHelpers::hasEpubExtension`, cap 800). For each epub: derive its cache dir (same `std::hash<std::string>` helper the reader/cache code uses — grep for `epub_` + `std::hash` and call the existing function; do NOT reimplement the hash), `CcxSyncState::loadBook`; if the state file is missing OR `fileSize/fileMtime` changed → compute `digest = KOReaderDocumentId::calculate(path)` (the expensive step — this cache is why it runs once per file change), mint `guid`/`firstSeenAt = now` if new, save. Collect `std::vector<LocalBook>{path, cacheDir, CcxBookState}`. Books whose digest appears in `CCXSYNC_STATE.tombstones` are EXCLUDED from upload and from the snapshot's live rows (owner decision: file stays, sync ignores it).
3. **UPLOAD:** `dav.mkcol("books/")`, `dav.mkcol("state/")`; `dav.list("books/", remoteBooks, 1000)`; for each local non-tombstoned book whose `digest + ".epub"` is not in `remoteBooks` → `dav.putFile("books/" + digest + ".epub", path, "application/epub+zip")`, `booksUp++`. Report phase progress per file.
4. **PULL:** `dav.list("state/", names, 32)`; build `Accumulator acc; acc.setLocalDigestFilter(localDigests)` — **but books are NOT filtered** (Accumulator only filters progress/bookmarks; remote-only books are needed for download). For each `*.json` name: `CcxSnapshotParse parse(acc);` `dav.getStreaming("state/" + name, feedFn, &parse)`; if HTTP fails or `parse.failed()` → abort the whole sync with error "bad snapshot: <name>" (better no sync than a partial merge).
5. **APPLY (per merged row, no transaction machinery — each write is an atomic small file):**
   - Books: merged tombstoned digests → add to `CCXSYNC_STATE.tombstones` if a local book carries that digest (so it stops being uploaded/emitted live) — SD file untouched. `tombstones++`.
   - Progress: for each merged progress row whose `updatedAt > book.localStamp` → store as pending in the book's state (`pendSpine/pendPct/pendAt`), `entities++`. (Never touch `progress.bin` directly — pagination-dependent; the reader applies it, Step 3 below.)
   - Bookmarks: group merged bookmarks by digest; for each local book: load its bookmark file (`JsonSettingsIO::loadBookmarks`), match entries to guids via the book state's `bmGuids` positional pairing REPLACED BY: bookmark entries gain persisted `guid`/`updatedAt` fields (see Step 2). For each merged bookmark: `deleted == 1` → remove any local entry with that guid; live and (no local entry with that guid, or `wins(...)`) → insert/replace entry `{xpath: "", summary: label, percentage, computedSpineIndex: spineIndex}` with that guid/updatedAt. Save the file if changed. **Directed check:** before writing entries with empty `xpath`, read the bookmark-open path (grep `BookmarkEntry` usage in the reader/bookmark list activity) and confirm navigation falls back to `computedSpineIndex`/`percentage` when `xpath` is empty; if it would crash or no-op, jump via spine start using the same fallback the percentage-only path uses — report as a concern if neither exists.
   - Local bookmark deletions: entries whose guid was in `bmGuids` at last sync but is gone from the file now → append `{guid, digest, now}` to `CCXSYNC_STATE.tombstones`.
   - Dirty stamping: book state `dirtyProgress` → `localStamp = now` (this run's single `now = time-ms` captured once), clear flag. `dirtyBookmarks` → entries lacking guid get `newGuid()` + `updatedAt = now`; all entries in a dirty file get `updatedAt = now` (v1 approximation: X4 can't tell which entry changed; documented). Update `bmGuids` to the file's current guid set.
6. **DOWNLOAD:** merged live books whose digest is not local → `dav.getToFile("books/" + digest + ".epub", "/Books/" + sanitize(title) + ".epub")` (sanitize: strip `\/:*?"<>|`, trim, fallback `book-<digest8>`; on name collision append `-<digest8>`). Create the book's state file (digest, guid from the merged row, `firstSeenAt = merged.updatedAt`), `booksDown++`. Skip digests in `tombstones`.
7. **PUSH:** `CcxSnapshotWrite` to `/.crosspoint/ccxsync-out.tmp`: books = every local non-tombstoned book (live rows, `updatedAt = firstSeenAt`) plus every `tombstones` entry with empty guid as `{digest, deleted:1, updatedAt: at}`; progress = per local book with `localStamp > 0` `{spineIndex, percentage from progress.bin + pageCount…` — **source:** read the 6-byte `progress.bin` (`spineIndex, pageNumber, pageCount` little-endian; use `memcpy`, never struct-cast) and emit `spineIndex`, `percentage = pageCount > 0 ? … book-level percentage` — book-level percentage is not derivable from one spine's page counts; emit the reader's saved book percentage if the cache exposes it (grep how the home screen's progress % per book is computed and reuse that helper), else `percentage = 0` with a `LOG_INF` (position interop then rides on spineIndex, which is the part X4 applies anyway); bookmarks = all entries of all local books (guid/updatedAt from the file) plus bookmark tombstones. Then `dav.putFile("state/" + deviceId + ".json.tmp", …, "application/json")` and `dav.move(...json.tmp", ...json")`. Delete the SD temp.
8. `lastSyncAt = now`, `saveGlobal()`. Fill `Summary`.

Heap notes: one `Accumulator` for the whole run (bounded by caps); `CcxWebDav` client constructed once; check `cancelFlag` between files and between phases.

- [ ] **Step 2: Bookmark guid persistence**

Add two OPTIONAL fields to bookmark JSON: in `src/JsonSettingsIO.cpp` `saveBookmarks`/`loadBookmarks`, write/read `"guid"` (string, default "") and `"updatedAt"` (long long, default 0) per entry; add `std::string guid; long long updatedAt = 0;` to `struct BookmarkEntry` (`src/BookmarkEntry.h`). Old files without the fields load fine (defaults); old firmware reading new files ignores unknown keys (ArduinoJson).

- [ ] **Step 3: Reader hooks** (`src/activities/reader/EpubReaderActivity.cpp`)

- In `onExit()` (next to `READING_STATS.onReaderExit()`): `CcxSyncState::markProgressDirty(epub->getCachePath());` — guard on an actual progress write having happened this session if a flag exists; otherwise unconditional is acceptable (one small SD write per reader exit).
- In `onEnter()` after `progress.bin` is read: load the book's `CcxBookState`; if `pendAt > localStamp && pendSpine >= 0` → override the restored position to `spineIndex = pendSpine`, `pageNumber = 0` (spine start — the documented v1 approximation), then clear `pendSpine/pendAt` (set -1/0) and save the state. `LOG_INF("CCXSYNC", "applied synced position: spine %d", …)`.
- Bookmark saves: at each `JsonSettingsIO::saveBookmarks(` call site under `src/`, add `CcxSyncState::markBookmarksDirty(<cacheDir for that book>)` (derive the cache dir the same way the call site's surrounding code does for progress).

- [ ] **Step 4: Build + host suite** — `pio run` SUCCESS; ctest green.

- [ ] **Step 5: Commit**

```bash
git add lib/CcxSync/ src/BookmarkEntry.h src/JsonSettingsIO.cpp src/activities/reader/EpubReaderActivity.cpp <other touched call sites>
git commit -m "feat(ccxsync): sync engine — books union, LWW apply, snapshot push, reader hooks"
```

---

### Task 7: UI — settings + SYNC NOW activity

**Files (x4-os repo):**
- Create: `src/activities/settings/CcxSyncSettingsActivity.h/.cpp`
- Create: `src/activities/settings/CcxSyncActivity.h/.cpp` (the SYNC NOW runner)
- Modify: the settings menu that lists `KOReaderSettingsActivity` (grep for where it's constructed/registered — add a "CipherCodex Sync" row beside it)
- Modify: the i18n string table (add `STR_CCXSYNC_*` entries following the `STR_KOREADER_*` pattern; `gen_i18n.py` runs pre-build)

**Interfaces:**
- Consumes: `CCXSYNC_STATE`, `CcxWebDav::test()`, `CcxSyncEngine::run`, `KeyboardEntryActivity`, `WifiSelectionActivity`, `NtpTime::syncOnce`, `GUI.drawList`/`GUI.drawProgressBar`, `SilentRestart.h`.

- [ ] **Step 1: `CcxSyncSettingsActivity`** — mirror `KOReaderSettingsActivity` exactly: a 5-row list — SERVER URL (`KeyboardEntryActivity`, `InputType::Url`, 128 max), USERNAME (`InputType::Text`, 64), PASSWORD (`InputType::Password`, 64), TEST CONNECTION, SYNC NOW. Each edit saves through `CCXSYNC_STATE` setters + `saveGlobal()`. TEST: launch `WifiSelectionActivity` → `NtpTime::syncOnce()` → `CcxWebDav(url,user,pass).test()` → show OK / HTTP code (the `KOReaderAuthActivity` flow shape); tear down Wi-Fi with the standard `WiFi.disconnect(false); delay(30); silentRestart()` pattern on exit. SYNC NOW pushes `CcxSyncActivity`.

- [ ] **Step 2: `CcxSyncActivity`** — mirror `FontDownloadActivity`'s state machine: `WIFI_SELECTION → SYNCING → COMPLETE/ERROR`. After Wi-Fi connects: `NtpTime::syncOnce()`, then `CcxSyncEngine::run(phaseFn, this, &cancelRequested_)` with the phase callback updating a status line (`SCANNING… / UPLOADING i/N / PULLING STATE / APPLYING / DOWNLOADING i/N / PUSHING`) + `GUI.drawProgressBar`; Back sets `cancelRequested_`. On finish render the summary (`↑N ↓N ~N entities, T tombstones` or the error). Override `preventAutoSleep()` true while SYNCING, `skipLoopDelay()` true. `onExit()`: standard Wi-Fi teardown + `silentRestart()`. **Note:** `CcxSyncEngine::run` blocks — call it from the activity's `loop()` on the main task the same way `FontDownloadActivity` runs its download (check whether it downloads inside `loop()` or spawns; do exactly what it does).

- [ ] **Step 3: Register + strings** — add the settings-menu row and the `STR_CCXSYNC_*` strings (all user-visible text through `tr()`).

- [ ] **Step 4: Build** — `pio run` SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/activities/settings/CcxSync* <menu file> <i18n files>
git commit -m "feat(ccxsync): settings + SYNC NOW activity (wifi/NTP/progress/teardown)"
```

---

### Task 8: On-device QA + release tag

**Files:** none (QA), then version/tag per the repo's release convention (grep how v0.2.13 was tagged/versioned — `git log --oneline -5` + `platformio.ini` version defines).

- [ ] **Step 1: Flash** — `pio run -t upload` (device on USB; owner assistance may be needed for cabling).
- [ ] **Step 2: Live three-way acceptance** against `https://kosync.cph.gg/ccx/` (user `ccx`; password from the owner/VPS — never committed). NOTE: unlike the Android E2E, the X4 tests run against the REAL base path (its snapshot is additive and its device file is `state/<newDeviceId>.json`; books upload is a union — no destructive writes). Do NOT delete anything under `books/` or `state/` other than the X4's own state file if cleanup is wanted.
  1. Configure creds on-device → TEST → OK.
  2. SYNC NOW with 2–3 epubs on SD → summary `↑N`; verify `state/<x4DeviceId>.json` appears (curl PROPFIND) and its books carry `"format":1` as a NUMBER; verify epubs landed under `books/`.
  3. Verify Android + rM2 pick up the X4's books on their next sync (Android: emulator or phone SYNC NOW; rM2: on-device sync) — X4-only books appear (filename-stem titles), progress from X4 lands.
  4. Move progress on Android in a shared book → sync Android → SYNC NOW on X4 → open the book on X4 → position jumps to the synced chapter (spine-start approximation).
  5. Delete a book on Android → sync all three → X4: book vanishes from other devices, X4 keeps the SD file, X4's next snapshot carries the tombstone (not a live row), no re-upload (check `books/` mtime unchanged / summary `↑0`).
  6. Bookmarks: add one on X4 → sync → appears on Android/rM2; delete it on X4 → sync → tombstone propagates (gone on Android after its sync).
  7. rM2 ink: X4 sync completes with the rM2's stroke-laden snapshot present (no crash, heap floor logged ≥ 40KB free during parse — add a temporary `LOG_INF` heap print per phase if not already logged).
- [ ] **Step 3: Docs** — update x4-os `docs/` (or README section) with a short "CipherCodex Sync" user note (server URL, what syncs, the never-deletes-SD-files rule).
- [ ] **Step 4: Release** — bump the firmware version the same way v0.2.13 did, commit `release: vX.Y.Z — CipherCodex WebDAV sync`, push `develop` to `origin` and tag per the repo's ritual (check `.github/workflows/` for what triggers a release build; follow it).

---

## Self-review notes (already applied)

- Spec coverage: X1 (Tasks 2–4), X2 (Tasks 1, 5, 6), X3 (Tasks 7–8). Dirty-flag/NTP rule → Task 6 Steps 1/3 + Task 7; digest cache → Task 6 Step 1 (SCAN); ignore-deletes → Task 6 APPLY/DOWNLOAD + Task 8 step 5; sessions/highlights not emitted → CcxSnapshotWrite has no session/highlight writers by construction.
- Deviations from spec text (justified): bookmark guids persist IN the bookmark JSON entries (optional fields) rather than only a parallel list in ccxsync.json — the state file keeps only the last-synced guid SET for deletion detection; per-book sync state lives in each book's cache dir instead of one monolithic ccxsync.json (a single 800-book JSON doc would not fit in heap); `format` is int 1=epub (E2E-discovered wire truth, contradicts the spec's `format != "epub"` string comparison — int governs).
- Type consistency: `Accumulator` caps/`dropped()` used by engine logging; `CcxBookState` field names match between Task 4 (definition) and Task 6 (use); `CcxWebDav` signatures match between Task 3 (definition) and Tasks 6–7 (use); `MergedBook.format` int checked `!= 1` in exactly one place (foldBook).
- Known approximations (documented, v1): X4 bookmark updatedAt stamps whole-file on dirty; book-level percentage may emit 0 when no cached percentage helper exists (spineIndex carries the interop); remote positions apply as spine-start.
