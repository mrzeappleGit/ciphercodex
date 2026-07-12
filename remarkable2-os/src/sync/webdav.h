#pragma once

#include <QString>
#include <QStringList>

class QNetworkAccessManager;

// Global scope (no ccx:: namespace) to match the frozen phase-3b contract: SyncStore/SyncEngine
// #include this and reference WebDavConfig/WebDavClient unqualified.
struct WebDavConfig {
    QString baseUrl;  // ends with '/', e.g. https://kosync.cph.gg/ccx/
    QString user;
    QString pass;
};

// Thin WebDAV (dufs) client over QNetworkAccessManager with Basic auth + a bounded transfer
// timeout. Every call BLOCKS on a local QEventLoop, so it must run on the sync worker thread,
// never the GUI thread. 2xx == success; 4xx/5xx/transport -> false with *err set.
class WebDavClient {
public:
    explicit WebDavClient(const WebDavConfig &cfg, QNetworkAccessManager *nam);

    bool mkcol(const QString &relPath, QString *err);          // MKCOL; 201 or 405-exists both ok
    bool list(const QString &relPath, QStringList *names, QString *err);  // PROPFIND Depth:1 -> leaf names
    bool get(const QString &relPath, QByteArray *out, QString *err);
    bool put(const QString &relPath, const QByteArray &data, QString *err);
    bool del(const QString &relPath, QString *err);
    bool testConnection(QString *err);                          // PROPFIND base (Depth:0)

private:
    WebDavConfig m_cfg;
    QNetworkAccessManager *m_nam;
};
