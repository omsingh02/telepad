#ifdef _WIN32

#include "platform.h"
#include "protocol.h"

#include <windows.h>
#include <stdio.h>
#include <stdlib.h>

int  platform_init(void)    { return 0; }
void platform_cleanup(void) { /* no-op */ }

/* ── Mouse ─────────────────────────────────────────────────────── */

void platform_mouse_move(int16_t dx, int16_t dy) {
    INPUT in = {0};
    in.type = INPUT_MOUSE;
    in.mi.dx = dx;
    in.mi.dy = dy;
    in.mi.dwFlags = MOUSEEVENTF_MOVE;
    if (SendInput(1, &in, sizeof(INPUT)) == 0) {
        printf("SendInput failed! GetLastError=%lu\n", GetLastError());
    }
}

void platform_mouse_button(uint8_t button, uint8_t pressed) {
    static const DWORD flags[3][2] = {
        { MOUSEEVENTF_LEFTUP,   MOUSEEVENTF_LEFTDOWN   },
        { MOUSEEVENTF_RIGHTUP,  MOUSEEVENTF_RIGHTDOWN  },
        { MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MIDDLEDOWN },
    };
    if (button >= 3) return;
    INPUT in = {0};
    in.type = INPUT_MOUSE;
    in.mi.dwFlags = flags[button][pressed ? 1 : 0];
    SendInput(1, &in, sizeof(INPUT));
}

void platform_scroll(int16_t delta) {
    INPUT in = {0};
    in.type = INPUT_MOUSE;
    in.mi.dwFlags = MOUSEEVENTF_WHEEL;
    in.mi.mouseData = (DWORD)((int32_t)delta * WHEEL_DELTA);
    SendInput(1, &in, sizeof(INPUT));
}

/* ── Keyboard ──────────────────────────────────────────────────── */

static void send_modifiers(uint8_t modifiers, BOOL pressed) {
    INPUT inputs[8] = {0};
    int n = 0;
    DWORD flag = pressed ? 0 : KEYEVENTF_KEYUP;
    if (modifiers & MOD_LCTRL)  { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_LCONTROL; inputs[n].ki.dwFlags = flag; n++; }
    if (modifiers & MOD_LSHIFT) { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_LSHIFT;   inputs[n].ki.dwFlags = flag; n++; }
    if (modifiers & MOD_LALT)   { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_LMENU;    inputs[n].ki.dwFlags = flag; n++; }
    if (modifiers & MOD_LMETA)  { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_LWIN;     inputs[n].ki.dwFlags = flag; n++; }
    if (modifiers & MOD_RCTRL)  { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_RCONTROL; inputs[n].ki.dwFlags = flag; n++; }
    if (modifiers & MOD_RSHIFT) { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_RSHIFT;   inputs[n].ki.dwFlags = flag; n++; }
    if (modifiers & MOD_RALT)   { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_RMENU;    inputs[n].ki.dwFlags = flag; n++; }
    if (modifiers & MOD_RMETA)  { inputs[n].type = INPUT_KEYBOARD; inputs[n].ki.wVk = VK_RWIN;     inputs[n].ki.dwFlags = flag; n++; }
    if (n > 0) SendInput(n, inputs, sizeof(INPUT));
}

