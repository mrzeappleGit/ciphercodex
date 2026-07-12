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
    "rawPressure",
    "tiltX",
    "tiltY",
    "tMs",
    "penMove",
    "penUp",
    "nearChanged",
    "eraserChanged",
    "sampled",
    "calibChanged",
    "canvasRectChanged",
    "near",
    "eraser",
    "canvasRect",
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
       8,   16, // methods
       7,  100, // properties
       0,    0, // enums/sets
       0,    0, // constructors
       0,       // flags
       8,       // signalCount

 // classinfo: key, value
       1,    2,

 // signals: name, argc, parameters, tag, flags, initial metatype offsets
       3,    7,   64,    4, 0x06,    8 /* Public */,
      12,    7,   79,    4, 0x06,   16 /* Public */,
      13,    0,   94,    4, 0x06,   24 /* Public */,
      14,    0,   95,    4, 0x06,   25 /* Public */,
      15,    0,   96,    4, 0x06,   26 /* Public */,
      16,    0,   97,    4, 0x06,   27 /* Public */,
      17,    0,   98,    4, 0x06,   28 /* Public */,
      18,    0,   99,    4, 0x06,   29 /* Public */,

 // signals: parameters
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int, QMetaType::UInt,    5,    6,    7,    8,    9,   10,   11,
    QMetaType::Void, QMetaType::QReal, QMetaType::QReal, QMetaType::QReal, QMetaType::Int, QMetaType::Int, QMetaType::Int, QMetaType::UInt,    5,    6,    7,    8,    9,   10,   11,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Void,
    QMetaType::Void,

 // properties: name, type, flags, notifyId, revision
      19, QMetaType::Bool, 0x00015001, uint(3), 0,
      20, QMetaType::Bool, 0x00015001, uint(4), 0,
       7, QMetaType::QReal, 0x00015001, uint(5), 0,
       9, QMetaType::Int, 0x00015001, uint(5), 0,
      10, QMetaType::Int, 0x00015001, uint(5), 0,
      21, QMetaType::QRectF, 0x00015103, uint(7), 0,
      22, QMetaType::Int, 0x00015103, uint(6), 0,

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
        // property 'canvasRect'
        QRectF,
        // property 'calib'
        int,
        // Q_OBJECT / Q_GADGET
        PenReader,
        // method 'penDown'
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
        quint32,
        // method 'penUp'
        void,
        // method 'nearChanged'
        void,
        // method 'eraserChanged'
        void,
        // method 'sampled'
        void,
        // method 'calibChanged'
        void,
        // method 'canvasRectChanged'
        void
    >,
    nullptr
} };

void PenReader::qt_static_metacall(QObject *_o, QMetaObject::Call _c, int _id, void **_a)
{
    auto *_t = static_cast<PenReader *>(_o);
    if (_c == QMetaObject::InvokeMetaMethod) {
        switch (_id) {
        case 0: _t->penDown((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6])),(*reinterpret_cast< std::add_pointer_t<quint32>>(_a[7]))); break;
        case 1: _t->penMove((*reinterpret_cast< std::add_pointer_t<qreal>>(_a[1])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[2])),(*reinterpret_cast< std::add_pointer_t<qreal>>(_a[3])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[4])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[5])),(*reinterpret_cast< std::add_pointer_t<int>>(_a[6])),(*reinterpret_cast< std::add_pointer_t<quint32>>(_a[7]))); break;
        case 2: _t->penUp(); break;
        case 3: _t->nearChanged(); break;
        case 4: _t->eraserChanged(); break;
        case 5: _t->sampled(); break;
        case 6: _t->calibChanged(); break;
        case 7: _t->canvasRectChanged(); break;
        default: ;
        }
    }
    if (_c == QMetaObject::IndexOfMethod) {
        int *result = reinterpret_cast<int *>(_a[0]);
        {
            using _q_method_type = void (PenReader::*)(qreal , qreal , qreal , int , int , int , quint32 );
            if (_q_method_type _q_method = &PenReader::penDown; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 0;
                return;
            }
        }
        {
            using _q_method_type = void (PenReader::*)(qreal , qreal , qreal , int , int , int , quint32 );
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
        {
            using _q_method_type = void (PenReader::*)();
            if (_q_method_type _q_method = &PenReader::canvasRectChanged; *reinterpret_cast<_q_method_type *>(_a[1]) == _q_method) {
                *result = 7;
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
        case 5: *reinterpret_cast< QRectF*>(_v) = _t->canvasRect(); break;
        case 6: *reinterpret_cast< int*>(_v) = _t->calib(); break;
        default: break;
        }
    }
    if (_c == QMetaObject::WriteProperty) {
        void *_v = _a[0];
        switch (_id) {
        case 5: _t->setCanvasRect(*reinterpret_cast< QRectF*>(_v)); break;
        case 6: _t->setCalib(*reinterpret_cast< int*>(_v)); break;
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
        if (_id < 8)
            qt_static_metacall(this, _c, _id, _a);
        _id -= 8;
    }
    if (_c == QMetaObject::RegisterMethodArgumentMetaType) {
        if (_id < 8)
            *reinterpret_cast<QMetaType *>(_a[0]) = QMetaType();
        _id -= 8;
    }
    if (_c == QMetaObject::ReadProperty || _c == QMetaObject::WriteProperty
            || _c == QMetaObject::ResetProperty || _c == QMetaObject::BindableProperty
            || _c == QMetaObject::RegisterPropertyMetaType) {
        qt_static_metacall(this, _c, _id, _a);
        _id -= 7;
    }
    return _id;
}

// SIGNAL 0
void PenReader::penDown(qreal _t1, qreal _t2, qreal _t3, int _t4, int _t5, int _t6, quint32 _t7)
{
    void *_a[] = { nullptr, const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t1))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t2))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t3))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t4))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t5))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t6))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t7))) };
    QMetaObject::activate(this, &staticMetaObject, 0, _a);
}

// SIGNAL 1
void PenReader::penMove(qreal _t1, qreal _t2, qreal _t3, int _t4, int _t5, int _t6, quint32 _t7)
{
    void *_a[] = { nullptr, const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t1))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t2))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t3))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t4))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t5))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t6))), const_cast<void*>(reinterpret_cast<const void*>(std::addressof(_t7))) };
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

// SIGNAL 7
void PenReader::canvasRectChanged()
{
    QMetaObject::activate(this, &staticMetaObject, 7, nullptr);
}
QT_WARNING_POP
