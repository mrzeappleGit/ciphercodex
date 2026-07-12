# Phase 2b contracts — EPUB reader + kosync device integration (frozen)

Scope: read EPUB documents, and wire kosync into the live device flow so acceptance test #7
(cross-device EPUB position sync with the Android app + a KOReader client) can run. Highlights /
text selection / Kept are the NEXT slice (2c); handwritten annotation over docs is 2d. The v2 DB
schema already has every table needed.

## The one hard interop rule

Cross-device sync transports only `(spine_index, char_offset, percentage)` in the kosync `progress`
string `ciphercodex:s=<spine>;o=<offset>`. Therefore:

- **`char_offset` must index into a byte-identical "built chapter text"** as the Android app's
  `buildChapterText`. Reproduce `XhtmlMapper` + `buildChapterText` EXACTLY (see Android sources
  below). Pagination (page cuts) is device-local and need NOT match — the receiving device maps the
  incoming `char_offset` to its own page via `pageIndexFor`.
- **Spine numbering must match Android**: keep every manifest-resolved `<itemref>` in order,
  INCLUDING ones whose zip entry is missing (placeholder chapter). Do not compact the spine.
- KOReader interop is chapter-level: KOReader writes `/body/DocFragment[N]` xpointers; the ported
  ProgressCodec foreign fallback already maps that to spine `N-1` and positions by percentage.

Authoritative Android sources to port byte-for-byte (READ THEM, do not work from prose):
`android/app/src/main/java/tech/mrzeapple/ciphercodex/epub/XhtmlMapper.kt` (block model, inline
whitespace collapse incl U+00A0/U+2007/U+202F, br, spans, links, anchors, skipped tags, block-
boundary set, heading levels, img/hr, the ~150-entry HtmlEntities table),
`.../ui/reader/Pagination.kt` (`buildChapterText`: block concat, "* * *" rule = 5 chars, image =
one U+FFFC, `\n` separator between blocks kept inside the preceding block's range; and `paginate`
greedy line-cut + `pageIndexFor`), `.../epub/EpubParser.kt` (zip index, container.xml, OPF spine/
manifest/metadata/`resolvePath`/`spineWeights`, TOC nav→NCX→fallback, `resolveLink`),
`.../epub/EpubModels.kt` (Block types, EpubChapter, spineWeights semantics).

## Dependencies (verified in SDK + device)

- **Zip**: Qt private `QZipReader` (`#include <QtCore/private/qzipreader_p.h>`, link
  `Qt6::CorePrivate`). No vendoring. EPUB is a zip; read entries by normalized name.
- XML: `QXmlStreamReader` (Qt6::Core). It reports undefined named entities as errors, so
  PRE-SUBSTITUTE the HtmlEntities table into the byte stream before parsing, OR feed a DTD subset;
  porting the entity map + a manual `&name;` pre-pass is the reliable route (mirror the Kotlin
  intent: entities resolved to their unicode replacement before/within tokenizing).
- Text layout/render: `QTextDocument` + `QTextLayout` (Qt6::Gui). Fonts on device: Noto Serif/
  Sans/Mono, EB Garamond.

## Module: EpubDocument — `src/epub/epubdocument.{h,cpp}` (port of EpubParser + models)