/* USB HID Usage Page 0x07 → Windows Virtual Key. Full coverage. */
static WORD hid_to_vk(uint16_t hid) {
    if (hid >= 0x04 && hid <= 0x1D) return (WORD)('A' + (hid - 0x04));
    if (hid >= 0x1E && hid <= 0x26) return (WORD)('1' + (hid - 0x1E));
    if (hid == 0x27) return '0';
    if (hid >= 0x3A && hid <= 0x45) return (WORD)(VK_F1 + (hid - 0x3A));
    switch (hid) {
        case 0x28: return VK_RETURN;       case 0x29: return VK_ESCAPE;
        case 0x2A: return VK_BACK;         case 0x2B: return VK_TAB;
        case 0x2C: return VK_SPACE;        case 0x2D: return VK_OEM_MINUS;
        case 0x2E: return VK_OEM_PLUS;     case 0x2F: return VK_OEM_4;
        case 0x30: return VK_OEM_6;        case 0x31: return VK_OEM_5;
        case 0x33: return VK_OEM_1;        case 0x34: return VK_OEM_7;
        case 0x35: return VK_OEM_3;        case 0x36: return VK_OEM_COMMA;
        case 0x37: return VK_OEM_PERIOD;   case 0x38: return VK_OEM_2;
        case 0x39: return VK_CAPITAL;      case 0x46: return VK_SNAPSHOT;
        case 0x47: return VK_SCROLL;       case 0x48: return VK_PAUSE;
        case 0x49: return VK_INSERT;       case 0x4A: return VK_HOME;
        case 0x4B: return VK_PRIOR;        case 0x4C: return VK_DELETE;
        case 0x4D: return VK_END;          case 0x4E: return VK_NEXT;
        case 0x4F: return VK_RIGHT;        case 0x50: return VK_LEFT;
        case 0x51: return VK_DOWN;         case 0x52: return VK_UP;
        case 0x53: return VK_NUMLOCK;      case 0x54: return VK_DIVIDE;
        case 0x55: return VK_MULTIPLY;     case 0x56: return VK_SUBTRACT;
        case 0x57: return VK_ADD;          case 0x58: return VK_RETURN;
        case 0x59: return VK_NUMPAD1;      case 0x5A: return VK_NUMPAD2;
        case 0x5B: return VK_NUMPAD3;      case 0x5C: return VK_NUMPAD4;
        case 0x5D: return VK_NUMPAD5;      case 0x5E: return VK_NUMPAD6;
        case 0x5F: return VK_NUMPAD7;      case 0x60: return VK_NUMPAD8;
        case 0x61: return VK_NUMPAD9;      case 0x62: return VK_NUMPAD0;
        case 0x63: return VK_DECIMAL;      case 0x65: return VK_APPS;
        case 0xE0: return VK_LCONTROL;     case 0xE1: return VK_LSHIFT;
        case 0xE2: return VK_LMENU;        case 0xE3: return VK_LWIN;
        case 0xE4: return VK_RCONTROL;     case 0xE5: return VK_RSHIFT;
        case 0xE6: return VK_RMENU;        case 0xE7: return VK_RWIN;
    }
    return 0;
}

static void send_vk_scancode(WORD vk, BOOL release) {
    UINT scan = MapVirtualKey(vk, MAPVK_VK_TO_VSC_EX);
    INPUT in = {0};
    in.type = INPUT_KEYBOARD;
    in.ki.wScan = (WORD)(scan & 0xFFFF);
    in.ki.dwFlags = KEYEVENTF_SCANCODE | (release ? KEYEVENTF_KEYUP : 0);
    if (scan & 0xE000) in.ki.dwFlags |= KEYEVENTF_EXTENDEDKEY;
    SendInput(1, &in, sizeof(INPUT));
}

void platform_key_press(uint16_t hid_usage, uint8_t modifiers) {
    if (modifiers) send_modifiers(modifiers, TRUE);
    WORD vk = hid_to_vk(hid_usage);
    if (vk) send_vk_scancode(vk, FALSE);
}

void platform_key_release(uint16_t hid_usage, uint8_t modifiers) {
    WORD vk = hid_to_vk(hid_usage);
    if (vk) send_vk_scancode(vk, TRUE);
    if (modifiers) send_modifiers(modifiers, FALSE);
}

void platform_type_text(const char *utf8, uint16_t len) {
    if (!utf8 || len == 0) return;
    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8, len, NULL, 0);
    if (wlen <= 0) return;
    WCHAR *w = (WCHAR *)malloc(sizeof(WCHAR) * (size_t)wlen);
    if (!w) return;
    MultiByteToWideChar(CP_UTF8, 0, utf8, len, w, wlen);

    INPUT batch[128];
    int n = 0;
    for (int i = 0; i < wlen; i++) {
        batch[n].type = INPUT_KEYBOARD;
        batch[n].ki.wVk = 0;
        batch[n].ki.wScan = (WORD)w[i];
        batch[n].ki.dwFlags = KEYEVENTF_UNICODE;
        batch[n].ki.time = 0;
        batch[n].ki.dwExtraInfo = 0;
        n++;
        batch[n] = batch[n - 1];
        batch[n].ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
        n++;
        if (n >= 126) {
            SendInput((UINT)n, batch, sizeof(INPUT));
            n = 0;
        }
    }
    if (n > 0) SendInput((UINT)n, batch, sizeof(INPUT));
    free(w);
}

/* ── Media / Volume / Lock / Launchers ─────────────────────────── */

