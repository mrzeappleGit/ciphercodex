#pragma once

#include <QPointF>
#include <QQmlEngine>
#include <QQuickPaintedItem>
#include <QString>
#include <QTimer>
#include <QVariantList>

class PdfDocument;

// Full-page PDF reader item. Renders the current page (grayscale, fit-width/fit-page,
// zoom, pan-when-zoomed). Page turns via L/R tap-thirds and horizontal swipe; each turn
// is a single whole-item repaint (reading wants quality — no fast pen waveform, and this
// item deliberately does NOT attach an EPScreenModeItem). Pen arrives as synthesized
// mouse (canvasRect unset), so mouse handlers cover pen + touch.
class PdfView : public QQuickPaintedItem
{
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(QString source READ source WRITE setSource NOTIFY sourceChanged)
    Q_PROPERTY(int pageIndex READ pageIndex NOTIFY pageChanged)
    Q_PROPERTY(int pageCount READ pageCount NOTIFY pageCountChanged)
    Q_PROPERTY(FitMode fitMode READ fitMode WRITE setFitMode NOTIFY fitModeChanged)
    Q_PROPERTY(double zoom READ zoom WRITE setZoom NOTIFY zoomChanged)

public:
    enum FitMode { FitWidth, FitPage };
    Q_ENUM(FitMode)

    explicit PdfView(QQuickItem *parent = nullptr);
    ~PdfView() override;

    void paint(QPainter *painter) override;

    QString source() const { return m_source; }
    void setSource(const QString &path);
    int pageIndex() const { return m_pageIndex; }
    int pageCount() const { return m_pageCount; }
    FitMode fitMode() const { return m_fitMode; }
    void setFitMode(FitMode m);
    double zoom() const { return m_zoom; }
    void setZoom(double z);

    Q_INVOKABLE bool openDocument(const QString &path);
    Q_INVOKABLE void next() { goToPage(m_pageIndex + 1); }
    Q_INVOKABLE void prev() { goToPage(m_pageIndex - 1); }
    Q_INVOKABLE void goToPage(int page);
    Q_INVOKABLE void setFit(int mode) { setFitMode(FitMode(mode)); }
    // Table of contents from the already-open document (no reopen). [{title,page,level}]
    Q_INVOKABLE QVariantList outline() const;
    // Chunked, cancelable whole-document text search on the GUI event loop: emits searchHit
    // per matching page as it scans, then searchFinished. Never blocks the UI.
    Q_INVOKABLE void startSearch(const QString &query);
    Q_INVOKABLE void cancelSearch();

signals:
    void sourceChanged();
    void pageChanged();
    void pageCountChanged();
    void fitModeChanged();
    void zoomChanged();
    void searchHit(int page, int count);
    void searchFinished(int totalPages, bool canceled);

protected:
    void mousePressEvent(QMouseEvent *e) override;
    void mouseMoveEvent(QMouseEvent *e) override;
    void mouseReleaseEvent(QMouseEvent *e) override;
    void geometryChange(const QRectF &newGeometry, const QRectF &oldGeometry) override;

private:
    // Displayed page rect in item pixels for the current page/fit/zoom/pan.
    QRectF displayRect() const;
    void clampPan(const QRectF &rect);  // keep content edges from drifting inside the viewport

    PdfDocument *m_doc = nullptr;
    QString m_source;
    int m_pageIndex = 0;
    int m_pageCount = 0;
    FitMode m_fitMode = FitWidth;
    double m_zoom = 1.0;
    QPointF m_pan;  // offset added to the centered page position

    QPointF m_pressPos;
    QPointF m_lastPos;
    bool m_moved = false;  // drag exceeded the tap threshold

    // Chunked search state (driven by a zero-interval timer, a few pages per tick).
    QTimer m_searchTimer;
    QString m_searchQuery;
    int m_searchPage = 0;
    void searchStep();
};
