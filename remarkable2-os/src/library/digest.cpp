#include "digest.h"

#include <QCryptographicHash>
#include <QFile>

namespace ccx {

QString partialMd5(const QString &filePath)
{
    QFile f(filePath);
    if (!f.open(QIODevice::ReadOnly))
        return QString();

    QCryptographicHash md(QCryptographicHash::Md5);
    char buffer[1024];
    const qint64 length = f.size();
    for (int i = -1; i <= 10; ++i) {
        // i==-1 is the offset-0 sample, special-cased: 1024<<(2*i) with i=-1 is a
        // negative shift (UB in C++); the Lua source relies on LuaJIT masking it to 0.
        const qint64 offset = (i == -1) ? 0 : (qint64(1024) << (2 * i));
        if (offset >= length)
            break;
        if (!f.seek(offset))
            break;
        qint64 filled = 0;
        while (filled < qint64(sizeof buffer)) {
            const qint64 n = f.read(buffer + filled, qint64(sizeof buffer) - filled);
            if (n <= 0)  // <0 error, ==0 EOF (RandomAccessFile returns -1 at EOF; QFile 0)
                break;
            filled += n;
        }
        if (filled == 0)
            break;
        md.addData(QByteArrayView(buffer, filled));
    }
    return QString::fromLatin1(md.result().toHex());
}

}  // namespace ccx
