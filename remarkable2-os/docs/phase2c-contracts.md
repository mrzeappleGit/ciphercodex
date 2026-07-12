# Phase 2c contracts — Highlights + Kept (frozen)

Scope: text selection → highlight (with an optional note) in the EPUB reader, render highlights on
the page, manage them, and a cross-library **Kept** view grouped by book with Markdown export. This
reuses the Android highlight model exactly (the `highlights` table already exists in schema v2).
PDF text highlighting is a LATER follow-on (different anchor model: page + text rects) — this slice
is EPUB-only for highlights; the Kept view lists whatever highlights exist. Handwritten annotation
over docs remains slice 2d.

## Anchor model (matches Android, already in schema v2)

`highlights(id, book_id, spine_index, start_char, end_char, text, note, color_id, created_at)`.
`start_char`/`end_char` are a half-open `[start,end)` range into the chapter's BUILT text (the same
byte-identical offset space as progress/bookmarks), so a highlight survives typography/reflow. `text`
is a snapshot of the highlighted string (for Kept display without re-parsing). `color_id` is kept as
metadata but has NO visual meaning on the monochrome panel (render all highlights the same mono
treatment). Highlights are LOCAL-ONLY (kosync carries position only); they travel with the book if
the DB is later WebDAV-synced.

## Library additions — `src/library/library.{h,cpp}`

```cpp
struct Highlight { qint64 id, bookId; int spineIndex, startChar, endChar; QString text, note;
                   int colorId; qint64 createdAt; };
struct KeptHighlight { Highlight h; QString bookTitle, bookAuthor; int format; QString filePath; };
QVector<Highlight> highlights(qint64 bookId, int spineIndex = -1); // all, or one chapter; ORDER BY spine_index, start_char
qint64 addHighlight(qint64 bookId, int spineIndex, int startChar, int endChar,
                    const QString &text, const QString &note = QString(), int colorId = 0);
void updateHighlight(qint64 id, const QString &note, int colorId);
void deleteHighlight(qint64 id);
QVector<KeptHighlight> allHighlights();  // JOIN books; ORDER BY created_at DESC (Kept view)
```
All via the existing Stmt/Tx pattern. `deleteBook` FK-cascades highlights already.

## Controller additions — `src/readercontroller.{h,cpp}`

```cpp
Q_INVOKABLE QVariantList highlights(qint64 bookId, int spineIndex);        // chapter highlights for rendering
Q_INVOKABLE qint64 addHighlight(qint64 bookId, int spineIndex, int startChar, int endChar,
                                const QString &text, const QString &note);
Q_INVOKABLE void updateHighlight(qint64 id, const QString &note);          // colorId stays 0 (mono)
Q_INVOKABLE void deleteHighlight(qint64 id);
Q_INVOKABLE QVariantList keptHighlights();  // [{id,bookId,bookTitle,bookAuthor,format,filePath,
                                            //   spineIndex,startChar,endChar,text,note,createdAt}]
Q_INVOKABLE bool exportKeptMarkdown(const QString &outPath);  // grouped Markdown, returns ok
```
Markdown export = the Android format EXACTLY (so exports match across devices), grouped by the
`(title, author)` pair, newest-book-first is not required — group order by first appearance:
per group `# <title>[ — <author>]\n\n`, then per highlight `> <text with \n->space>\n`, and if the
note is non-blank `\n<note with \n->space>\n`, then a blank line. color_id is NOT serialized. Write
to `outPath` (default the app data dir, e.g. `/home/root/ciphercodex/kept.md`) atomically
(temp + fsync + rename).

## EpubRenderer additions — `src/epub/epubrender.{h,cpp}`

```cpp
// Built char offset under a view-space point on pageIndex (hit-test via documentLayout), or -1.
int offsetAtPoint(int pageIndex, const QPointF &pt, const QSizeF &size);
// Word [start,end) (built offsets) containing/nearest `builtOffset` (for tap/long-press select).
QPair<int,int> wordRangeAt(int builtOffset);
// View-space rectangles covering built range [start,end) on pageIndex (for drawing selection +
// saved highlights); empty if the range doesn't intersect the page. Uses the per-line layout.
QVector<QRectF> rectsForRange(int pageIndex, int startOffset, int endOffset, const QSizeF &size);
// Plain built text slice [start,end) (for the highlight snapshot + copy).
QString textSlice(int startOffset, int endOffset) const;
```

