#pragma once
#include <QObject>
#include <QQmlEngine>

class QSocketNotifier;

// KEY_POWER from the snvs power-key evdev device (event0). The stock OS ships
// logind HandlePowerKey=ignore (that's how xochitl owns the key), so it is ours to read.
// Also owns the suspend call so power concerns live in one place.
class PowerButton : public QObject
{
    Q_OBJECT
    QML_ELEMENT

public:
    explicit PowerButton(QObject *parent = nullptr);
    ~PowerButton() override;

    // systemd suspend; returns once the request is queued (the freeze lands moments later).
    Q_INVOKABLE void suspend();

signals:
    void pressed();

private:
    void readEvents();

    int m_fd = -1;
    QSocketNotifier *m_notifier = nullptr;
};
