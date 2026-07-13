#include <QFontDatabase>
#include <QGuiApplication>
#include <QMetaEnum>
#include <QMetaObject>
#include <QQmlApplicationEngine>

#include <dlfcn.h>
#include <cstdio>

// CCX_PROBE_SCREENMODE: read the closed epaper plugin's EPScreenModeItem::Mode enum from its
// exported metaobject and exit. Robust discovery of the mode integers before we depend on them.
static int probeScreenMode()
{
    void *h = dlopen("/usr/lib/plugins/scenegraph/libqsgepaper.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); return 1; }
    auto *mo = reinterpret_cast<const QMetaObject *>(
        dlsym(h, "_ZN16EPScreenModeItem16staticMetaObjectE"));
    if (!mo) { fprintf(stderr, "no staticMetaObject symbol: %s\n", dlerror()); return 1; }
    printf("class: %s\n", mo->className());
    for (int i = 0; i < mo->enumeratorCount(); ++i) {
        const QMetaEnum e = mo->enumerator(i);
        printf("enum %s:\n", e.name());
        for (int k = 0; k < e.keyCount(); ++k)
            printf("  %s = %d\n", e.key(k), e.value(k));
    }
    printf("properties:\n");
    for (int i = mo->propertyOffset(); i < mo->propertyCount(); ++i)
        printf("  %s : %s\n", mo->property(i).name(), mo->property(i).typeName());
    return 0;
}

int main(int argc, char *argv[])
{
    if (qEnvironmentVariableIsSet("CCX_PROBE_SCREENMODE"))
        return probeScreenMode();

    QGuiApplication app(argc, argv);

    // Design fonts (embedded, OFL): Rajdhani display, Share Tech Mono captions, Courier
    // Prime reading. The device's own fonts stay as fallback for glyphs these lack.
    for (const char *f : {"Rajdhani-Bold.ttf", "Rajdhani-SemiBold.ttf", "Rajdhani-Medium.ttf",
                          "ShareTechMono-Regular.ttf", "CourierPrime-Regular.ttf",
                          "CourierPrime-Bold.ttf", "CourierPrime-Italic.ttf"})
        QFontDatabase::addApplicationFont(
            QStringLiteral(":/qt/qml/CipherCodex/assets/fonts/") + QLatin1String(f));

    QQmlApplicationEngine engine;
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed,
                     &app, []() { QCoreApplication::exit(1); },
                     Qt::QueuedConnection);
    engine.loadFromModule("CipherCodex", "Main");
    return app.exec();
}
