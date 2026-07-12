#pragma once

#include <QString>

namespace ccx {

// KOReader partial-MD5 (frontend/util.lua) — the kosync "binary" document id.
// Ports the Android app's Digests.partialMd5 byte-for-byte: samples up to 1024
// bytes at offsets 0, then 1024*4^i for i in 0..10, stopping at the first offset
// past EOF. Returns lowercase 32-hex, or "" if the file cannot be opened.
QString partialMd5(const QString &filePath);

}  // namespace ccx
