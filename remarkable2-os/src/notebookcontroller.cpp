#include "notebookcontroller.h"

#include <QMarginsF>
#include <QPageSize>
#include <QPainter>
#include <QPdfWriter>
#include <QPen>

static constexpr int UNDO_CAP = 100;
// Retained-point bound across the undo stack: ~500k points ≈ 15 MB worst case in RAM.
// Op count alone is unbounded in bytes (one broad erase can hold MBs of copies).
static constexpr qint64 UNDO_POINT_CAP = 500000;
static constexpr qreal PDF_W = 1404.0, PDF_H = 1872.0; // points, matches panel portrait

NotebookController::NotebookController(QObject *parent) : QObject(parent)
{
    const QString dir = qEnvironmentVariable("CCX_DATA_DIR", QStringLiteral("/home/root/ciphercodex"));
    QString err;
    m_storage = Storage::open(dir, &err);
    if (!m_storage)
        qWarning("NotebookController: storage open failed: %s", qUtf8Printable(err));
}

NotebookController::~NotebookController()
{
    delete m_storage;
}

QVariantList NotebookController::notebooks()
{
    QVariantList out;
    if (!m_storage)
        return out;
    for (const NotebookInfo &n : m_storage->notebooks())
        out.append(QVariantMap{{QStringLiteral("id"), n.id},
                               {QStringLiteral("title"), n.title},
                               {QStringLiteral("pageCount"), n.pageCount}});
    return out;
}

qint64 NotebookController::createNotebook(const QString &title)
{
    return m_storage ? m_storage->createNotebook(title) : -1;
}

void NotebookController::deleteNotebook(qint64 id)
{
    if (m_storage)
        m_storage->deleteNotebook(id);
}

QVariantList NotebookController::pages(qint64 notebookId)
{
    QVariantList out;
    if (!m_storage)
        return out;
    for (const PageInfo &p : m_storage->pages(notebookId))
        out.append(QVariantMap{{QStringLiteral("id"), p.id}, {QStringLiteral("seq"), p.seq}});
    return out;
}

qint64 NotebookController::createPage(qint64 notebookId)
{
    return m_storage ? m_storage->createPage(notebookId) : -1;
}

void NotebookController::openPage(qint64 pageId, InkItem *canvas, PenReader *pen)
{
    if (!m_storage || !canvas)
        return;
    if (m_canvas) // QPointer: null if StackView already destroyed the old page's canvas
        m_canvas->disconnect(this);
    if (m_pen && m_canvas) {
        disconnect(m_pen, nullptr, m_canvas, nullptr);
    }
    m_canvas = canvas;
    m_pen = pen;
    m_pageId = pageId;
    m_undo.clear();
    m_redo.clear();
    m_undoPoints = 0;
    emit undoChanged();
    connect(canvas, &InkItem::strokeFinished, this, &NotebookController::onStrokeFinished,
            Qt::UniqueConnection);
    connect(canvas, &InkItem::strokesErased, this, &NotebookController::onStrokesErased,
            Qt::UniqueConnection);
    if (pen) {
        // direct C++ hot path: no JS trampoline at 200 Hz, all 7 args intact
        connect(pen, &PenReader::penDown, canvas, &InkItem::penDown, Qt::UniqueConnection);
        connect(pen, &PenReader::penMove, canvas, &InkItem::penMove, Qt::UniqueConnection);
        connect(pen, &PenReader::penUp, canvas, &InkItem::penUp, Qt::UniqueConnection);
    }
    canvas->setStrokes(m_storage->strokes(pageId));
}

void NotebookController::resyncCanvas()
{
    if (m_canvas && m_storage && m_pageId >= 0)
        m_canvas->setStrokes(m_storage->strokes(m_pageId));
}

void NotebookController::onStrokeFinished(const StrokeData &s)
{
    if (!m_storage || m_pageId < 0 || !m_canvas)
        return;
    StrokeData stored = s;
    stored.pageId = m_pageId;
    stored.id = m_storage->appendStroke(stored); // THE journal write: committed before undo knows
    if (stored.id < 0) {
        // insert failed (disk full / IO error): visible ink would be a lie — drop it
        qWarning("stroke append failed; resyncing canvas from DB");
        resyncCanvas();
        return;
    }
    m_canvas->commitStroke(stored);
    pushUndo({Op::Add, {stored}});
}

