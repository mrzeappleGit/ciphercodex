#include "kosync.h"

#include <QCryptographicHash>
#include <QRegularExpression>

#include <cmath>

namespace ccx::kosync {

QString userKey(const QString &password)
{
    return QString::fromLatin1(
        QCryptographicHash::hash(password.toUtf8(), QCryptographicHash::Md5).toHex());
}

double truncatePercentage(double pct)
{
    return std::floor(pct * 10000.0) / 10000.0;
}

bool progressDirty(qint64 updatedAt, qint64 syncedAt)
{
    return syncedAt <= 0 || syncedAt < updatedAt;
}

QString ProgressCodec::encode(int spineIndex, int charOffset)
{
    return QStringLiteral("ciphercodex:s=%1;o=%2").arg(spineIndex).arg(charOffset);
}

std::optional<Pos> ProgressCodec::decode(const QString &progress)
{
    static const QRegularExpression re(QStringLiteral("^ciphercodex:s=(\\d+);o=(\\d+)$"));
    const QRegularExpressionMatch m = re.match(progress.trimmed());
    if (!m.hasMatch())
        return std::nullopt;
    bool okS = false, okO = false;
    const int s = m.captured(1).toInt(&okS);
    const int o = m.captured(2).toInt(&okO);
    if (!okS || !okO)
        return std::nullopt;
    return Pos{s, o};
}

std::optional<int> ProgressCodec::foreignSpine(const QString &progress)
{
    // KOReader xpointer: DocFragment[N] is the 1-based spine index. Not anchored
    // at the end (the xpointer continues past the fragment).
    static const QRegularExpression re(QStringLiteral("^/body/DocFragment\\[(\\d+)\\]"));
    const QRegularExpressionMatch m = re.match(progress.trimmed());
    if (!m.hasMatch())
        return std::nullopt;
    bool ok = false;
    const int n = m.captured(1).toInt(&ok);
    if (!ok || n <= 0)
        return std::nullopt;
    return n - 1;
}

}  // namespace ccx::kosync
