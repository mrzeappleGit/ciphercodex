/****************************************************************************
** Meta object code from reading C++ file 'notebookcontroller.h'
**
** Created by: The Qt Meta Object Compiler version 68 (Qt 6.8.2)
**
** WARNING! All changes made in this file will be lost!
*****************************************************************************/

#include "../../../src/notebookcontroller.h"
#include <QtCore/qmetatype.h>

#include <QtCore/qtmochelpers.h>

#include <memory>


#include <QtCore/qxptype_traits.h>
#if !defined(Q_MOC_OUTPUT_REVISION)
#error "The header file 'notebookcontroller.h' doesn't include <QObject>."
#elif Q_MOC_OUTPUT_REVISION != 68
#error "This file was generated using the moc from 6.8.2. It"
#error "cannot be used with the include files from this version of Qt."
#error "(The moc has changed too much.)"
#endif

#ifndef Q_CONSTINIT
#define Q_CONSTINIT
#endif

QT_WARNING_PUSH
QT_WARNING_DISABLE_DEPRECATED
QT_WARNING_DISABLE_GCC("-Wuseless-cast")
namespace {
struct qt_meta_tag_ZN18NotebookControllerE_t {};
} // unnamed namespace


#ifdef QT_MOC_HAS_STRINGDATA
static constexpr auto qt_meta_stringdata_ZN18NotebookControllerE = QtMocHelpers::stringData(
    "NotebookController",
    "QML.Element",
    "auto",
    "undoChanged",
    "",
    "notebooks",
    "QVariantList",
    "createNotebook",
    "title",
    "deleteNotebook",
    "id",
    "pages",
    "notebookId",
    "createPage",
    "openPage",
    "pageId",
    "InkItem*",
    "canvas",
    "PenReader*",
    "pen",
    "undo",
    "redo",
    "exportNotebookPdf",
    "outPath",
    "canUndo",
    "canRedo"
);
#else  // !QT_MOC_HAS_STRINGDATA
#error "qtmochelpers.h not found or too old."
#endif // !QT_MOC_HAS_STRINGDATA

Q_CONSTINIT static const uint qt_meta_data_ZN18NotebookControllerE[] = {

 // content:
      12,       // revision
       0,       // classname
       1,   14, // classinfo
      10,   16, // methods
       2,  104, // properties
       0,    0, // enums/sets
       0,    0, // constructors
       0,       // flags
       1,       // signalCount

 // classinfo: key, value
       1,    2,

 // signals: name, argc, parameters, tag, flags, initial metatype offsets
       3,    0,   76,    4, 0x06,    3 /* Public */,

 // methods: name, argc, parameters, tag, flags, initial metatype offsets
       5,    0,   77,    4, 0x02,    4 /* Public */,
       7,    1,   78,    4, 0x02,    5 /* Public */,
       9,    1,   81,    4, 0x02,    7 /* Public */,
      11,    1,   84,    4, 0x02,    9 /* Public */,
      13,    1,   87,    4, 0x02,   11 /* Public */,
      14,    3,   90,    4, 0x02,   13 /* Public */,
      20,    0,   97,    4, 0x02,   17 /* Public */,
      21,    0,   98,    4, 0x02,   18 /* Public */,
      22,    2,   99,    4, 0x02,   19 /* Public */,

 // signals: parameters
    QMetaType::Void,

 // methods: parameters
    0x80000000 | 6,
    QMetaType::LongLong, QMetaType::QString,    8,
    QMetaType::Void, QMetaType::LongLong,   10,
    0x80000000 | 6, QMetaType::LongLong,   12,
    QMetaType::LongLong, QMetaType::LongLong,   12,
    QMetaType::Void, QMetaType::LongLong, 0x80000000 | 16, 0x80000000 | 18,   15,   17,   19,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Bool, QMetaType::LongLong, QMetaType::QString,   12,   23,

 // properties: name, type, flags, notifyId, revision
      24, QMetaType::Bool, 0x00015001, uint(0), 0,
      25, QMetaType::Bool, 0x00015001, uint(0), 0,

       0        // eod
};

Q_CONSTINIT const QMetaObject NotebookController::staticMetaObject = { {
    QMetaObject::SuperData::link<QObject::staticMetaObject>(),
    qt_meta_stringdata_ZN18NotebookControllerE.offsetsAndSizes,
    qt_meta_data_ZN18NotebookControllerE,
    qt_static_metacall,
    nullptr,
    qt_metaTypeArray<
        // property 'canUndo'
        bool,
        // property 'canRedo'
        bool,
        // Q_OBJECT / Q_GADGET
        NotebookController,
        // method 'undoChanged'
        void,
        // method 'notebooks'
        QVariantList,
        // method 'createNotebook'
        qint64,
        const QString &,
        // method 'deleteNotebook'
        void,
        qint64,
        // method 'pages'
        QVariantList,
        qint64,
        // method 'createPage'
        qint64,
        qint64,
        // method 'openPage'
        void,
        qint64,
        InkItem *,
        PenReader *,
        // method 'undo'
        void,
        // method 'redo'
        void,
        // method 'exportNotebookPdf'
        bool,
        qint64,
        const QString &
    >,
    nullptr
} };

