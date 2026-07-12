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
    "strokesSplit",
    "removedIds",
    "QList<StrokeData>",
    "fragments",
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
      16,   16, // methods
       1,  236, // properties
       0,    0, // enums/sets
       0,    0, // constructors
       0,       // flags
       4,       // signalCount

 // classinfo: key, value
       1,    2,

 // signals: name, argc, parameters, tag, flags, initial metatype offsets
       3,    0,  112,    4, 0x06,    2 /* Public */,
       5,    1,  113,    4, 0x06,    3 /* Public */,
       8,    1,  116,    4, 0x06,    5 /* Public */,
      11,    2,  119,    4, 0x06,    7 /* Public */,

 // slots: name, argc, parameters, tag, flags, initial metatype offsets
      15,    7,  124,    4, 0x0a,   10 /* Public */,
      15,    6,  139,    4, 0x2a,   18 /* Public | MethodCloned */,
      15,    5,  152,    4, 0x2a,   25 /* Public | MethodCloned */,
      15,    4,  163,    4, 0x2a,   31 /* Public | MethodCloned */,
      15,    3,  172,    4, 0x2a,   36 /* Public | MethodCloned */,
      23,    7,  179,    4, 0x0a,   40 /* Public */,
      23,    6,  194,    4, 0x2a,   48 /* Public | MethodCloned */,
      23,    5,  207,    4, 0x2a,   55 /* Public | MethodCloned */,
      23,    4,  218,    4, 0x2a,   61 /* Public | MethodCloned */,
      23,    3,  227,    4, 0x2a,   66 /* Public | MethodCloned */,
      24,    0,  234,    4, 0x0a,   70 /* Public */,
      25,    0,  235,    4, 0x0a,   71 /* Public */,

 // signals: parameters
    QMetaType::Void,
    QMetaType::Void, 0x80000000 | 6,    7,
    QMetaType::Void, 0x80000000 | 9,   10,
    QMetaType::Void, 0x80000000 | 9, 0x80000000 | 13,   12,   14,

 // slots: parameters
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int, QMetaType::UInt,   16,   17,   18,   19,   20,   21,   22,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int,   16,   17,   18,   19,   20,   21,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int,   16,   17,   18,   19,   20,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int,   16,   17,   18,   19,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal,   16,   17,   18,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int, QMetaType::UInt,   16,   17,   18,   19,   20,   21,   22,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int,   16,   17,   18,   19,   20,   21,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int,   16,   17,   18,   19,   20,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int,   16,   17,   18,   19,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal,   16,   17,   18,
    QMetaType::Void,
    QMetaType::Void,

 // properties: name, type, flags, notifyId, revision
      26, QMetaType::Int, 0x00015103, uint(0), 0,

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
        // method 'strokesSplit'
        void,
        const QVector<qint64> &,
        const QVector<StrokeData> &,
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
        case 3: _t->strokesSplit((*reinterpret_cast< std::add_pointer_t<QList<qint64>>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<QList<StrokeData>>>(_a[2]))); break;
        case 4: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6])),(*reinterpret_cast< std::add_pointer_t<quint32>>(_a[7]))); break;
        case 5: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6]))); break;
        case 6: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5]))); break;
        case 7: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4]))); break;
        case 8: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3]))); break;
        case 9: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6])),(*reinterpret_cast< std::add_pointer_t<quint32>>(_a[7]))); break;
        case 10: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6]))); break;
        case 11: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5]))); break;
        case 12: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4]))); break;
        case 13: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3]))); break;
        case 14: _t->penUp(); break;
        case 15: _t->clear(); break;
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
        case 3:
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
        {
            using _q_method_type = void (InkItem::*)(const QVector<qint64> & , const QVector<StrokeData> & );
            if (_q_method_type _q_method = &InkItem::strokesSplit; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 3;
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
        if (_id < 16)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 16;
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        if (_id < 16)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 16;
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

// SIGNAL 3
void InkItem::strokesSplit(const QVector<qint64> & _t1, const QVector<StrokeData> & _t2)
{
    void *_a[] = { nullptr, const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t1))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t2))) };
    QMetaObject::activate(this, &staticMetaObject, 3, _a);
}
QT_WARNING_POP
