/****************************************************************************
** Meta object code from reading C++ file 'penreader.h'
**
** Created by: The Qt Meta Object Compiler version 68 (Qt 6.8.2)
**
** WARNING! All changes made in this file will be lost!
*****************************************************************************/

#include "../../../src/penreader.h"
#include <QtCore/qmetatype.h>

#include <QtCore/qtmochelpers.h>

#include <memory>


#include <QtCore/qxptype_traits.h>
#if !defined(Q_MOC_OUTPUT_REVISION)
#error "The header file 'penreader.h' doesn't include <QObject>."
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
struct qt_meta_tag_ZN9PenReaderE_t {};
} // unnamed namespace


#ifdef QT_MOC_HAS_STRINGDATA
static constexpr auto qt_meta_stringdata_ZN9PenReaderE = QtMocHelpers::stringData(
    "PenReader",
    "QML.Element",
    "auto",
    "penDown",
    "",
    "x",
    "y",
    "pressure",
    "penMove",
    "penUp",
    "nearChanged",
    "eraserChanged",
    "sampled",
    "calibChanged",
    "near",
    "eraser",
    "tiltX",
    "tiltY",
    "calib"
);
#else  // !QT_MOC_HAS_STRINGDATA
#error "qtmochelpers.h not found or too old."
#endif // !QT_MOC_HAS_STRINGDATA

Q_CONSTINIT static const uint qt_meta_data_ZN9PenReaderE[] = {

 // content:
      12,       // revision
       0,       // classname
       1,   14, // classinfo
       7,   16, // methods
       6,   77, // properties
       0,    0, // enums/sets
       0,    0, // constructors
       0,       // flags
       7,       // signalCount

 // classinfo: key, value
       1,    2,

 // signals: name, argc, parameters, tag, flags, initial metatype offsets
       3,    3,   58,    4, 0x06,    7 /* Public */,
       8,    3,   65,    4, 0x06,   11 /* Public */,
       9,    0,   72,    4, 0x06,   15 /* Public */,
      10,    0,   73,    4, 0x06,   16 /* Public */,
      11,    0,   74,    4, 0x06,   17 /* Public */,
      12,    0,   75,    4, 0x06,   18 /* Public */,
      13,    0,   76,    4, 0x06,   19 /* Public */,

 // signals: parameters
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal,    5,    6,    7,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal,    5,    6,    7,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Void,

 // properties: name, type, flags, notifyId, revision
      14, QMetaType::Bool, 0x00015001, uint(3), 0,
      15, QMetaType::Bool, 0x00015001, uint(4), 0,
       7, QMetaType::QReal, 0x00015001, uint(5), 0,
      16, QMetaType::Int, 0x00015001, uint(5), 0,
      17, QMetaType::Int, 0x00015001, uint(5), 0,
      18, QMetaType::Int, 0x00015103, uint(6), 0,

       0        // eod
};

Q_CONSTINIT const QMetaObject PenReader::staticMetaObject = { {
    QMetaObject::SuperData::link<QObject::staticMetaObject>(),
    qt_meta_stringdata_ZN9PenReaderE.offsetsAndSizes,
    qt_meta_data_ZN9PenReaderE,
    qt_static_metacall,
    nullptr,
    qt_metaTypeArray<
        // property 'near'
        bool,
        // property 'eraser'
        bool,
        // property 'pressure'
        qreal,
        // property 'tiltX'
        int,
        // property 'tiltY'
        int,
        // property 'calib'
        int,
        // Q_OBJECT / Q_GADGET
        PenReader,
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
        // method 'penUp'
        void,
        // method 'nearChanged'
        void,
        // method 'eraserChanged'
        void,
        // method 'sampled'
        void,
        // method 'calibChanged'
        void
    >,
    nullptr
} };

