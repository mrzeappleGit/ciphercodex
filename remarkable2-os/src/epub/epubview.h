#pragma once

#include <QHash>
#include <QImage>
#include <QList>
#include <QPair>
#include <QPointF>
#include <QQmlEngine>
#include <QQuickPaintedItem>
#include <QString>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>
#include <QVector>

#include <memory>

class EpubDocument;
class EpubRenderer;

// Full-page reflowable EPUB reader item. Owns an EpubDocument and a small LRU of
// per-chapter EpubRenderers; paints the current page (text via EpubRenderer, images via
// EpubDocument::imageBytes). Page turns cross chapter boundaries (prev from page 0 lands on
// the previous chapter's last page). Deliberately attaches NO EPScreenModeItem — reading
// wants a clean quality waveform. Pen arrives as synthesized mouse (canvasRect unset), so
// mouse handlers cover pen + touch. Mirrors PdfView's structure/paging/search-timer shape.
class EpubView : public QQuickPaintedItem
{
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(int spineIndex READ spineIndex NOTIFY spineChanged)
    Q_PROPERTY(int spineCount READ spineCount NOTIFY sourceChanged)
    Q_PROPERTY(int pageInSpine READ pageInSpine NOTIFY pageChanged)
    Q_PROPERTY(int pagesInSpine READ pagesInSpine NOTIFY pageChanged)
    Q_PROPERTY(double percentage READ percentage NOTIFY percentageChanged)
    Q_PROPERTY(bool canReturn READ canReturn NOTIFY canReturnChanged)
    Q_PROPERTY(bool hasSelection READ hasSelection NOTIFY selectionChanged)
    Q_PROPERTY(QString selectionText READ selectionText NOTIFY selectionChanged)

public:
    explicit EpubView(QQuickItem *parent = nullptr);
    ~EpubView() override;

    void paint(QPainter *painter) override;

    int spineIndex() const { return m_spineIndex; }
    int spineCount() const;
    int pageInSpine() const { return m_pageInSpine; }
    int pagesInSpine();
    double percentage() const { return m_percentage; }
    bool canReturn() const { return m_canReturn; }
    bool hasSelection() const { return m_hasSelection; }
    QString selectionText() const { return m_selText; }

    Q_INVOKABLE bool openDocument(const QString &path);
    Q_INVOKABLE void next();
    Q_INVOKABLE void prev();
    Q_INVOKABLE void goToLocation(int spine, int charOffset);
    Q_INVOKABLE void goToSpine(int spine) { goToLocation(spine, 0); }
    Q_INVOKABLE void setTypography(const QString &family, int bodyPx, double lineSpacing,
                                   int marginPx, bool justify);
    // [{title, spine}] from the open document's TOC (nav -> NCX -> per-chapter fallback).
    Q_INVOKABLE QVariantList toc();
    // Follow an internal link href. Short same-chapter targets return {footnote:true,text,...}
    // WITHOUT moving; anything else pushes the return stack and jumps. External/unresolvable
    // hrefs return {ok:false}. Map: {ok,footnote,text,spine,offset}.
    Q_INVOKABLE QVariantMap follow(const QString &href);
    Q_INVOKABLE void back();  // pop the return stack (undo the last jump)
    // Chunked, cancelable whole-book search on the GUI event loop (one chapter per tick):
    // emits searchHit per match, then searchFinished. Never blocks the UI.
    Q_INVOKABLE void startSearch(const QString &query);
    Q_INVOKABLE void cancelSearch() { cancelSearch(true); }
    void cancelSearch(bool emitSignal);  // internal supersede uses emitSignal=false (silent)