## EpubView additions — `src/epub/epubview.{h,cpp}`

Selection (built-offset based, chapter-local; word-granular is fine for e-ink):
```cpp
Q_PROPERTY(bool hasSelection ...)          // a committed selection exists
Q_PROPERTY(QString selectionText ...)
Q_INVOKABLE void selectWordAt(qreal x, qreal y);   // long-press: select the word under the point
Q_INVOKABLE void extendSelectionTo(qreal x, qreal y); // drag: extend to the word under the point
Q_INVOKABLE void clearSelection();
Q_INVOKABLE QVariantMap selectionAnchor();  // {spine,startChar,endChar,text} or {} if none
Q_INVOKABLE void copySelection();           // QClipboard setText(selectionText)
Q_INVOKABLE void reloadHighlights();        // controller-independent: EpubView asks for them? NO —
signals: void selectionChanged();
```
Highlight rendering: EpubView keeps a per-CURRENT-chapter list of highlight ranges (set by the reader
screen via `setChapterHighlights(QVariantList)` = [{id,startChar,endChar}]) and draws each as a mono
treatment (a light-gray underline band under the text rects from `rectsForRange`) UNDER the ink text,
plus the live selection as an inverted/gray box. Re-drawn on page turn / reflow. When the chapter
changes (spine turn), the reader reloads highlights for the new spine and calls
`setChapterHighlights`. Provide `Q_INVOKABLE QVariantList highlightAt(qreal x, qreal y)` -> the
highlight id(s) under a point (tap a highlight to edit/delete).

Interaction: a long-press (press-hold ~500ms without moving) starts a word selection; dragging
extends it; on release the reader shows a small selection toolbar (HIGHLIGHT / COPY / NOTE / X).
A plain tap still turns pages / follows links as today. Palm/pen: selection works with touch;
pen tap-to-turn unchanged.

## QML

- `EpubReaderScreen.qml` (edit): render highlights (EpubView draws them; the screen loads them per
  chapter via `reader.highlights(bookId, spine)` on spine change + after add/delete and calls
  `epubView.setChapterHighlights`). A selection toolbar (appears while `epubView.hasSelection`):
  HIGHLIGHT (addHighlight with the selection anchor + snapshot, then reload + clearSelection),
  COPY (epubView.copySelection), NOTE (opens a note editor -> addHighlight with the note), X (clear).
  Tapping an existing highlight (epubView.highlightAt) opens an edit sheet: show text, edit NOTE,
  DELETE. No color picker (mono). No dictionary/"definition" action (no offline dictionary — omit).
- `KeptScreen.qml` (new): the KEPT home tile opens it. List `reader.keptHighlights()` grouped by
  book (a book header row, then its highlights with the quoted text + note); tap a highlight ->
  open that book at (spineIndex, startChar) [EPUB] via the same push path BookDetail uses; an
  EXPORT MARKDOWN button (calls exportKeptMarkdown, shows the written path). Empty state when none.
  CipherCodex identity, >=90px targets.
- `HomeScreen.qml` (edit): KEPT tile navigates to KeptScreen (drop its "PHASE 3" tag), passing the
  reader controller. (KEPT was tagged PHASE 3 but we're doing it now as part of the reader.)

## Build

No new libs. Add `QtGui` QClipboard (already have Qt6::Gui). New QML file KeptScreen.qml to the
shell QML_FILES. Host test (`tests/test_highlights.cpp`): Library highlight CRUD + allHighlights
JOIN + the Markdown export string format (assert exact bytes for a known set, grouping by
title/author, note handling, \n->space). Selection/rendering is device-verified (needs GUI).

## Acceptance (device)

Open an EPUB, long-press to select a word, extend the selection, HIGHLIGHT it -> it renders as a mono
underline that survives page turns and a font-size change (reflow). Add a NOTE to a highlight; tap
the highlight to edit/delete. Open KEPT from home -> the highlight appears grouped under its book
with the note; tap it -> the book opens at that spot; EXPORT MARKDOWN writes a .md file with the
exact grouped format. Host highlight tests pass.
