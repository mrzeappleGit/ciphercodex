#include "powerbutton.h"

#include <QProcess>
#include <QSocketNotifier>
#include <fcntl.h>
#include <linux/input.h>
#include <unistd.h>

PowerButton::PowerButton(QObject *parent) : QObject(parent)
{
    const QByteArray dev = qgetenv("CCX_POWER_DEV").isEmpty()
        ? QByteArray("/dev/input/event0")   // snvs-powerkey per /proc/bus/input/devices
        : qgetenv("CCX_POWER_DEV");
    m_fd = open(dev.constData(), O_RDONLY | O_NONBLOCK);
    if (m_fd < 0) {
        qWarning("PowerButton: cannot open %s", dev.constData());
        return;
    }
    m_notifier = new QSocketNotifier(m_fd, QSocketNotifier::Read, this);
    connect(m_notifier, &QSocketNotifier::activated, this, &PowerButton::readEvents);
}

PowerButton::~PowerButton()
{
    if (m_fd >= 0)
        close(m_fd);
}

void PowerButton::readEvents()
{
    struct input_event ev;
    while (read(m_fd, &ev, sizeof(ev)) == sizeof(ev))
        if (ev.type == EV_KEY && ev.code == KEY_POWER && ev.value == 1)
            emit pressed();
}

void PowerButton::suspend()
{
    // Detached: this process freezes with the system moments later; never block the GUI
    // thread on systemctl. Wake sources (power key) are kernel-configured, untouched here.
    // A failed suspend leaves the face on an awake device; recoverable by tap/press, but
    // leave a trace for shell.log. (systemctl's own nonzero exit stays unobservable.)
    if (!QProcess::startDetached(QStringLiteral("systemctl"), {QStringLiteral("suspend")}))
        qWarning("PowerButton: failed to spawn systemctl suspend");
}