void NotebookController::onStrokesErased(const QVector<qint64> &ids)
{
    if (!m_storage || !m_canvas)
        return;
    const QVector<StrokeData> copies = m_canvas->strokesById(ids); // grab before removal, undo needs them
    if (!m_storage->removeStrokes(ids)) {
        qWarning("stroke erase failed; resyncing canvas from DB");
        resyncCanvas(); // also clears the 50%-gray pending-erase paint
        return;
    }
    m_canvas->removeStrokes(ids);
    pushUndo({Op::Erase, copies});
}

qint64 NotebookController::opPoints(const Op &op) const
{
    qint64 n = 0;
    for (const StrokeData &s : op.strokes)
        n += s.pts.size();
    return n;
}

void NotebookController::pushUndo(Op op)
{
    m_undoPoints += opPoints(op);
    m_undo.append(std::move(op));
    while (m_undo.size() > UNDO_CAP || (m_undoPoints > UNDO_POINT_CAP && m_undo.size() > 1)) {
        m_undoPoints -= opPoints(m_undo.first());
        m_undo.removeFirst();
    }
    m_redo.clear();
    emit undoChanged();
}

bool NotebookController::apply(const Op &op, bool undoing)
{
    // undo Add == redo Erase == remove; undo Erase == redo Add == restore.
    // Canvas is touched only after storage committed — screen never diverges from DB.
    if ((op.type == Op::Add) == undoing) {
        QVector<qint64> ids;
        ids.reserve(op.strokes.size());
        for (const StrokeData &s : op.strokes)
            ids.append(s.id);
        if (!m_storage->removeStrokes(ids)) {
            resyncCanvas();
            return false;
        }
        if (m_canvas)
            m_canvas->removeStrokes(ids);
    } else {
        QVector<StrokeData> strokes = op.strokes;
        if (!m_storage->restoreStrokes(strokes)) { // re-inserts with original ids
            resyncCanvas();
            return false;
        }
        if (m_canvas)
            m_canvas->addStrokes(strokes);
    }
    return true;
}

void NotebookController::undo()
{
    if (!m_storage || m_undo.isEmpty())
        return;
    Op op = m_undo.takeLast();
    m_undoPoints -= opPoints(op);
    if (apply(op, true))
        m_redo.append(std::move(op));
    emit undoChanged();
}

void NotebookController::redo()
{
    if (!m_storage || m_redo.isEmpty())
        return;
    Op op = m_redo.takeLast();
    if (apply(op, false)) {
        m_undoPoints += opPoints(op);
        m_undo.append(std::move(op));
    }
    emit undoChanged();
}

bool NotebookController::exportNotebookPdf(qint64 notebookId, const QString &outPath)
{
    if (!m_storage)
        return false;
    const QVector<PageInfo> pageList = m_storage->pages(notebookId);
    if (pageList.isEmpty())
        return false;
    QPdfWriter writer(outPath);
    writer.setResolution(72); // 1 painter unit == 1 pt
    writer.setPageSize(QPageSize(QSizeF(PDF_W, PDF_H), QPageSize::Point));
    writer.setPageMargins(QMarginsF(0, 0, 0, 0));
    QPainter p;
    if (!p.begin(&writer))
        return false;
    p.setRenderHint(QPainter::Antialiasing, true);
    bool first = true;
    for (const PageInfo &page : pageList) {
        if (!first)
            writer.newPage();
        first = false;
        for (const StrokeData &s : m_storage->strokes(page.id)) { // one page in memory at a time
            if (s.pts.isEmpty())
                continue;
            QPointF prev(s.pts[0].x * PDF_W, s.pts[0].y * PDF_H);
            for (int i = 0; i < s.pts.size(); ++i) {
                const QPointF cur(s.pts[i].x * PDF_W, s.pts[i].y * PDF_H);
                const qreal pr = s.pts[i].pressure / 4095.0;
                p.setPen(QPen(Qt::black, 1.0 + pr * pr * 10.0,
                              Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
                p.drawLine(prev, cur);
                prev = cur;
            }
        }
    }
    p.end();
    return true;
}
