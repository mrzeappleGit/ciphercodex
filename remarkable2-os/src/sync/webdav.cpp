#include "webdav.h"

#include <QByteArray>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>
#include <QXmlStreamReader>

namespace {

// One raw HTTP outcome: httpCode == -1 means no response (transport failure). Mirrors
// kosyncclient.cpp's finish() blocking pattern; safe only off the GUI thread.
struct Raw {
    int httpCode = -1;
    QByteArray body;
    QString transportError;
};

Raw finish(QNetworkReply *reply)
{
    QEventLoop loop;
    QObject::connect(reply, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    Raw raw;
    const QVariant code = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute);
    raw.body = reply->readAll();
    if (code.isValid())
        raw.httpCode = code.toInt();
    else
        raw.transportError = reply->errorString();
    reply->deleteLater();
    return raw;
}

bool ok2xx(int code) { return code >= 200 && code < 300; }

// Set *err from a failed outcome. Transport failure carries no HTTP code.
bool fail(QString *err, const Raw &raw)
{
    if (err) {
        if (raw.httpCode < 0)
            *err = raw.transportError.isEmpty() ? QStringLiteral("Network error")
                                                : raw.transportError;
        else
            *err = QStringLiteral("HTTP %1").arg(raw.httpCode);
    }
    return false;
}

// Decode an href (absolute path like /ccx/state/foo.json, or a full URL) to a decoded path
// with any trailing slash removed. Handles percent-encoding either way.
QString hrefPath(const QString &href)
{
    QString p = href.contains(QStringLiteral("://"))
        ? QUrl(href).path(QUrl::FullyDecoded)
        : QString::fromUtf8(QByteArray::fromPercentEncoding(href.toUtf8()));
    while (p.endsWith(QLatin1Char('/')))
        p.chop(1);
    return p;
}

}  // namespace

WebDavClient::WebDavClient(const WebDavConfig &cfg, QNetworkAccessManager *nam)
    : m_cfg(cfg), m_nam(nam) {}

// relPaths are app-minted (hex digest/deviceId + known extension), so no percent-encoding needed.
static QNetworkRequest davRequest(const WebDavConfig &cfg, const QString &relPath)
{
    QNetworkRequest req(QUrl(cfg.baseUrl + relPath));
    const QByteArray cred = (cfg.user + QLatin1Char(':') + cfg.pass).toUtf8().toBase64();
    req.setRawHeader("Authorization", "Basic " + cred);
    req.setTransferTimeout(30000);  // bound half-open sockets; times out as httpCode -1
    return req;
}

bool WebDavClient::mkcol(const QString &relPath, QString *err)
{
    const Raw raw = finish(m_nam->sendCustomRequest(davRequest(m_cfg, relPath), "MKCOL"));
    // 405 Method Not Allowed == collection already exists (can't MKCOL twice) — idempotent success.
    if (ok2xx(raw.httpCode) || raw.httpCode == 405)
        return true;
    return fail(err, raw);
}

bool WebDavClient::list(const QString &relPath, QStringList *names, QString *err)
{
    QNetworkRequest req = davRequest(m_cfg, relPath);
    req.setRawHeader("Depth", "1");
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/xml"));
    static const QByteArray body =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        "<propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>";
    const Raw raw = finish(m_nam->sendCustomRequest(req, "PROPFIND", body));
    if (!ok2xx(raw.httpCode))
        return fail(err, raw);

    // The requested collection is itself one of the Depth:1 <href>s — skip it, keep only children.
    const QString self = hrefPath(QUrl(m_cfg.baseUrl + relPath).path(QUrl::FullyDecoded));
    QXmlStreamReader xml(raw.body);
    while (!xml.atEnd()) {
        if (xml.readNext() == QXmlStreamReader::StartElement
            && xml.name() == QLatin1String("href")) {  // local name; ignores d:/D: prefix
            const QString path = hrefPath(xml.readElementText());
            if (path.isEmpty() || path == self)
                continue;
            const QString leaf = path.section(QLatin1Char('/'), -1);
            if (!leaf.isEmpty())
                names->append(leaf);
        }
    }
    return true;  // tolerate trailing XML parse errors; return what we parsed
}

bool WebDavClient::get(const QString &relPath, QByteArray *out, QString *err)
{
    const Raw raw = finish(m_nam->get(davRequest(m_cfg, relPath)));
    if (!ok2xx(raw.httpCode))
        return fail(err, raw);
    if (out)
        *out = raw.body;
    return true;
}

bool WebDavClient::put(const QString &relPath, const QByteArray &data, QString *err)
{
    const Raw raw = finish(m_nam->put(davRequest(m_cfg, relPath), data));
    if (!ok2xx(raw.httpCode))
        return fail(err, raw);
    return true;
}

bool WebDavClient::del(const QString &relPath, QString *err)
{
    const Raw raw = finish(m_nam->deleteResource(davRequest(m_cfg, relPath)));
    if (!ok2xx(raw.httpCode))
        return fail(err, raw);
    return true;
}

bool WebDavClient::testConnection(QString *err)
{
    QNetworkRequest req = davRequest(m_cfg, QString());
    req.setRawHeader("Depth", "0");  // just probe the base collection, don't enumerate children
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/xml"));
    static const QByteArray body =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        "<propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>";
    const Raw raw = finish(m_nam->sendCustomRequest(req, "PROPFIND", body));
    if (!ok2xx(raw.httpCode))
        return fail(err, raw);
    return true;
}

#ifdef WEBDAV_SELFTEST
// Manual, network-dependent self-check — NOT part of the normal build. Documents usage.
// Build & run (needs a live dufs endpoint):
//   g++ -std=c++17 -DWEBDAV_SELFTEST src/sync/webdav.cpp -o wd $(pkg-config --cflags --libs Qt6Network Qt6Core) -fPIC
//   ./wd https://host/ccx/ user pass
#include <QCoreApplication>
#include <cstdio>
int main(int argc, char **argv)
{
    QCoreApplication app(argc, argv);
    if (argc < 4) {
        fprintf(stderr, "usage: %s <baseUrl-ending-in-slash> <user> <pass>\n", argv[0]);
        return 2;
    }
    QNetworkAccessManager nam;
    WebDavConfig cfg{QString::fromUtf8(argv[1]), QString::fromUtf8(argv[2]), QString::fromUtf8(argv[3])};
    WebDavClient dav(cfg, &nam);
    QString err;
    if (!dav.testConnection(&err)) { printf("testConnection FAILED: %s\n", qPrintable(err)); return 1; }
    printf("testConnection ok\n");
    dav.mkcol(QStringLiteral("state/"), &err);
    if (!dav.put(QStringLiteral("state/selftest.json"), "{\"hello\":1}", &err)) {
        printf("put FAILED: %s\n", qPrintable(err)); return 1;
    }
    QStringList names;
    if (dav.list(QStringLiteral("state/"), &names, &err))
        printf("list: %s\n", qPrintable(names.join(QStringLiteral(", "))));
    QByteArray got;
    if (dav.get(QStringLiteral("state/selftest.json"), &got, &err))
        printf("get: %s\n", got.constData());
    dav.del(QStringLiteral("state/selftest.json"), &err);
    return 0;
}
#endif