    // Text selection (chapter-local, built-offset, word-granular for e-ink). A long-press starts
    // it, drag extends; the reader screen drives its toolbar off hasSelection/selectionText.
    Q_INVOKABLE void selectWordAt(qreal x, qreal y);
    Q_INVOKABLE void extendSelectionTo(qreal x, qreal y);
    Q_INVOKABLE void clearSelection();
    Q_INVOKABLE QVariantMap selectionAnchor();  // {spine,startChar,endChar,text} or {} if none
    Q_INVOKABLE void copySelection();           // built text -> QClipboard
    // Saved highlights for the CURRENT chapter, drawn as a mono underline band under the text.
    // list = [{id,startChar,endChar}]; the reader re-sets it on spine change / after add+delete.
    Q_INVOKABLE void setChapterHighlights(const QVariantList &highlights);
    // Highlight id(s) under a view-space point (tap a highlight to edit/delete). Newest-set last.
    Q_INVOKABLE QVariantList highlightAt(qreal x, qreal y);

signals:
    void sourceChanged();
    void spineChanged();
    void pageChanged();
    void percentageChanged();
    void canReturnChanged();
    // Emitted on every page turn: spine + built char offset of the page's first char, so the
    // controller can save + push progress (kosync `s=;o=`).
    void locationChanged(int spine, int charOffset);
    // A tapped internal link in the reading area; QML decides (footnote popup vs jump) via follow().
    void linkActivated(const QString &href);
    void searchHit(int spine, int charOffset, const QString &snippet);
    void searchFinished(bool canceled, bool truncated);  // truncated: hit the result cap
    void selectionChanged();  // committed selection appeared / grew / cleared
    void highlightTapped(qint64 id);  // tap landed on a saved highlight (open its edit sheet)

protected:
    void mousePressEvent(QMouseEvent *e) override;
    void mouseMoveEvent(QMouseEvent *e) override;
    void mouseReleaseEvent(QMouseEvent *e) override;
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;

private:
    EpubRenderer *rendererFor(int spine);   // cached, laid out at the current typography/viewport
    EpubRenderer *currentRenderer() { return m_doc ? rendererFor(m_spineIndex) : nullptr; }
    void emitTurn(bool emitLocation);       // recompute pct + notify after a page/spine change
    void reflowKeepingPosition();           // typography/geometry change: re-paginate, keep offset
    void pushReturn();
    void updateCanReturn();
    void searchStep();
    QString snippetFor(const QString &text, int matchStart, int matchLen) const;
    void onLongPress();              // press-hold fired: start a word selection at the press point
    void clearChapterAnnotations();  // spine change: drop this chapter's highlights + selection

    EpubDocument *m_doc = nullptr;
    int m_spineIndex = 0;
    int m_pageInSpine = 0;
    double m_percentage = 0;
    bool m_canReturn = false;

    // Typography (mirrors settings; applied to every renderer). Defaults are reading-sane.
    QString m_family = QStringLiteral("Serif");
    int m_bodyPx = 22;
    double m_lineSpacing = 1.4;
    int m_marginPx = 40;
    QImage m_imgCache;        // decoded+scaled standalone-image page, keyed by path+size
    QString m_imgCachePath;
    bool m_justify = false;

    QHash<int, std::shared_ptr<EpubRenderer>> m_renderers;  // spine -> renderer
    QList<int> m_rendererOrder;                             // MRU front

    QVector<QPair<int, int>> m_returnStack;  // (spine, offset) undo stack for jumps

    QPointF m_pressPos;
    QPointF m_lastPos;
    bool m_moved = false;
    QTimer m_pressTimer;  // long-press detector (word-selection start)

    // Selection state (chapter-local; built offsets survive reflow, so no clear on typography/size).
    struct Hl {
        qint64 id;
        int start, end;
    };
    QVector<Hl> m_highlights;                       // current-chapter saved highlights
    bool m_hasSelection = false;
    bool m_selecting = false;                       // live long-press drag (suppresses tap/turn)
    int m_selSpine = 0;
    int m_selAnchorStart = 0, m_selAnchorEnd = 0;   // the first word (drag pivot)
    int m_selStart = 0, m_selEnd = 0;               // committed [start,end) built offsets
    QString m_selText;

    QTimer m_searchTimer;
    QString m_searchQuery;
    int m_searchSpine = 0;
    int m_searchHits = 0;
};
