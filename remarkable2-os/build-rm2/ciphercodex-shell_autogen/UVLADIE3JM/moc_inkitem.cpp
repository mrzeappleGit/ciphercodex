/****************************************************************************
** Meta object code from reading C++ file 'inkitem.h'
**
** Created by: The Qt Meta Object Compiler version 68 (Qt 6.8.2)
**
** WARNING! All changes made in this file will be lost!
*****************************************************************************/

#include "../../../src/inkitem.h"
#include <QtCore/qmetatype.h>
#include <QtCore/QList>

#include <QtCore/qtmochelpers.h>

#include <memory>


#include <QtCore/qxptype_traits.h>
#if !defined(Q_MOC_OUTPUT_REVISION)
#error "The header file 'inkitem.h' doesn't include <QObject>."
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
struct qt_meta_tag_ZN7InkItemE_t {};
} // unnamed namespace


#ifdef QT_MOC_HAS_STRINGDATA
static constexpr auto qt_meta_stringdata_ZN7InkItemE = QtMocHelpers::stringData(
    "InkItem",
    "QML.Element",
    "auto",
    "toolChanged",
    "",
    "strokeFinished",
    "StrokeData",
    "s",
    "strokesErased",
    "QList<qint64>",
    "ids",
    "penDown",
    "x",
    "y",
    "pressure",
    "rawPressure",
    "tiltX",
    "tiltY",
    "tMs",
    "penMove",
    "penUp",
    "clear",
    "tool"
);
#else  // !QT_MOC_HAS_STRINGDATA
#error "qtmochelpers.h not found or too old."
#endif // !QT_MOC_HAS_STRINGDATA

Q_CONSTINIT static const uint qt_meta_data_ZN7InkItemE[] = {

 // content:
      12,       // revision
       0,       // classname
       1,   14, // classinfo
      15,   16, // methods
       1,  225, // properties
       0,    0, // enums/sets
       0,    0, // constructors
       0,       // flags
       3,       // signalCount

 // classinfo: key, value
       1,    2,

 // signals: name, argc, parameters, tag, flags, initial metatype offsets
       3,    0,  106,    4, 0x06,    2 /* Public */,
       5,    1,  107,    4, 0x06,    3 /* Public */,
       8,    1,  110,    4, 0x06,    5 /* Public */,

 // slots: name, argc, parameters, tag, flags, initial metatype offsets
      11,    7,  113,    4, 0x0a,    7 /* Public */,
      11,    6,  128,    4, 0x2a,   15 /* Public | MethodCloned */,
      11,    5,  141,    4, 0x2a,   22 /* Public | MethodCloned */,
      11,    4,  152,    4, 0x2a,   28 /* Public | MethodCloned */,
      11,    3,  161,    4, 0x2a,   33 /* Public | MethodCloned */,
      19,    7,  168,    4, 0x0a,   37 /* Public */,
      19,    6,  183,    4, 0x2a,   45 /* Public | MethodCloned */,
      19,    5,  196,    4, 0x2a,   52 /* Public | MethodCloned */,
      19,    4,  207,    4, 0x2a,   58 /* Public | MethodCloned */,
      19,    3,  216,    4, 0x2a,   63 /* Public | MethodCloned */,
      20,    0,  223,    4, 0x0a,   67 /* Public */,
      21,    0,  224,    4, 0x0a,   68 /* Public */,

 // signals: parameters
    QMetaType::Void,
    QMetaType::Void, 0x80000000 | 6,    7,
    QMetaType::Void, 0x80000000 | 9,   10,

 // slots: parameters
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int, QMetaType::UInt,   12,   13,   14,   15,   16,   17,   18,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int,   12,   13,   14,   15,   16,   17,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int,   12,   13,   14,   15,   16,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int,   12,   13,   14,   15,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal,   12,   13,   14,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int, QMetaType::UInt,   12,   13,   14,   15,   16,   17,   18,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int,   12,   13,   14,   15,   16,   17,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int,   12,   13,   14,   15,   16,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int,   12,   13,   14,   15,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal,   12,   13,   14,
    QMetaType::Void,
    QMetaType::Void,

 // properties: name, type, flags, notifyId, revision
      22, QMetaType::Int, 0x00015103, uint(0), 0,

       0        // eod
};

Q_CONSTINIT const QMetaObject InkItem::staticMetaObject = { {
    QMetaObject::SuperData::link<QQuickPaintedItem::staticMetaObject>(),
    qt_meta_stringdata_ZN7InkItemE.offsetsAndSizes,
    qt_meta_data_ZN7InkItemE,
    qt_static_metacall,
    nullptr,
    qt_metaTypeArray<
        // property 'tool'
        int,
        // Q_OBJECT / Q_GADGET
        InkItem,
        // method 'toolChanged'
        void,
        // method 'strokeFinished'
        void,
        const StrokeData &,
        // method 'strokesErased'
        void,
        const QVector<qint64> &,
        // method 'penDown'
        void,
        qreal,
        qreal,
        qreal,
        int,
        int,
        int,
        quint32,
        // method 'penDown'
        void,
        qreal,
        qreal,
        qreal,
        int,
        int,
        int,
        // method 'penDown'
        void,
        qreal,
        qreal,
        qreal,
        int,
        int,
        // method 'penDown'
        void,
        qreal,
        qreal,
        qreal,
        int,
        // method 'penDown'
        void,
        qreal,
        qreal,
        qreal,
        // method 'penMove'
        void,
        qreal,
        qreal,
        qreal,
        int,
        int,
        int,
        quint32,
        // method 'penMove'
        void,
        qreal,
        qreal,
        qreal,
        int,
        int,
        int,
        // method 'penMove'
        void,
        qreal,
        qreal,
        qreal,
        int,
        int,
        // method 'penMove'
        void,
        qreal,
        qreal,
        qreal,
        int,
        // method 'penMove'
        void,
        qreal,
        qreal,
        qreal,
        // method 'penUp'
        void,
        // method 'clear'
        void
    >,
    nullptr
} };

