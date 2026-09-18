#ifdef _WIN32

#include "platform.h"

#include <string.h>

/*
 * Now-playing via Windows SMTC (System Media Transport Controls).
 *
 * The full WinRT GlobalSystemMediaTransportControlsSessionManager API is
 * available since Windows 10 1809 (build 17763). Calling it from pure C
 * requires C/WinRT projection headers — significant build complexity for a
 * single optional feature.
 *
 * For v1 we ship a **safe stub** that returns "not playing" so the protocol
 * is complete and the phone UI just hides the now-playing card. The full
 * SMTC binding can be added later as a separate C++/WinRT translation unit
 * without changing this header or the wire format.
 */

int platform_now_playing(platform_now_playing_t *np) {
    if (!np) return -1;
    memset(np, 0, sizeof(*np));
    return -1; /* nothing playing — phone hides the card */
}

#endif