void PenReader::qt_static_metacall(QObject *_o, QMetaObject::Call _c, int _id, void **_a)
{
    auto *_t = static_cast<PenReader *>(_o);
    if (_c == QMetaObject::InvokeMetaMethod) {
        switch (_id) {
        case 0: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3]))); break;
        case 1: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3]))); break;
        case 2: _t->penUp(); break;
        case 3: _t->nearChanged(); break;
        case 4: _t->eraserChanged(); break;
        case 5: _t->sampled(); break;
        case 6: _t->calibChanged(); break;
        default: ;
        }
    }
    if (_c == QMetaObject::IndexOfMethod) {
        int *result = reinterpret_cast<int *>(_a[0]);
        {
            using _q_method_type = void (PenReader::*)(qreal , qreal , qreal );
            if (_q_method_type _q_method = &PenReader::penDown; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 0;
                return;
            }
        }
        {
            using _q_method_type = void (PenReader::*)(qreal , qreal , qreal );
            if (_q_method_type _q_method = &PenReader::penMove; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 1;
                return;
            }
        }
        {
            using _q_method_type = void (PenReader::*)();
            if (_q_method_type _q_method = &PenReader::penUp; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 2;
                return;
            }
        }
        {
            using _q_method_type = void (PenReader::*)();
            if (_q_method_type _q_method = &PenReader::nearChanged; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 3;
                return;
            }
        }
        {
            using _q_method_type = void (PenReader::*)();
            if (_q_method_type _q_method = &PenReader::eraserChanged; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 4;
                return;
            }
        }
        {
            using _q_method_type = void (PenReader::*)();
            if (_q_method_type _q_method = &PenReader::sampled; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 5;
                return;
            }
        }
        {
            using _q_method_type = void (PenReader::*)();
            if (_q_method_type _q_method = &PenReader::calibChanged; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 6;
                return;
            }
        }
    }
    if (_c == QMetaObject::ReadProperty) {
        void *_v = _a[0];
        switch (_id) {
        case 0: *reinterpret_cast< bool*>(_v) = _t->near(); break;
        case 1: *reinterpret_cast< bool*>(_v) = _t->eraser(); break;
        case 2: *reinterpret_cast< qreal*>(_v) = _t->pressure(); break;
        case 3: *reinterpret_cast< int*>(_v) = _t->tiltX(); break;
        case 4: *reinterpret_cast< int*>(_v) = _t->tiltY(); break;
        case 5: *reinterpret_cast< int*>(_v) = _t->calib(); break;
        default: break;
        }
    }
    if (_c == QMetaObject::WriteProperty) {
        void *_v = _a[0];
        switch (_id) {
        case 5: _t->setCalib(*reinterpret_cast< int*>(_v)); break;
        default: break;
        }
    }
}

const QMetaObject *PenReader::metaObject() const
{
    return QObject::d_ptr->metaObject ? QObject::d_ptr->dynamicMetaObject() : &staticMetaObject;
}

void *PenReader::qt_metacast(const char *_clname)
{
    if (!_clname) return nullptr;
    if (!strcmp(_clname, qt_meta_stringdata_ZN9PenReaderE.stringdata0))
        return static_cast<void*>(this);
    return QObject::qt_metacast(_clname);
}

int PenReader::qt_metacall(QMetaObject::Call _c, int _id, void **_a)
{
    _id = QObject::qt_metacall(_c, _id, _a);
    if (_id < 0)
        return _id;
    if (_c == QMetaObject::InvokeMetaMethod) {
        if (_id < 7)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 7;
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        if (_id < 7)
            *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType();
        _id -= 7;
    }
    if (_c == QMetaObject::ReadProperty || _c == QMetaObject::WriteProperty
            || _c == QMetaObject::ResetProperty || _c == QMetaObject::BindableProperty
            || _c == QMetaObject::RegisterPropertyMetaType) {
        qt_static_metacall(this, _c, _id, _a);
        _id -= 6;
    }
    return _id;
}

// SIGNAL 0
void PenReader::penDown(qreal _t1, qreal _t2, qreal _t3)
{
    void *_a[] = { nullptr, const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t1))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t2))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t3))) };
    QMetaObject::activate(this, &staticMetaObject, 0, _a);
}

// SIGNAL 1
void PenReader::penMove(qreal _t1, qreal _t2, qreal _t3)
{
    void *_a[] = { nullptr, const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t1))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t2))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t3))) };
    QMetaObject::activate(this, &staticMetaObject, 1, _a);
}

// SIGNAL 2
void PenReader::penUp()
{
    QMetaObject::activate(this, &staticMetaObject, 2, nullptr);
}

// SIGNAL 3
void PenReader::nearChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 3, nullptr);
}

// SIGNAL 4
void PenReader::eraserChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 4, nullptr);
}

// SIGNAL 5
void PenReader::sampled()
{
    QMetaObject::activate(this, &staticMetaObject, 5, nullptr);
}

// SIGNAL 6
void PenReader::calibChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 6, nullptr);
}
QT_WARNING_POP
