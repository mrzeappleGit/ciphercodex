// Host-side kosync + digest tests, plain assert(), no framework, no network.
// Exercises only the pure logic (digest, codec, truncation, userKey, dirty),
// so this binary links kosync.cpp + digest.cpp against Qt6Core alone.
#include "digest.h"
#include "kosync.h"

#include <QByteArrayView>
#include <QCryptographicHash>
#include <QFile>
#include <QTemporaryDir>

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>

using namespace ccx::kosync;

static void writeFile(const QString &path, const QByteArray &bytes)
{
    QFile f(path);
    assert(f.open(QIODevice::WriteOnly));
    assert(f.write(bytes) == bytes.size());
    f.close();
}

static QString md5Hex(QByteArrayView bytes)
{
    return QString::fromLatin1(QCryptographicHash::hash(bytes, QCryptographicHash::Md5).toHex());
}

static void testDigest(const QString &dir)
{
    // (a) a file < 1024 bytes: only the offset-0 sample is read, so partial == full MD5.
    QByteArray tiny(100, Qt::Uninitialized);
    for (int p = 0; p < tiny.size(); ++p)
        tiny[p] = char(p);
    const QString tinyPath = dir + QStringLiteral("/tiny.bin");
    writeFile(tinyPath, tiny);
    assert(ccx::partialMd5(tinyPath) == md5Hex(tiny));

    // (b) a > 1 MB file: recompute the expected MD5 here over exactly the sampled
    // ranges (offsets 0, then 1024<<(2*i)), independent of digest.cpp's loop.
    const qint64 length = 1050000;  // > 1 MB, so offset 1048576 (i=5) is sampled, i=6 is past EOF
    QByteArray big(int(length), Qt::Uninitialized);
    for (int p = 0; p < big.size(); ++p)
        big[p] = char((p * 131 + 7) & 0xFF);
    const QString bigPath = dir + QStringLiteral("/big.bin");
    writeFile(bigPath, big);

    QCryptographicHash md(QCryptographicHash::Md5);
    for (int i = -1; i <= 10; ++i) {
        const qint64 offset = (i == -1) ? 0 : (qint64(1024) << (2 * i));
        if (offset >= length)
            break;
        const qint64 take = std::min<qint64>(1024, length - offset);
        md.addData(QByteArrayView(big.constData() + offset, take));
    }
    assert(ccx::partialMd5(bigPath) == QString::fromLatin1(md.result().toHex()));

    // open failure -> empty string
    assert(ccx::partialMd5(dir + QStringLiteral("/does-not-exist")).isEmpty());
}

static void testCodec()
{
    assert(ProgressCodec::encode(3, 42) == QStringLiteral("ciphercodex:s=3;o=42"));

    const auto d = ProgressCodec::decode(QStringLiteral("ciphercodex:s=3;o=42"));
    assert(d.has_value() && d->spineIndex == 3 && d->charOffset == 42);

    // roundtrip
    const auto rt = ProgressCodec::decode(ProgressCodec::encode(7, 0));
    assert(rt.has_value() && rt->spineIndex == 7 && rt->charOffset == 0);

    // malformed / foreign values do not decode
    assert(!ProgressCodec::decode(QStringLiteral("garbage")).has_value());
    assert(!ProgressCodec::decode(QStringLiteral("ciphercodex:s=3")).has_value());
    assert(!ProgressCodec::decode(QStringLiteral("/body/DocFragment[5]/body/p[2]")).has_value());

    // foreign fallback: DocFragment[N] is 1-based -> spineIndex N-1
    const auto fs = ProgressCodec::foreignSpine(QStringLiteral("/body/DocFragment[5]/body/p[2]/text()"));
    assert(fs.has_value() && *fs == 4);
    assert(!ProgressCodec::foreignSpine(QStringLiteral("ciphercodex:s=1;o=0")).has_value());
    assert(!ProgressCodec::foreignSpine(QStringLiteral("/body/DocFragment[0]")).has_value());  // 1-based
}

static void testTruncation()
{
    const auto approx = [](double a, double b) { return std::fabs(a - b) < 1e-9; };
    assert(approx(truncatePercentage(0.123456), 0.1234));  // floor, never round up
    assert(approx(truncatePercentage(0.99999), 0.9999));
    assert(truncatePercentage(1.0) == 1.0);
    assert(truncatePercentage(0.0) == 0.0);
}

static void testUserKey()
{
    assert(userKey(QStringLiteral("password")) == QStringLiteral("5f4dcc3b5aa765d61d8327deb882cf99"));
}

static void testDirty()
{
    assert(progressDirty(100, 0));    // synced_at NULL (never synced) -> dirty
    assert(progressDirty(100, -1));   // NULL sentinel
    assert(!progressDirty(100, 100)); // synced at the exact pushed version -> clean
    assert(progressDirty(200, 100));  // a newer save bumps updated_at past synced_at -> dirty
    assert(!progressDirty(100, 150)); // synced_at ahead -> clean
}

int main()
{
    QTemporaryDir tmp;
    assert(tmp.isValid());
    testDigest(tmp.path());
    testCodec();
    testTruncation();
    testUserKey();
    testDirty();
    printf("ALL KOSYNC TESTS PASSED\n");
    return 0;
}
