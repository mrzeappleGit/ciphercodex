#include "idlewatch.h"

#include <QEvent>
#include <QGuiApplication>

IdleWatch::IdleWatch(QObject *parent) : QObject(parent)
{
    if (const int v = qEnvironmentVariableIntValue("CCX_IDLE_MIN"); v > 0) {
        m_minutes = v;
        m_locked = true;  // dev override beats the settings-table value
    }
    m_timer.setSingleShot(true);
    connect(&m_timer, &QTimer::timeout, this, &IdleWatch::idleTimeout);
    m_sincePoke.start();
    qGuiApp->installEventFilter(this);
    restart();
}

void IdleWatch::setTimeoutMinutes(int m)
{
    if (m_locked || m == m_minutes)
        return;
    m_minutes = m;
    emit timeoutMinutesChanged();
    restart();
}

void IdleWatch::restart()
{
    if (m_minutes > 0)
        m_timer.start(m_minutes * 60 * 1000);
    else
        m_timer.stop();
}

void IdleWatch::poke()
{
    // Touch-moves flood at input rate; one timer restart per second is plenty.
    if (m_sincePoke.elapsed() < 1000)
        return;
    m_sincePoke.restart();
    restart();
}

bool IdleWatch::eventFilter(QObject *obj, QEvent *ev)
{
    switch (ev->type()) {
    case QEvent::MouseButtonPress:
    case QEvent::MouseMove:
    case QEvent::MouseButtonRelease:
    case QEvent::TouchBegin:
    case QEvent::TouchUpdate:
    case QEvent::TouchEnd:
    case QEvent::KeyPress:
    case QEvent::Wheel:
        poke();
        break;
    default:
        break;
    }
    return QObject::eventFilter(obj, ev);  // observe only, never consume
}
