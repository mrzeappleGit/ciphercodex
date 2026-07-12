#include "penreader.h"

#include <QGuiApplication>
#include <QScreen>
#include <QSocketNotifier>
#include <fcntl.h>
#include <linux/input.h>
#include <qpa/qwindowsysteminterface.h>
#include <unistd.h>

// Measured on device 2026-07-11 (input-probe, OS 3.27.3.0):
static constexpr int WACOM_X_MAX = 20966;
static constexpr int WACOM_Y_MAX = 15725;
static constexpr int WACOM_P_MAX = 4095;

PenReader::PenReader(QObject *parent) : QObject(parent)
{
    m_clock.start();
    if (const QScreen *s = QGuiApplication::primaryScreen()) {
        m_screenW = s->size().width();
        m_screenH = s->size().height();
    }
    const QByteArray dev = qgetenv("CCX_PEN_DEV").isEmpty() ? QByteArray("/dev/input/event1")
                                                            : qgetenv("CCX_PEN_DEV");
    if (const QByteArray c = qgetenv("CCX_PEN_CALIB"); !c.isEmpty())
        m_calib = c.toInt();
    m_fd = open(dev.constData(), O_RDONLY | O_NONBLOCK);
    if (m_fd < 0) {
        qWarning("PenReader: cannot open %s", dev.constData());
        return;
    }
    m_notifier = new QSocketNotifier(m_fd, QSocketNotifier::Read, this);
    connect(m_notifier, &QSocketNotifier::activated, this, &PenReader::readEvents);
}

void PenReader::setCalib(int c)
{
    if (c == m_calib) return;
    m_calib = c;
    emit calibChanged();
}

void PenReader::setCanvasRect(const QRectF &r)
{
    if (r == m_canvasRect) return;
    m_canvasRect = r;
    emit canvasRectChanged();
}

QPointF PenReader::toScreen(int rx, int ry) const
{
    qreal nx = qreal(rx) / WACOM_X_MAX; // along the panel's long axis
    qreal ny = qreal(ry) / WACOM_Y_MAX;
    if (m_calib & 1) nx = 1.0 - nx;
    if (m_calib & 2) ny = 1.0 - ny;
    return (m_calib & 4) ? QPointF(nx * m_screenW, ny * m_screenH)
                         : QPointF(ny * m_screenW, nx * m_screenH);
}

void PenReader::synthMouse(const QPointF &pos, QEvent::Type type)
{
    // Raw-evdev pen bypasses Qt input entirely; forge a left-button mouse event so the
    // Marker can press QML buttons. Fullscreen window: local == global coordinates.
    const Qt::MouseButtons buttons =
        (type == QEvent::MouseButtonRelease) ? Qt::NoButton : Qt::LeftButton;
    QWindowSystemInterface::handleMouseEvent(QGuiApplication::focusWindow(), pos, pos,
                                             buttons, Qt::LeftButton, type);
}

void PenReader::readEvents()
{
    struct input_event ev;
    while (read(m_fd, &ev, sizeof(ev)) == sizeof(ev)) {
        switch (ev.type) {
        case EV_ABS:
            switch (ev.code) {
            case ABS_X: m_rx = ev.value; break;
            case ABS_Y: m_ry = ev.value; break;
            case ABS_PRESSURE: m_rp = ev.value; break;
            case ABS_TILT_X: m_tiltX = ev.value; break;
            case ABS_TILT_Y: m_tiltY = ev.value; break;
            }
            break;
        case EV_KEY:
            switch (ev.code) {
            case BTN_TOOL_PEN:
                if (m_near != (ev.value != 0)) { m_near = ev.value; emit nearChanged(); }
                if (m_eraser) { m_eraser = false; emit eraserChanged(); }
                break;
            case BTN_TOOL_RUBBER:
                if (m_near != (ev.value != 0)) { m_near = ev.value; emit nearChanged(); }
                if (m_eraser != (ev.value != 0)) { m_eraser = ev.value; emit eraserChanged(); }
                break;
            }
            break;
        case EV_SYN: {
            const bool touching = m_rp > 0;
            m_pressure = qreal(m_rp) / WACOM_P_MAX;
            const QPointF pos = toScreen(m_rx, m_ry);
            const quint32 t = quint32(m_clock.elapsed());
            if (touching && !m_touching) {
                // empty rect = NO canvas on screen: synthesize mouse everywhere so the pen
                // can press buttons on Home/NotebookList (contract: all buttons pen-usable)
                m_inkStroke = !m_canvasRect.isEmpty() && m_canvasRect.contains(pos);
                if (m_inkStroke)
                    emit penDown(pos.x(), pos.y(), m_pressure, m_rp, m_tiltX, m_tiltY, t);
                else
                    synthMouse(pos, QEvent::MouseButtonPress);
            } else if (touching) {
                if (m_inkStroke)
                    emit penMove(pos.x(), pos.y(), m_pressure, m_rp, m_tiltX, m_tiltY, t);
                else
                    synthMouse(pos, QEvent::MouseMove);
            } else if (m_touching) {
                if (m_inkStroke)
                    emit penUp();
                else
                    synthMouse(pos, QEvent::MouseButtonRelease);
            }
            m_touching = touching;
            emit sampled();
            break;
        }
        }
    }
}
