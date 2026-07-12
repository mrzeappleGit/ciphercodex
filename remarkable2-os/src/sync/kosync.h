#pragma once

#include <QString>

#include <optional>

// Forward-declared so host tests can include this header and link only kosync.cpp
// (pure logic) without pulling in Qt6Network. The network client lives in
// kosyncclient.cpp; test binaries never instantiate KosyncClient.
class QNetworkAccessManager;

namespace ccx::kosync {

// ---- Pure, host-testable core (kosync.cpp; no network) ----

// userKey = md5hex(utf8(password)), lowercase 32-hex. The password itself is
// never stored or sent — only this key is.
QString userKey(const QString &password);

// KOReader wire convention: truncate (never round up) to 4 decimals.
double truncatePercentage(double pct);  // floor(pct*10000)/10000

// Dirty = synced_at IS NULL OR synced_at < updated_at.
// syncedAt <= 0 encodes SQL NULL (never synced).
bool progressDirty(qint64 updatedAt, qint64 syncedAt);

struct Pos {
    int spineIndex;
    int charOffset;
};

// CipherCodex's own position encoding for the opaque kosync progress field.
// Both the Android app and CipherCodex OS read/write this exact format.
struct ProgressCodec {
    static QString encode(int spineIndex, int charOffset);  // "ciphercodex:s=<s>;o=<o>"
    static std::optional<Pos> decode(const QString &progress);  // ^ciphercodex:s=(\d+);o=(\d+)$
    // Foreign (KOReader xpointer) fallback: ^/body/DocFragment[N] is the 1-based
    // spine index; returns N-1. Callers otherwise position by percentage.
    static std::optional<int> foreignSpine(const QString &progress);
};

// ---- Types shared with the network client ----

struct Account {
    QString serverUrl;
    QString username;
    QString userKey;  // md5(password) hex, lowercase
};

struct RemoteProgress {
    QString document;
    QString progress;
    double percentage = 0.0;  // 0..1
    QString device;
    QString deviceId;
    qint64 timestamp = -1;  // server unix seconds; -1 == absent
    bool exists = false;    // false => server has no record for this document
};

// httpCode == -1 means transport failure (no response). On a successful
// getProgress, `remote` carries the record (remote.exists tells no-record apart).
struct Result {
    bool ok = false;
    int httpCode = -1;
    QString message;
    RemoteProgress remote;
};

// ---- Network client (kosyncclient.cpp; Qt6Network). One blocking call each. ----

class KosyncClient {
public:
    explicit KosyncClient(QNetworkAccessManager *nam);

    Result registerUser(const Account &a);  // POST /users/create, no auth
    Result authorize(const Account &a);      // GET  /users/auth
    Result getProgress(const Account &a, const QString &document);  // GET /syncs/progress/{doc}
    Result updateProgress(const Account &a, const RemoteProgress &p);  // PUT /syncs/progress

private:
    QNetworkAccessManager *m_nam;
};

}  // namespace ccx::kosync
