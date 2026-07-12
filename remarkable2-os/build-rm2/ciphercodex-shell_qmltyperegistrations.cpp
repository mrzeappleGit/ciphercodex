/****************************************************************************
** Generated QML type registration code
**
** WARNING! All changes made in this file will be lost!
*****************************************************************************/

#include <QtQml/qqml.h>
#include <QtQml/qqmlmoduleregistration.h>

#if __has_include(<inkitem.h>)
#  include <inkitem.h>
#endif
#if __has_include(<notebookcontroller.h>)
#  include <notebookcontroller.h>
#endif
#if __has_include(<penreader.h>)
#  include <penreader.h>
#endif


#if !defined(QT_STATIC)
#define Q_QMLTYPE_EXPORT Q_DECL_EXPORT
#else
#define Q_QMLTYPE_EXPORT
#endif
Q_QMLTYPE_EXPORT void qml_register_types_CipherCodex()
{
    QT_WARNING_PUSH QT_WARNING_DISABLE_DEPRECATED
    qmlRegisterTypesAndRevisions<InkItem>("CipherCodex", 1);
    qmlRegisterAnonymousType<QQuickItem, 254>("CipherCodex", 1);
    qmlRegisterTypesAndRevisions<NotebookController>("CipherCodex", 1);
    qmlRegisterTypesAndRevisions<PenReader>("CipherCodex", 1);
    QT_WARNING_POP
    qmlRegisterModule("CipherCodex", 1, 0);
}

static const QQmlModuleRegistration cipherCodexRegistration("CipherCodex", qml_register_types_CipherCodex);
