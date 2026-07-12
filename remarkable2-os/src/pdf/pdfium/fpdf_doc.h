// PDFium public C API — VENDORED MINIMAL SUBSET (BSD-3-Clause).
// Copyright 2014 The PDFium Authors; original code copyright 2014 Foxit Software Inc.
// Source API: pdfium/public/fpdf_doc.h. Declarations copied byte-for-byte from the
// device/SDK sysroot header (bookmark/outline traversal + document metadata only).
#ifndef CCX_PDFIUM_FPDF_DOC_H_
#define CCX_PDFIUM_FPDF_DOC_H_

#include "fpdfview.h"

#ifdef __cplusplus
extern "C" {
#endif

// First child of |bookmark|, or first top-level item when |bookmark| is NULL.
FPDF_EXPORT FPDF_BOOKMARK FPDF_CALLCONV
FPDFBookmark_GetFirstChild(FPDF_DOCUMENT document, FPDF_BOOKMARK bookmark);

// Next sibling of |bookmark|, or NULL if last at this level.
FPDF_EXPORT FPDF_BOOKMARK FPDF_CALLCONV
FPDFBookmark_GetNextSibling(FPDF_DOCUMENT document, FPDF_BOOKMARK bookmark);

// Title of |bookmark| as UTF-16LE into |buffer|; returns byte count incl. NUL.
FPDF_EXPORT unsigned long FPDF_CALLCONV
FPDFBookmark_GetTitle(FPDF_BOOKMARK bookmark,
                      void* buffer,
                      unsigned long buflen);

// Destination associated with |bookmark|, or NULL.
FPDF_EXPORT FPDF_DEST FPDF_CALLCONV
FPDFBookmark_GetDest(FPDF_DOCUMENT document, FPDF_BOOKMARK bookmark);

// 0-based page index containing |dest|, or -1 on error.
FPDF_EXPORT int FPDF_CALLCONV FPDFDest_GetDestPageIndex(FPDF_DOCUMENT document,
                                                        FPDF_DEST dest);

// Meta-data |tag| (e.g. "Title") as UTF-16LE into |buffer|; returns byte count
// incl. trailing zeros. |tag| is one of: Title, Author, Subject, Keywords,
// Creator, Producer, CreationDate, ModDate.
FPDF_EXPORT unsigned long FPDF_CALLCONV FPDF_GetMetaText(FPDF_DOCUMENT document,
                                                         FPDF_BYTESTRING tag,
                                                         void* buffer,
                                                         unsigned long buflen);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // CCX_PDFIUM_FPDF_DOC_H_