static void send_vk_momentary(WORD vk) {
    INPUT in = {0};
    in.type = INPUT_KEYBOARD;
    in.ki.wVk = vk;
    SendInput(1, &in, sizeof(INPUT));
    in.ki.dwFlags = KEYEVENTF_KEYUP;
    SendInput(1, &in, sizeof(INPUT));
}

void platform_media(uint8_t action) {
    switch (action) {
        case MEDIA_PLAY_PAUSE: send_vk_momentary(VK_MEDIA_PLAY_PAUSE); break;
        case MEDIA_NEXT:       send_vk_momentary(VK_MEDIA_NEXT_TRACK); break;
        case MEDIA_PREV:       send_vk_momentary(VK_MEDIA_PREV_TRACK); break;
        case MEDIA_STOP:       send_vk_momentary(VK_MEDIA_STOP); break;
        default: break;
    }
}

void platform_volume(uint8_t direction) {
    switch (direction) {
        case VOLUME_UP:   send_vk_momentary(VK_VOLUME_UP); break;
        case VOLUME_DOWN: send_vk_momentary(VK_VOLUME_DOWN); break;
        case VOLUME_MUTE: send_vk_momentary(VK_VOLUME_MUTE); break;
        default: break;
    }
}

void platform_lock(void) {
    if (!LockWorkStation()) {
        fprintf(stderr,
                "LockWorkStation failed (err=%lu). "
                "Likely a non-interactive session.\n",
                GetLastError());
    }
}

void platform_launch(uint8_t action) {
    switch (action) {
        case LAUNCH_SHOW_DESKTOP: {
            INPUT in[4] = {0};
            in[0].type = INPUT_KEYBOARD; in[0].ki.wVk = VK_LWIN;
            in[1].type = INPUT_KEYBOARD; in[1].ki.wVk = 'D';
            in[2].type = INPUT_KEYBOARD; in[2].ki.wVk = 'D'; in[2].ki.dwFlags = KEYEVENTF_KEYUP;
            in[3].type = INPUT_KEYBOARD; in[3].ki.wVk = VK_LWIN; in[3].ki.dwFlags = KEYEVENTF_KEYUP;
            SendInput(4, in, sizeof(INPUT));
            break;
        }
        case LAUNCH_TASK_VIEW: {
            INPUT in[4] = {0};
            in[0].type = INPUT_KEYBOARD; in[0].ki.wVk = VK_LWIN;
            in[1].type = INPUT_KEYBOARD; in[1].ki.wVk = VK_TAB;
            in[2].type = INPUT_KEYBOARD; in[2].ki.wVk = VK_TAB; in[2].ki.dwFlags = KEYEVENTF_KEYUP;
            in[3].type = INPUT_KEYBOARD; in[3].ki.wVk = VK_LWIN; in[3].ki.dwFlags = KEYEVENTF_KEYUP;
            SendInput(4, in, sizeof(INPUT));
            break;
        }
        case LAUNCH_BROWSER:
            ShellExecuteA(NULL, "open", "https://", NULL, NULL, SW_SHOWNORMAL);
            break;
        case LAUNCH_FILE_MANAGER:
            ShellExecuteA(NULL, "open", "explorer.exe", NULL, NULL, SW_SHOWNORMAL);
            break;
        case LAUNCH_TASK_MANAGER:
            ShellExecuteA(NULL, "open", "taskmgr.exe", NULL, NULL, SW_SHOWNORMAL);
            break;
        case LAUNCH_SCREENSHOT: {
            INPUT in[4] = {0};
            in[0].type = INPUT_KEYBOARD; in[0].ki.wVk = VK_LWIN;
            in[1].type = INPUT_KEYBOARD; in[1].ki.wVk = VK_LSHIFT;
            in[2].type = INPUT_KEYBOARD; in[2].ki.wVk = 'S';
            INPUT up[3] = {0};
            up[0].type = INPUT_KEYBOARD; up[0].ki.wVk = 'S';      up[0].ki.dwFlags = KEYEVENTF_KEYUP;
            up[1].type = INPUT_KEYBOARD; up[1].ki.wVk = VK_LSHIFT;up[1].ki.dwFlags = KEYEVENTF_KEYUP;
            up[2].type = INPUT_KEYBOARD; up[2].ki.wVk = VK_LWIN;  up[2].ki.dwFlags = KEYEVENTF_KEYUP;
            SendInput(3, in, sizeof(INPUT));
            SendInput(3, up, sizeof(INPUT));
            break;
        }
        default: break;
    }
}

#endif /* _WIN32 */
