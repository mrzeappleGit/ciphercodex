#pragma once
#include <QElapsedTimer>
#include <QEvent>
#include <QObject>
#include <QQmlEngine>
#include <QRectF>

class QSocketNotifier;

// Reads the Wacom digitizer evdev stream directly; the epaper QPA does not
// deliver stylus events to Qt (verified on device, OS 3.27.3.0).
class PenReader : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    // Live state for the probe UI. near == pen in proximity -> gate touch (palm rejection).
    Q_PROPERTY(bool near READ near NOTIFY nearChanged)
    Q_PROPERTY(bool eraser READ eraser NOTIFY eraserChanged)
    Q_PROPERTY(qreal pressure READ pressure NOTIFY sampled)
    Q_PROPERTY(int tiltX READ tiltX NOTIFY sampled)
    Q_PROPERTY(int tiltY READ tiltY NOTIFY sampled)
    // Scene-coordinate ink region. Pen-down inside -> penDown/Move/Up signals; outside ->
    // synthesized left-button mouse events so the pen can press QML buttons.
    // Empty rect (default) = no canvas on screen: every pen-down becomes a mouse event.
    Q_PROPERTY(QRectF canvasRect READ canvasRect WRITE setCanvasRect NOTIFY canvasRectChanged)
    // ponytail: calibration knob for the digitizer->screen transform (0..7 = swap/flipX/flipY
    // bits). 1 verified correct on device 2026-07-11: screen_x = ry/RYMAX*W, screen_y = (1-rx/RXMAX)*H.
    Q_PROPERTY(int calib READ calib WRITE setCalib NOTIFY calibChanged)

public:
    explicit PenReader(QObject *parent = nullptr);

    bool near() const { return m_near; }
    bool eraser() const { return m_eraser; }
    qreal pressure() const { return m_pressure; }
    int tiltX() const { return m_tiltX; }
    int tiltY() const { return m_tiltY; }
    int calib() const { return m_calib; }
    void setCalib(int c);
    QRectF canvasRect() const { return m_canvasRect; }
    void setCanvasRect(const QRectF &r);

signals:
    // rawPressure 0-4095, tilt -9000..9000, tMs monotonic ms since app start.
    void penDown(qreal x, qreal y, qreal pressure, int rawPressure, int tiltX, int tiltY, quint32 tMs);
    void penMove(qreal x, qreal y, qreal pressure, int rawPressure, int tiltX, int tiltY, quint32 tMs);
    void penUp();
    void nearChanged();
    void eraserChanged();
    void sampled();
    void calibChanged();
    void canvasRectChanged();

private:
    void readEvents();
    QPointF toScreen(int rx, int ry) const;
    void synthMouse(const QPointF &pos, QEvent::Type type);

    QSocketNotifier *m_notifier = nullptr;
    int m_fd = -1;
    // raw state accumulated until SYN_REPORT
    int m_rx = 0, m_ry = 0, m_rp = 0;
    int m_tiltX = 0, m_tiltY = 0;
    bool m_near = false, m_eraser = false, m_touching = false;
    bool m_inkStroke = false; // latched at pen-down: inside canvasRect -> ink, outside -> mouse
    qreal m_pressure = 0;
    int m_calib = 1; // verified on device
    qreal m_screenW = 1404, m_screenH = 1872;
    QRectF m_canvasRect;
    QElapsedTimer m_clock;
};
