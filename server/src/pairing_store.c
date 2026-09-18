#include "pairing_store.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#include <shlobj.h>
#else
#include <sys/stat.h>
#include <unistd.h>
#endif

#define MAX_TRUSTED 128
#define KEY_LEN     32

static uint8_t g_keys[MAX_TRUSTED][KEY_LEN];
static int     g_count = 0;

static const char *trusted_path(char *out, size_t cap) {
#ifdef _WIN32
    char roam[MAX_PATH];
    if (SHGetFolderPathA(NULL, CSIDL_APPDATA, NULL, 0, roam) != S_OK) return NULL;
    snprintf(out, cap, "%s\\Telepad", roam);
    CreateDirectoryA(out, NULL);
    snprintf(out, cap, "%s\\Telepad\\trusted_clients.bin", roam);
#else
    const char *home = getenv("HOME");
    if (!home) return NULL;
    snprintf(out, cap, "%s/.config/telepad", home);
    mkdir(out, 0700);
    snprintf(out, cap, "%s/.config/telepad/trusted_clients.bin", home);
#endif
    return out;
}

static void save(void) {
    char path[1024];
    if (!trusted_path(path, sizeof(path))) return;
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fwrite(g_keys, KEY_LEN, (size_t)g_count, f);
    fclose(f);
#ifndef _WIN32
    chmod(path, 0600);
#endif
}

int pairing_store_init(void) {
    char path[1024];
    if (!trusted_path(path, sizeof(path))) return -1;
    FILE *f = fopen(path, "rb");
    if (!f) {
        g_count = 0;
        return 0;
    }
    g_count = 0;
    uint8_t buf[KEY_LEN];
    while (g_count < MAX_TRUSTED && fread(buf, 1, KEY_LEN, f) == KEY_LEN) {
        memcpy(g_keys[g_count++], buf, KEY_LEN);
    }
    fclose(f);
    return 0;
}

void pairing_store_cleanup(void) {
    /* Nothing dynamic to free. Zero-out for hygiene. */
    memset(g_keys, 0, sizeof(g_keys));
    g_count = 0;
}

int pairing_store_is_trusted(const uint8_t pubkey[KEY_LEN]) {
    for (int i = 0; i < g_count; i++) {
        if (memcmp(g_keys[i], pubkey, KEY_LEN) == 0) return 1;
    }
    return 0;
}

void pairing_store_trust(const uint8_t pubkey[KEY_LEN]) {
    if (pairing_store_is_trusted(pubkey)) return;
    if (g_count >= MAX_TRUSTED) {
        /* FIFO eviction. Simple and predictable. */
        memmove(g_keys[0], g_keys[1], (size_t)(MAX_TRUSTED - 1) * KEY_LEN);
        g_count = MAX_TRUSTED - 1;
    }
    memcpy(g_keys[g_count++], pubkey, KEY_LEN);
    save();
}

void pairing_store_forget(const uint8_t pubkey[KEY_LEN]) {
    for (int i = 0; i < g_count; i++) {
        if (memcmp(g_keys[i], pubkey, KEY_LEN) == 0) {
            if (i < g_count - 1) {
                memmove(g_keys[i], g_keys[i + 1],
                        (size_t)(g_count - i - 1) * KEY_LEN);
            }
            g_count--;
            save();
            return;
        }
    }
}

void pairing_store_forget_all(void) {
    g_count = 0;
    save();
}

int pairing_store_count(void) {
    return g_count;
}
