#pragma once
#include <QElapsedTimer>
#include <QObject>
#include <QQmlEngine>
#include <QTimer>

// App-wide idle detector -> auto-sleep. Qt input events (touch, synthesized pen-on-chrome,
// keys) reset it via an application event filter; raw ink strokes bypass Qt input entirely,
// so Main.qml pokes it from PenReader's per-stroke signals (never per-sample — 200Hz).
// CCX_IDLE_MIN env (minutes) is a dev override that locks out the settings value; 0 disables.
class IdleWatch : public QObject
{
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(int timeoutMinutes READ timeoutMinutes WRITE setTimeoutMinutes
               NOTIFY timeoutMinutesChanged)

public:
    explicit IdleWatch(QObject *parent = nullptr);

    int timeoutMinutes() const { return m_minutes; }
    void setTimeoutMinutes(int m);
    Q_INVOKABLE void poke();

    bool eventFilter(QObject *obj, QEvent *ev) override;

signals:
    void idleTimeout();
    void timeoutMinutesChanged();

private:
    void restart();

    QTimer m_timer;
    QElapsedTimer m_sincePoke;
    int m_minutes = 10;
    bool m_locked = false;
};
