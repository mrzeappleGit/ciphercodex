// PDFium public C API — VENDORED MINIMAL SUBSET (BSD-3-Clause).
// Copyright 2014 The PDFium Authors; original code copyright 2014 Foxit Software Inc.
// Source API: pdfium/public/fpdf_text.h. Declarations copied byte-for-byte from the
// device/SDK sysroot header (whole-page text extraction for search only).
#ifndef CCX_PDFIUM_FPDF_TEXT_H_
#define CCX_PDFIUM_FPDF_TEXT_H_

#include "fpdfview.h"

#ifdef __cplusplus
extern "C" {
#endif

// Prepare per-character info for a page; release with FPDFText_ClosePage.
FPDF_EXPORT FPDF_TEXTPAGE FPDF_CALLCONV FPDFText_LoadPage(FPDF_PAGE page);

FPDF_EXPORT void FPDF_CALLCONV FPDFText_ClosePage(FPDF_TEXTPAGE text_page);

FPDF_EXPORT int FPDF_CALLCONV FPDFText_CountChars(FPDF_TEXTPAGE text_page);

// Extract |count| UCS-2 values from |start_index| into |result| (must hold
// count+1 values). Returns chars written, including the trailing terminator.
FPDF_EXPORT int FPDF_CALLCONV FPDFText_GetText(FPDF_TEXTPAGE text_page,
                                               int start_index,
                                               int count,
                                               unsigned short* result);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // CCX_PDFIUM_FPDF_TEXT_H_