void InkItem::qt_static_metacall(QObject *_o, QMetaObject::Call _c, int _id, void **_a)
{
    auto *_t = static_cast<InkItem *>(_o);
    if (_c == QMetaObject::InvokeMetaMethod) {
        switch (_id) {
        case 0: _t->toolChanged(); break;
        case 1: _t->strokeFinished((*reinterpret_cast< std::add_pointer_t<StrokeData>>(_a[1]))); break;
        case 2: _t->strokesErased((*reinterpret_cast< std::add_pointer_t<QList<qint64>>>(_a[1]))); break;
        case 3: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6])),(*reinterpret_cast< std::add_pointer_t<quint32>>(_a[7]))); break;
        case 4: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6]))); break;
        case 5: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5]))); break;
        case 6: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4]))); break;
        case 7: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3]))); break;
        case 8: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6])),(*reinterpret_cast< std::add_pointer_t<quint32>>(_a[7]))); break;
        case 9: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6]))); break;
        case 10: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5]))); break;
        case 11: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4]))); break;
        case 12: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3]))); break;
        case 13: _t->penUp(); break;
        case 14: _t->clear(); break;
        default: ;
        }
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        switch (_id) {
        default: *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType(); break;
        case 2:
            switch (*reinterpret_cast<int*>(_a[1])) {
            default: *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType(); break;
            case 0:
                *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType::fromType< QList<qint64> >(); break;
            }
            break;
        }
    }
    if (_c == QMetaObject::IndexOfMethod) {
        int *result = reinterpret_cast<int *>(_a[0]);
        {
            using _q_method_type = void (InkItem::*)();
            if (_q_method_type _q_method = &InkItem::toolChanged; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 0;
                return;
            }
        }
        {
            using _q_method_type = void (InkItem::*)(const StrokeData & );
            if (_q_method_type _q_method = &InkItem::strokeFinished; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 1;
                return;
            }
        }
        {
            using _q_method_type = void (InkItem::*)(const QVector<qint64> & );
            if (_q_method_type _q_method = &InkItem::strokesErased; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 2;
                return;
            }
        }
    }
    if (_c == QMetaObject::ReadProperty) {
        void *_v = _a[0];
        switch (_id) {
        case 0: *reinterpret_cast< int*>(_v) = _t->tool(); break;
        default: break;
        }
    }
    if (_c == QMetaObject::WriteProperty) {
        void *_v = _a[0];
        switch (_id) {
        case 0: _t->setTool(*reinterpret_cast< int*>(_v)); break;
        default: break;
        }
    }
}

const QMetaObject *InkItem::metaObject() const
{
    return QObject::d_ptr->metaObject ? QObject::d_ptr->dynamicMetaObject() : &staticMetaObject;
}

void *InkItem::qt_metacast(const char *_clname)
{
    if (!_clname) return nullptr;
    if (!strcmp(_clname, qt_meta_stringdata_ZN7InkItemE.stringdata0))
        return static_cast<void*>(this);
    return QQuickPaintedItem::qt_metacast(_clname);
}

int InkItem::qt_metacall(QMetaObject::Call _c, int _id, void **_a)
{
    _id = QQuickPaintedItem::qt_metacall(_c, _id, _a);
    if (_id < 0)
        return _id;
    if (_c == QMetaObject::InvokeMetaMethod) {
        if (_id < 15)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 15;
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        if (_id < 15)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 15;
    }
    if (_c == QMetaObject::ReadProperty || _c == QMetaObject::WriteProperty
            || _c == QMetaObject::ResetProperty || _c == QMetaObject::BindableProperty
            || _c == QMetaObject::RegisterPropertyMetaType) {
        qt_static_metacall(this, _c, _id, _a);
        _id -= 1;
    }
    return _id;
}

// SIGNAL 0
void InkItem::toolChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 0, nullptr);
}

// SIGNAL 1
void InkItem::strokeFinished(const StrokeData & _t1)
{
    void *_a[] = { nullptr, const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t1))) };
    QMetaObject::activate(this, &staticMetaObject, 1, _a);
}

// SIGNAL 2
void InkItem::strokesErased(const QVector<qint64> & _t1)
{
    void *_a[] = { nullptr, const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t1))) };
    QMetaObject::activate(this, &staticMetaObject, 2, _a);
}
QT_WARNING_POP