```cpp
struct EpubTocEntry { QString title; int spineIndex; };
struct EpubMeta { QString title, author, language, description; }; // empty string = absent
struct LinkTarget { int spineIndex; QString anchor; bool ok; };

// One built chapter: the offset space + render inputs. `text` is byte-identical to Android's
// buildChapterText output; all char offsets index into it.
struct BuiltChapter {
    int spineIndex;
    QString text;                       // THE offset space (with '\n' separators, U+FFFC images, "* * *")
    QVector<QPair<int,int>> blockRanges;    // per-block [start,end) into text
    QVector<int> blockKinds;                // 0 para,1 heading,2 rule,3 image (parallel to blockRanges)
    QVector<int> headingLevels;             // level per block (0 if not heading)
    QVector<QPair<QPair<int,int>,int>> spans;   // ((start,end), styleBit) bold1/italic2/underline4/sub8/sup16
    QVector<QPair<QPair<int,int>,QString>> links; // ((start,end), href)
    QVector<QPair<int,QString>> images;     // (charIndex of U+FFFC, zipPath)
    QHash<QString,int> anchors;             // id -> block index
    int charCount;                          // sum of block textLengths (Android charCount, for percentage)
};

class EpubDocument {
public:
    static EpubDocument *open(const QString &path, QString *err); // container.xml -> OPF eager; chapters lazy
    ~EpubDocument();
    EpubMeta metadata() const;
    int spineCount() const;
    QVector<EpubTocEntry> toc() const;
    QVector<qint64> spineWeights() const;   // compressedSize>0 ? compressed : size>0 ? size : 1; missing->1
    BuiltChapter chapter(int spineIndex);   // lazy parse + build; internal LRU (a few chapters)
    QByteArray coverImageBytes();
    QByteArray imageBytes(const QString &zipPath); // null on missing; never throws
    LinkTarget resolveLink(int fromSpineIndex, const QString &href);
};
```
`resolvePath(baseDir, href)`: strip `#…`/`?…`, percent-decode UTF-8 (`%HH` only, leave `+`), `\`→`/`,
handle leading `/`, split and resolve `.`/`..`, rejoin — EXACT per EpubParser. Reject zip entries
escaping the archive. Note `QZipReader::FileInfo` may expose only uncompressed `size`; if compressed
size is unavailable, use uncompressed size for `spineWeights` and RECORD the deviation (percentage
may differ slightly cross-device; position is carried by char_offset, so this is a tolerated ceiling).

## Byte-identical built text — the crux (host golden tests REQUIRED)

`XhtmlMapper` + `buildChapterText` port must pass `tests/test_epub_text.cpp`, plain-assert golden
cases hand-derived from the algorithm. At minimum:
1. Whitespace collapse: `<p>  a\n\tb  </p>` -> block text `"a b"`; leading/trailing trimmed.
2. NBSP-family collapse: text with U+00A0/U+2007/U+202F between words collapses to one space.
3. Entities: `<p>a&mdash;b&nbsp;c</p>` -> `"a—b c"` (nbsp is collapsible -> becomes space:
   verify against the Kotlin: nbsp replacement is U+00A0 which isCollapsibleSpace -> collapses to ' ').
4. Two paragraphs -> `"p1\np2"` (single '\n' between, none trailing); blockRanges `[0,len(p1))` and
   `[len(p1)+1, end)` (the '\n' stays in the FIRST block's range per Android: contentEnd = length-1
   when separated).
5. Rule between paras -> `"p1\n* * *\np2"`; image -> single U+FFFC char in its own block.
6. `<br>` inside a paragraph -> literal '\n' inside the block text (trailing-space stripped first).
7. Heading `<h2>Hi</h2>` -> block text `"Hi"`, kind heading, level 2.
8. Skipped `<script>/<style>/<head>` subtrees contribute no text.
Derive each expected string by executing the Kotlin logic by hand; comment the derivation.

## Module: EpubRenderer/pagination — `src/epub/epubrender.{h,cpp}` (device-local)

Build a `QTextDocument` from a BuiltChapter: one QTextBlock per block (paragraph first-line indent
16px, heading bold + scale [1.6,1.45,1.3,1.2,1.1,1.05], rule centered "* * *", image centered
placeholder), char formats from `spans`/`links`. Maintain the offset<->docPosition map via
`blockRanges` + `QTextBlock::position()` (offset in built text -> block i (blockRanges lookup) ->
docPos = block(i).position() + (offset - blockRanges[i].start)); inverse for selection later.
Paginate by walking every layout line (across blocks) collecting (lineTopPx, lineStart, lineEnd) in
DOC positions, greedy-cut into pages that fit the content height, image blocks standalone — mirror
`paginate`. Provide `pageIndexForOffset(builtOffset)` and `pageStartOffset(pageIndex)`.
Typography inputs: font family, body px size, line-spacing multiple, side margins, justify — from
settings. Re-paginate on any change. Whole-book percentage: `(before + weights[spine]*within)/total`
with `within = clamp(startCharOfPage / max(1,charCount), 0, 1)`.

## Module: EpubView — `src/epub/epubview.{h,cpp}` QQuickPaintedItem, QML_ELEMENT

Renders the current page (QTextDocument drawContents translated by -topPx, clipped to
contentHeight; images drawn from `imageBytes`). Exposes: `spineIndex`, `spineCount`, `pageInSpine`,
`pagesInSpine`, `percentage`; invokables `openDocument(path)`, `next()/prev()` (cross chapter
boundaries), `goToLocation(spine,charOffset)`, `goToSpine(spine)`, `setTypography(...)`,
`follow(href)` (returns whether it was a footnote vs jump; maintains a 10-deep return stack;
`back()`), and a chunked cancelable `startSearch(q)` emitting `searchHit(spine,charOffset,snippet)`.
Page turn: tap-zones L/R third + horizontal swipe (both hands). No EPScreenModeItem (reading =
quality waveform). Pen arrives as synth mouse (canvasRect unset). Emits `locationChanged` on every
turn (spine + charOffset of the page's first char) so the controller can save + push progress.

## Kosync device integration — extend `src/sync/` + a small controller surface

- `ReaderController` (or a new `SyncController`) reads/writes the `settings` table keys
  (sync_enabled, server_url, username, user_key, device_name, device_id, last_sync_at) and exposes
  QML invokables: `syncConfig()`, `setSyncConfig(server,username,password,deviceName)` (password ->
  user_key = md5hex, never store raw), `testConnection()` (GET /users/auth -> ok/err),
  `registerUser()`. device_id created once (UUIDv4 dashes stripped) and persisted.
- Book open flow (EPUB and PDF): compute the book's stored digest; `pullOnOpen(bookId)` returns
  {state, spine, charOffset, percentage, device} per the ported KosyncSyncManager logic (own-echo,
  remoteNewer with 2s + 0.0005 slack). If RemoteNewer, the reader shows a JUMP/STAY prompt
  ("SYNC // <device> @ <pct>%"); JUMP -> goToLocation(spine,charOffset) (or by percentage if no
  offset), STAY keeps local.
- Save+push on: page turn (debounced save), leaving the reader, and suspend. Push is fire-and-
  forget with dirty-retry (`syncAllDirty`). All network on a bounded timeout (already added).
- Reuse the existing `KosyncClient` (has 15s timeout) and `kosync.cpp` pure logic (codec, dirty,
  userKey, truncation) — all host-tested. The blocking QEventLoop client runs on the GUI thread but
  ONLY on explicit user actions (open/close/test/manual sync), never on a background poll (brief:
  "No background network polling").

## QML — `src/qml/`

- `EpubReaderScreen.qml`: full-bleed EpubView; slim toolbar BACK / title / chapter x·of·N or
  page-in-chapter / TOC / MARKS / SEARCH / Aa (typography) ; progress scrubber (whole-book %); TOC
  drawer (spine list -> goToSpine); bookmark list; search results (spine+snippet -> jump); footnote
  popup for short same-chapter link targets; return-stack BACK affordance after a jump. Typography
  panel: font family (Serif/Sans/Mono/Garamond), size -/+, line spacing, margins, justify toggle,
  portrait/landscape. Saves + pushes progress on turn/leave.
- `BookDetailScreen.qml`: EPUB books now open the EPUB reader (drop the placeholder); RESUME uses
  the saved (spine,charOffset). On open, run pullOnOpen and show the JUMP/STAY prompt if ahead.
- `SettingsScreen.qml` (new or minimal): KOReader sync server / username / password / device name +
  TEST CONNECTION + REGISTER; sync on/off. (Full Settings is Phase 3; this slice adds the sync
  section so #7 is runnable.)
- Reading typography settings persist in `settings` table.

## Build (CMakeLists.txt)

Add to `ccx-reader`: `src/epub/epubdocument.cpp`, `src/epub/epubrender.cpp`, and any sync additions;
add `src/epub/epubview.{h,cpp}` to the shell target sources (QML_ELEMENT) with `src/epub` on the
include path (like `src/pdf`). Link `Qt6::CorePrivate` (QZipReader) and `Qt6::Gui` (already).
Host tests (`test_epub_text.cpp` golden built-text, `test_epub_parse.cpp` resolvePath + spine
numbering + spineWeights) build with Qt6Core (+ CorePrivate for QZipReader) — no GUI, no PDFium.

## Acceptance for this slice

On device: import an EPUB, open it, read with page turns; TOC jump; whole-book search jump; set a
bookmark and return; follow a footnote link and return; change font size/family/margins and see
reflow with the resume position preserved; close and reopen -> resumes at the same spot. Host golden
tests for built text pass. Then **acceptance test #7**: configure the same kosync server on the rM2,
the Android CipherCodex app, and a KOReader client; open the SAME EPUB on each; advance on one,
open on another -> the JUMP prompt offers the correct position; verify rM2<->Android land on the
same word (same s=;o=) and rM2<->KOReader land in the right chapter. (This step needs the owner's
kosync server + Android app + a KOReader client — coordinate the live run.)
