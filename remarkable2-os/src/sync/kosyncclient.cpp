#include "kosync.h"

#include <QEventLoop>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>

namespace ccx::kosync {

namespace {

const char *const kAccept = "application/vnd.koreader.v1+json";

// One raw HTTP outcome: httpCode == -1 means no response (transport failure).
struct Raw {
    int httpCode = -1;
    QByteArray body;
    QString transportError;
};

// Known koreader-sync-server error codes, mapped to readable text (matches Android).
QString friendlyMessage(int code)
{
    switch (code) {
    case 2001: return QStringLiteral("Unauthorized");
    case 2002: return QStringLiteral("Username is already registered");
    case 2003: return QStringLiteral("Invalid request");
    case 2005: return QStringLiteral("Registration is disabled on this server");
    default: return QString();
    }
}

QUrl buildUrl(const Account &a, const QStringList &segments)
{
    QString base = a.serverUrl.trimmed();
    while (base.endsWith(QLatin1Char('/')))
        base.chop(1);
    // Only variable segment is a 32-hex digest — no percent-encoding needed.
    return QUrl(base + QLatin1Char('/') + segments.join(QLatin1Char('/')));
}

QNetworkRequest request(const QUrl &url)
{
    QNetworkRequest req(url);
    req.setRawHeader("accept", kAccept);  // reference server 412s without it
    req.setTransferTimeout(15000);  // bound half-open sockets; times out as httpCode -1
    return req;
}

void addAuth(QNetworkRequest &req, const Account &a)
{
    req.setRawHeader("x-auth-user", a.username.toUtf8());
    req.setRawHeader("x-auth-key", a.userKey.toLower().toUtf8());  // md5 hex must be lowercase
}

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

// Build an error Result from a non-2xx response (or transport failure).
Result errorFrom(const Raw &raw)
{
    Result r;
    r.ok = false;
    r.httpCode = raw.httpCode;
    if (raw.httpCode < 0) {
        r.message = raw.transportError.isEmpty() ? QStringLiteral("Network error")
                                                 : raw.transportError;
        return r;
    }
    const QJsonObject obj = QJsonDocument::fromJson(raw.body).object();
    const QString friendly = obj.contains(QStringLiteral("code"))
        ? friendlyMessage(obj.value(QStringLiteral("code")).toInt())
        : QString();
    if (!friendly.isEmpty())
        r.message = friendly;
    else if (obj.contains(QStringLiteral("message")))
        r.message = obj.value(QStringLiteral("message")).toString();
    else
        r.message = QStringLiteral("HTTP %1").arg(raw.httpCode);
    return r;
}

QByteArray jsonBody(const QJsonObject &obj)
{
    return QJsonDocument(obj).toJson(QJsonDocument::Compact);
}

}  // namespace

KosyncClient::KosyncClient(QNetworkAccessManager *nam) : m_nam(nam) {}

Result KosyncClient::registerUser(const Account &a)
{
    QNetworkRequest req = request(buildUrl(a, {QStringLiteral("users"), QStringLiteral("create")}));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    QJsonObject body{{QStringLiteral("username"), a.username},
                     {QStringLiteral("password"), a.userKey}};  // register carries no auth headers
    const Raw raw = finish(m_nam->post(req, jsonBody(body)));
    if (raw.httpCode == 201)
        return Result{true, 201, QString(), {}};
    return errorFrom(raw);
}

Result KosyncClient::authorize(const Account &a)
{
    QNetworkRequest req = request(buildUrl(a, {QStringLiteral("users"), QStringLiteral("auth")}));
    addAuth(req, a);
    const Raw raw = finish(m_nam->get(req));
    if (raw.httpCode == 200)
        return Result{true, 200, QString(), {}};
    return errorFrom(raw);
}

Result KosyncClient::getProgress(const Account &a, const QString &document)
{
    QNetworkRequest req = request(
        buildUrl(a, {QStringLiteral("syncs"), QStringLiteral("progress"), document}));
    addAuth(req, a);
    const Raw raw = finish(m_nam->get(req));
    if (raw.httpCode != 200)
        return errorFrom(raw);

    const QJsonObject obj = QJsonDocument::fromJson(raw.body).object();
    Result r;
    r.ok = true;
    r.httpCode = 200;
    // "No record" is a 200 with an empty object: detected by the absent
    // percentage field, never by status.
    if (!obj.contains(QStringLiteral("percentage"))) {
        r.remote.exists = false;
        return r;
    }
    RemoteProgress &p = r.remote;
    p.exists = true;
    p.document = obj.value(QStringLiteral("document")).toString(document);
    p.progress = obj.value(QStringLiteral("progress")).toString();
    p.percentage = obj.value(QStringLiteral("percentage")).toDouble();
    p.device = obj.value(QStringLiteral("device")).toString();
    p.deviceId = obj.value(QStringLiteral("device_id")).toString();
    p.timestamp = obj.contains(QStringLiteral("timestamp"))
        ? qint64(obj.value(QStringLiteral("timestamp")).toDouble()) : -1;
    return r;
}

Result KosyncClient::updateProgress(const Account &a, const RemoteProgress &p)
{
    QNetworkRequest req = request(
        buildUrl(a, {QStringLiteral("syncs"), QStringLiteral("progress")}));
    addAuth(req, a);
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    QJsonObject body{
        {QStringLiteral("document"), p.document},
        {QStringLiteral("progress"), p.progress},
        {QStringLiteral("percentage"), truncatePercentage(p.percentage)},  // truncate, never round up
    };
    if (!p.device.isEmpty())
        body.insert(QStringLiteral("device"), p.device);
    if (!p.deviceId.isEmpty())
        body.insert(QStringLiteral("device_id"), p.deviceId);
    const Raw raw = finish(m_nam->put(req, jsonBody(body)));
    if (raw.httpCode == 200)
        return Result{true, 200, QString(), {}};
    return errorFrom(raw);
}

}  // namespace ccx::kosync
