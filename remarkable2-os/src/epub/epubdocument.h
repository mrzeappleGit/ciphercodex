#pragma once

#include <QByteArray>
#include <QHash>
#include <QList>
#include <QPair>
#include <QString>
#include <QVector>

#include <functional>

// Port of the Android EPUB text pipeline (XhtmlMapper + buildChapterText + EpubParser).
// The ONE hard rule: BuiltChapter.text is byte-identical to the Android app's
// buildChapterText output, because the cross-device kosync `o=<char_offset>` indexes
// into it. See docs/phase2b-contracts.md and the Kotlin sources it names.

struct EpubTocEntry {
    QString title;
    int spineIndex;
};

struct EpubMeta {
    QString title, author, language, description;  // empty string = absent
};

struct LinkTarget {
    int spineIndex;
    QString anchor;  // empty when the href had no "#id"
    bool ok;         // false = target outside the book (external URL / unresolvable)
};

// One built chapter: the offset space + render inputs. `text` is byte-identical to
// Android's buildChapterText output; every char offset indexes into it.
struct BuiltChapter {
    int spineIndex = 0;
    QString text;                                  // '\n' separators, U+FFFC images, "* * *" rules
    QVector<QPair<int, int>> blockRanges;          // per-block [start,end) into text
    QVector<int> blockKinds;                       // 0 para, 1 heading, 2 rule, 3 image
    QVector<int> headingLevels;                    // level per block (0 if not heading)
    QVector<QPair<QPair<int, int>, int>> spans;    // ((start,end), styleBit): bold1 italic2 underline4 sub8 sup16
    QVector<QPair<QPair<int, int>, QString>> links;  // ((start,end), href)
    QVector<QPair<int, QString>> images;           // (charIndex of U+FFFC, zipPath)
    QHash<QString, int> anchors;                   // id -> block index
    int charCount = 0;                             // sum of block textLengths (Rule=1, Image=1)
};

class QZipReader;

// EPUB reader: container.xml -> OPF parsed eagerly on open(); chapters parsed lazily
// (LRU of a few). Not thread-safe; use one instance on one thread (mirrors Android's
// per-instance lock intent — the reader is single-threaded on device).
class EpubDocument {
public:
    static EpubDocument *open(const QString &path, QString *err);  // nullptr + *err on failure
    ~EpubDocument();
    EpubDocument(const EpubDocument &) = delete;
    EpubDocument &operator=(const EpubDocument &) = delete;

    EpubMeta metadata() const { return m_meta; }
    int spineCount() const { return int(m_spinePaths.size()); }
    QVector<EpubTocEntry> toc() const { return m_toc; }
    QVector<qint64> spineWeights() const { return m_spineWeights; }
    BuiltChapter chapter(int spineIndex);              // lazy parse + build; internal LRU
    QByteArray coverImageBytes();
    QByteArray imageBytes(const QString &zipPath);     // empty on missing; never throws
    LinkTarget resolveLink(int fromSpineIndex, const QString &href);

private:
    EpubDocument() = default;

    QString entryFilePath(const QString &normPath) const;  // "" if absent
    bool hasEntry(const QString &normPath) const { return !entryFilePath(normPath).isEmpty(); }
    QByteArray entryBytes(const QString &normPath) const;  // "" if absent/unreadable
    qint64 entrySize(const QString &normPath) const;       // -1 if absent

    QZipReader *m_zip = nullptr;
    EpubMeta m_meta;
    QVector<QString> m_spinePaths;
    QVector<qint64> m_spineWeights;
    QVector<EpubTocEntry> m_toc;
    QString m_coverPath;

    // Normalized zip index (name + lowercase name -> raw filePath / size), first wins.
    QHash<QString, QString> m_byName, m_byLower;
    QHash<QString, qint64> m_sizeByName, m_sizeByLower;
    QHash<QString, int> m_spineLookup;  // spine path + lowercase -> spine index

    QHash<int, BuiltChapter> m_cache;   // LRU
    QList<int> m_cacheOrder;            // MRU front
};

namespace ccx::epub {

// Testable seam: raw XHTML bytes -> BuiltChapter, no zip needed. `resolveImage` maps an
// img/image href to a resolved zip path, or returns an empty QString to drop the image
// (mirrors Android's resolveImage: { null } default). The built text is byte-identical to
// Android's XhtmlMapper.parse + buildChapterText on the same bytes.
BuiltChapter buildChapterFromXhtml(const QByteArray &xhtml, int spineIndex,
                                   const std::function<QString(const QString &)> &resolveImage = {});

// Exposed for tests: path resolution identical to Android EpubParser.resolvePath.
QString resolvePath(const QString &baseDir, const QString &href);

}  // namespace ccx::epub
