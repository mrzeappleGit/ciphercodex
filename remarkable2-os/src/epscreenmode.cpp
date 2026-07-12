#include "epscreenmode.h"

#include <QMetaObject>
#include <QMetaProperty>

#include <cstring>
#include <dlfcn.h>

namespace epscreenmode {
namespace {

// Mangled symbols in libqsgepaper.so (verified via nm/probe on OS 3.27.3.0):
constexpr char kLib[]  = "/usr/lib/plugins/scenegraph/libqsgepaper.so";
constexpr char kCtor[] = "_ZN16EPScreenModeItemC1EP10QQuickItem"; // EPScreenModeItem(QQuickItem*)
constexpr char kMeta[] = "_ZN16EPScreenModeItem16staticMetaObjectE";
constexpr char kSet[]  = "_ZN16EPScreenModeItem7setModeENS_4ModeE"; // setMode(Mode)

using CtorFn = void (*)(void *self, QQuickItem *parent);
using SetFn  = void (*)(void *self, int mode);

struct Api {
    CtorFn ctor = nullptr;
    SetFn set = nullptr;
    const QMetaObject *mo = nullptr;
    bool tried = false;
};

Api &api()
{
    static Api a;
    if (a.tried)
        return a;
    a.tried = true;
    // Already in-process (we run with QT_QUICK_BACKEND=epaper); dlopen bumps the refcount
    // and hands back the existing handle. RTLD_NOLOAD avoids loading it if somehow absent.
    void *h = dlopen(kLib, RTLD_NOW | RTLD_NOLOAD);
    if (!h)
        h = dlopen(kLib, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        qWarning("epscreenmode: plugin not loadable (%s); falling back to default waveform",
                 dlerror());
        return a;
    }
    a.ctor = reinterpret_cast<CtorFn>(dlsym(h, kCtor));
    a.set = reinterpret_cast<SetFn>(dlsym(h, kSet));
    a.mo = reinterpret_cast<const QMetaObject *>(dlsym(h, kMeta));
    if (!a.ctor || !a.mo)
        qWarning("epscreenmode: EPScreenModeItem symbols missing; default waveform in use");
    return a;
}

} // namespace

QQuickItem *attachScreenMode(QQuickItem *canvas, int mode)
{
    if (!canvas)
        return nullptr;
    Api &a = api();
    if (!a.ctor || !a.mo)
        return nullptr;

    // EPScreenModeItem derives from QQuickItem; we don't know its exact sizeof, so
    // over-allocate wildly (object is heap-lived, parented, never arrayed) and construct
    // in place. QObject/QQuickItem is the first base -> pointer == storage, and Qt's
    // eventual `delete` pairs with this global operator new. 4 KB >> any plausible size.
    void *mem = ::operator new(4096);
    std::memset(mem, 0, 4096);
    a.ctor(mem, canvas);
    auto *item = reinterpret_cast<QQuickItem *>(mem); // first base is QQuickItem
    item->setParentItem(canvas);
    item->setSize(canvas->size());
    item->setZ(1); // in front so the opaque canvas can't occlusion-cull it; it draws nothing
    setMode(item, mode);
    return item;
}

void setMode(QQuickItem *item, int mode)
{
    if (!item)
        return;
    Api &a = api();
    if (a.set) {
        a.set(item, mode);
        return;
    }
    if (a.mo) { // fallback: write the 'mode' property through the metaobject
        const int idx = a.mo->indexOfProperty("mode");
        if (idx >= 0)
            a.mo->property(idx).write(item, mode);
    }
}

} // namespace epscreenmode
