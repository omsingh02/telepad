#ifdef _WIN32

#include "platform.h"

#include <windows.h>
#include <string.h>

/*
 * UTF-8 ↔ UTF-16 clipboard bridging via standard Win32 clipboard APIs.
 *
 * Wraps OpenClipboard/SetClipboardData/GetClipboardData. Retries OpenClipboard
 * a few times — another process may have it open briefly (Explorer copy
 * operations, etc.). 32 KB cap matches the Android side.
 */

#define MAX_CLIPBOARD 32768

static int open_clipboard_with_retry(HWND owner) {
    for (int i = 0; i < 5; i++) {
        if (OpenClipboard(owner)) return 1;
        Sleep(10);
    }
    return 0;
}

size_t platform_clipboard_read(char *out, size_t max) {
    if (!out || max == 0) return 0;
    if (!open_clipboard_with_retry(NULL)) return 0;

    size_t written = 0;
    HANDLE h = GetClipboardData(CF_UNICODETEXT);
    if (h) {
        WCHAR *w = (WCHAR *)GlobalLock(h);
        if (w) {
            int needed = WideCharToMultiByte(CP_UTF8, 0, w, -1,
                                             NULL, 0, NULL, NULL);
            if (needed > 0 && (size_t)needed <= max) {
                WideCharToMultiByte(CP_UTF8, 0, w, -1,
                                    out, needed, NULL, NULL);
                /* needed includes terminator; we want byte count without it. */
                written = (size_t)(needed - 1);
                if (written > max) written = max;
            }
            GlobalUnlock(h);
        }
    }
    CloseClipboard();
    return written;
}

void platform_clipboard_write(const char *utf8, size_t len) {
    if (!utf8 || len == 0) return;
    if (len > MAX_CLIPBOARD) len = MAX_CLIPBOARD;

    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8, (int)len, NULL, 0);
    if (wlen <= 0) return;

    HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE, (size_t)(wlen + 1) * sizeof(WCHAR));
    if (!h) return;
    WCHAR *dst = (WCHAR *)GlobalLock(h);
    if (!dst) { GlobalFree(h); return; }

    MultiByteToWideChar(CP_UTF8, 0, utf8, (int)len, dst, wlen);
    dst[wlen] = 0;
    GlobalUnlock(h);

    if (!open_clipboard_with_retry(NULL)) {
        GlobalFree(h);
        return;
    }
    EmptyClipboard();
    if (!SetClipboardData(CF_UNICODETEXT, h)) {
        /* SetClipboardData takes ownership on success; free on failure. */
        GlobalFree(h);
    }
    CloseClipboard();
}

#endif