void NotebookController::qt_static_metacall(QObject *_o, QMetaObject::Call _c, int _id, void **_a)
{
    auto *_t = static_cast<NotebookController *>(_o);
    if (_c == QMetaObject::InvokeMetaMethod) {
        switch (_id) {
        case 0: _t->undoChanged(); break;
        case 1: { QVariantList _r = _t->notebooks();
            if (_a[0]) *reinterpret_cast< QVariantList*>(_a[0]) = std::move(_r); }  break;
        case 2: { qint64 _r = _t->createNotebook((*reinterpret_cast< std::add_pointer_t<QString>>(_a[1])));
            if (_a[0]) *reinterpret_cast< qint64*>(_a[0]) = std::move(_r); }  break;
        case 3: _t->deleteNotebook((*reinterpret_cast< std::add_pointer_t<qint64>>(_a[1]))); break;
        case 4: { QVariantList _r = _t->pages((*reinterpret_cast< std::add_pointer_t<qint64>>(_a[1])));
            if (_a[0]) *reinterpret_cast< QVariantList*>(_a[0]) = std::move(_r); }  break;
        case 5: { qint64 _r = _t->createPage((*reinterpret_cast< std::add_pointer_t<qint64>>(_a[1])));
            if (_a[0]) *reinterpret_cast< qint64*>(_a[0]) = std::move(_r); }  break;
        case 6: _t->openPage((*reinterpret_cast< std::add_pointer_t<qint64>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<InkItem*>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<PenReader*>>(_a[3]))); break;
        case 7: _t->undo(); break;
        case 8: _t->redo(); break;
        case 9: { bool _r = _t->exportNotebookPdf((*reinterpret_cast< std::add_pointer_t<qint64>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<QString>>(_a[2])));
            if (_a[0]) *reinterpret_cast< bool*>(_a[0]) = std::move(_r); }  break;
        default: ;
        }
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        switch (_id) {
        default: *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType(); break;
        case 6:
            switch (*reinterpret_cast<int*>(_a[1])) {
            default: *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType(); break;
            case 1:
                *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType::fromType< InkItem* >(); break;
            case 2:
                *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType::fromType< PenReader* >(); break;
            }
            break;
        }
    }
    if (_c == QMetaObject::IndexOfMethod) {
        int *result = reinterpret_cast<int *>(_a[0]);
        {
            using _q_method_type = void (NotebookController::*)();
            if (_q_method_type _q_method = &NotebookController::undoChanged; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 0;
                return;
            }
        }
    }
    if (_c == QMetaObject::ReadProperty) {
        void *_v = _a[0];
        switch (_id) {
        case 0: *reinterpret_cast< bool*>(_v) = _t->canUndo(); break;
        case 1: *reinterpret_cast< bool*>(_v) = _t->canRedo(); break;
        default: break;
        }
    }
}

const QMetaObject *NotebookController::metaObject() const
{
    return QObject::d_ptr->metaObject ? QObject::d_ptr->dynamicMetaObject() : &staticMetaObject;
}

void *NotebookController::qt_metacast(const char *_clname)
{
    if (!_clname) return nullptr;
    if (!strcmp(_clname, qt_meta_stringdata_ZN18NotebookControllerE.stringdata0))
        return static_cast<void*>(this);
    return QObject::qt_metacast(_clname);
}

int NotebookController::qt_metacall(QMetaObject::Call _c, int _id, void **_a)
{
    _id = QObject::qt_metacall(_c, _id, _a);
    if (_id < 0)
        return _id;
    if (_c == QMetaObject::InvokeMetaMethod) {
        if (_id < 10)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 10;
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        if (_id < 10)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 10;
    }
    if (_c == QMetaObject::ReadProperty || _c == QMetaObject::WriteProperty
            || _c == QMetaObject::ResetProperty || _c == QMetaObject::BindableProperty
            || _c == QMetaObject::RegisterPropertyMetaType) {
        qt_static_metacall(this, _c, _id, _a);
        _id -= 2;
    }
    return _id;
}

// SIGNAL 0
void NotebookController::undoChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 0, nullptr);
}
QT_WARNING_POP
