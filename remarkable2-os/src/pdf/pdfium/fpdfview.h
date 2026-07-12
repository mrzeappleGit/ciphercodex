// PDFium public C API — VENDORED MINIMAL SUBSET (BSD-3-Clause).
// Copyright 2014 The PDFium Authors; original code copyright 2014 Foxit Software Inc.
// Source API: pdfium/public/fpdfview.h. Declarations copied byte-for-byte from the
// device/SDK sysroot header so the subset matches the on-device libpdfium.so ABI
// exactly (verified by link test). Only the entry points CipherCodex uses are kept.
#ifndef CCX_PDFIUM_FPDFVIEW_H_
#define CCX_PDFIUM_FPDFVIEW_H_

#include <stddef.h>

// PDF opaque handle types (incomplete types force API type safety).
typedef struct fpdf_bitmap_t__* FPDF_BITMAP;
typedef struct fpdf_bookmark_t__* FPDF_BOOKMARK;
typedef struct fpdf_dest_t__* FPDF_DEST;
typedef struct fpdf_document_t__* FPDF_DOCUMENT;
typedef struct fpdf_page_t__* FPDF_PAGE;
typedef struct fpdf_textpage_t__* FPDF_TEXTPAGE;

// Basic data types.
typedef int FPDF_BOOL;
typedef unsigned long FPDF_DWORD;
typedef float FS_FLOAT;

// String types (UTF-16LE wide strings; each FPDF_WCHAR is 2 bytes, low byte first).
typedef unsigned short FPDF_WCHAR;
typedef const FPDF_WCHAR* FPDF_WIDESTRING;
typedef const char* FPDF_BYTESTRING;
typedef const char* FPDF_STRING;

// Rectangle size (points). Coordinate system agnostic.
typedef struct FS_SIZEF_ {
  float width;
  float height;
} * FS_LPSIZEF, FS_SIZEF;

// Non-Windows, dynamically imported: both macros expand empty (matches sysroot header
// when FPDF_IMPLEMENTATION / WIN32 are undefined).
#define FPDF_EXPORT
#define FPDF_CALLCONV

// Page rendering flags (bit-wise OR).
#define FPDF_ANNOT 0x01       // render non-interactive annotations
#define FPDF_GRAYSCALE 0x08   // grayscale output

#ifdef __cplusplus
extern "C" {
#endif

// Selection of 2D graphics library used for rendering to FPDF_BITMAPs.
typedef enum {
  FPDF_RENDERERTYPE_AGG = 0,
  FPDF_RENDERERTYPE_SKIA = 1,
} FPDF_RENDERER_TYPE;

// Process-wide options for initializing the library. Layout is ABI-critical.
typedef struct FPDF_LIBRARY_CONFIG_ {
  int version;                    // must be 2
  const char** m_pUserFontPaths;  // NULL for defaults
  void* m_pIsolate;               // v8 isolate; NULL
  unsigned int m_v8EmbedderSlot;
  void* m_pPlatform;              // v8 platform; NULL
  FPDF_RENDERER_TYPE m_RendererType;
} FPDF_LIBRARY_CONFIG;

FPDF_EXPORT void FPDF_CALLCONV
FPDF_InitLibraryWithConfig(const FPDF_LIBRARY_CONFIG* config);

FPDF_EXPORT void FPDF_CALLCONV FPDF_DestroyLibrary();

FPDF_EXPORT FPDF_DOCUMENT FPDF_CALLCONV
FPDF_LoadDocument(FPDF_STRING file_path, FPDF_BYTESTRING password);

FPDF_EXPORT void FPDF_CALLCONV FPDF_CloseDocument(FPDF_DOCUMENT document);

FPDF_EXPORT int FPDF_CALLCONV FPDF_GetPageCount(FPDF_DOCUMENT document);

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV
FPDF_GetPageSizeByIndexF(FPDF_DOCUMENT document, int page_index, FS_SIZEF* size);

FPDF_EXPORT FPDF_PAGE FPDF_CALLCONV FPDF_LoadPage(FPDF_DOCUMENT document,
                                                  int page_index);

FPDF_EXPORT void FPDF_CALLCONV FPDF_ClosePage(FPDF_PAGE page);

FPDF_EXPORT void FPDF_CALLCONV FPDF_RenderPageBitmap(FPDF_BITMAP bitmap,
                                                     FPDF_PAGE page,
                                                     int start_x,
                                                     int start_y,
                                                     int size_x,
                                                     int size_y,
                                                     int rotate,
                                                     int flags);

// FPDFBitmap_Create: 4 bytes/pixel (BGRx, or BGRA when alpha != 0), rows are
// width * 4 bytes with no gap between lines.
FPDF_EXPORT FPDF_BITMAP FPDF_CALLCONV FPDFBitmap_Create(int width,
                                                        int height,
                                                        int alpha);

FPDF_EXPORT FPDF_BOOL FPDF_CALLCONV FPDFBitmap_FillRect(FPDF_BITMAP bitmap,
                                                        int left,
                                                        int top,
                                                        int width,
                                                        int height,
                                                        FPDF_DWORD color);

FPDF_EXPORT void* FPDF_CALLCONV FPDFBitmap_GetBuffer(FPDF_BITMAP bitmap);

FPDF_EXPORT void FPDF_CALLCONV FPDFBitmap_Destroy(FPDF_BITMAP bitmap);

#ifdef __cplusplus
}
#endif

#endif  // CCX_PDFIUM_FPDFVIEW_H_
