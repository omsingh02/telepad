#include "noise_session.h"
#include "protocol.h"

#include <noise/protocol.h>
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

static NoiseDHState *g_static_kp = NULL;
static uint8_t       g_static_pub[32];

static const char *key_path(char *out, size_t cap) {
#ifdef _WIN32
    char roam[MAX_PATH];
    if (SHGetFolderPathA(NULL, CSIDL_APPDATA, NULL, 0, roam) != S_OK) return NULL;
    snprintf(out, cap, "%s\\Telepad", roam);
    CreateDirectoryA(out, NULL);
    snprintf(out, cap, "%s\\Telepad\\static.key", roam);
#else
    const char *home = getenv("HOME");
    if (!home) return NULL;
    snprintf(out, cap, "%s/.config/telepad", home);
    mkdir(out, 0700);
    snprintf(out, cap, "%s/.config/telepad/static.key", home);
#endif
    return out;
}

static int load_or_create_keypair(void) {
    if (noise_dhstate_new_by_id(&g_static_kp, NOISE_DH_CURVE25519) != NOISE_ERROR_NONE)
        return -1;

    char path[1024];
    if (!key_path(path, sizeof(path))) return -1;

    FILE *f = fopen(path, "rb");
    if (f) {
        uint8_t priv[32];
        if (fread(priv, 1, 32, f) == 32) {
            noise_dhstate_set_keypair_private(g_static_kp, priv, 32);
        }
        fclose(f);
    } else {
        if (noise_dhstate_generate_keypair(g_static_kp) != NOISE_ERROR_NONE)
            return -1;
        uint8_t priv[32];
        noise_dhstate_get_keypair(g_static_kp, priv, sizeof(priv), g_static_pub, sizeof(g_static_pub));
        f = fopen(path, "wb");
        if (f) {
            fwrite(priv, 1, 32, f);
            fclose(f);
        }
#ifndef _WIN32
        chmod(path, 0600);
#endif
    }
    noise_dhstate_get_public_key(g_static_kp, g_static_pub, sizeof(g_static_pub));
    return 0;
}

int telepad_noise_init(void) {
    if (noise_init() != NOISE_ERROR_NONE) return -1;
    return load_or_create_keypair();
}

void telepad_noise_cleanup(void) {
    if (g_static_kp) noise_dhstate_free(g_static_kp);
    g_static_kp = NULL;
}

void telepad_noise_get_pubkey(uint8_t out[32]) {
    memcpy(out, g_static_pub, 32);
}

/* SHA-256 first 6 bytes → 12-char hex fingerprint.
   Uses noise-c's built-in SHA256 to avoid taking another dep. */
static void compute_fingerprint(char out[13]) {
    NoiseHashState *h = NULL;
    noise_hashstate_new_by_id(&h, NOISE_HASH_SHA256);
    uint8_t digest[32];
    noise_hashstate_hash_one(h, g_static_pub, 32, digest, 32);
    noise_hashstate_free(h);
    static const char hex[] = "0123456789ABCDEF";
    for (int i = 0; i < 6; i++) {
        out[i * 2]     = hex[(digest[i] >> 4) & 0xF];
        out[i * 2 + 1] = hex[digest[i] & 0xF];
    }
    out[12] = '\0';
}

void telepad_noise_print_pairing_banner(void) {
    /* Base64 encode the pubkey for displays that want the full key. */
    static const char b64[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    char enc[64];
    int  pos = 0;
    for (int i = 0; i < 30; i += 3) {
        enc[pos++] = b64[(g_static_pub[i] >> 2) & 63];
        enc[pos++] = b64[((g_static_pub[i] & 3) << 4) |
                          (g_static_pub[i + 1] >> 4)];
        enc[pos++] = b64[((g_static_pub[i + 1] & 15) << 2) |
                          (g_static_pub[i + 2] >> 6)];
        enc[pos++] = b64[g_static_pub[i + 2] & 63];
    }
    enc[pos++] = b64[(g_static_pub[30] >> 2) & 63];
    enc[pos++] = b64[((g_static_pub[30] & 3) << 4) | (g_static_pub[31] >> 4)];
    enc[pos++] = b64[(g_static_pub[31] & 15) << 2];
    enc[pos++] = '=';
    enc[pos]   = '\0';

    char fp[13];
    compute_fingerprint(fp);

    printf("\n");
    printf("  Telepad server identity\n");
    printf("  ─────────────────────────────────────────────────\n");
    printf("  Fingerprint: %.4s · %.4s · %.4s\n",
           fp, fp + 4, fp + 8);
    printf("  Pubkey:      %s\n", enc);
    printf("  ─────────────────────────────────────────────────\n");
    printf("  Compare the fingerprint to what your phone shows.\n\n");
}

int telepad_noise_handshake_respond(telepad_noise_t *c,
                                    const uint8_t *msg1, int msg1_len,
                                    uint8_t *out_resp, int out_cap) {
    if (!c || !msg1 || msg1_len != NOISE_IK_MSG1_LEN ||
        out_cap < NOISE_IK_MSG2_LEN) return -1;

    NoiseHandshakeState *hs = NULL;
    int err = noise_handshakestate_new_by_name(
        &hs, "Noise_IK_25519_ChaChaPoly_BLAKE2s", NOISE_ROLE_RESPONDER);
    if (err != NOISE_ERROR_NONE) return -1;

    NoiseDHState *local = noise_handshakestate_get_local_keypair_dh(hs);
    noise_dhstate_copy(local, g_static_kp);

    int rc = -1;
    if (noise_handshakestate_start(hs) != NOISE_ERROR_NONE) goto fail;

    NoiseBuffer rb;
    noise_buffer_set_input(rb, (uint8_t *)msg1, msg1_len);
    if (noise_handshakestate_read_message(hs, &rb, NULL) != NOISE_ERROR_NONE)
        goto fail;

    NoiseBuffer wb;
    noise_buffer_set_output(wb, out_resp, out_cap);
    if (noise_handshakestate_write_message(hs, &wb, NULL) != NOISE_ERROR_NONE)
        goto fail;
    int resp_len = (int)wb.size;

    if (noise_handshakestate_split(hs, &c->send, &c->recv) != NOISE_ERROR_NONE)
        goto fail;
    c->ready = 1;
    rc = resp_len;

fail:
    noise_handshakestate_free(hs);
    return rc;
}

int telepad_noise_ready(const telepad_noise_t *c) {
    return c && c->ready && c->recv && c->send;
}

int telepad_noise_decrypt(telepad_noise_t *c,
                          const uint8_t *ct, int ct_len,
                          uint8_t *out_pt, int out_cap) {
    if (!telepad_noise_ready(c) || ct_len < 16 || ct_len > out_cap) return -1;
    memcpy(out_pt, ct, ct_len);
    NoiseBuffer b;
    noise_buffer_set_inout(b, out_pt, ct_len, out_cap);
    if (noise_cipherstate_decrypt(c->recv, &b) != NOISE_ERROR_NONE) return -1;
    return (int)b.size;
}

int telepad_noise_encrypt(telepad_noise_t *c,
                          const uint8_t *plain, int plain_len,
                          uint8_t *out_ct, int out_cap) {
    if (!telepad_noise_ready(c) || plain_len + 16 > out_cap) return -1;
    memcpy(out_ct, plain, plain_len);
    NoiseBuffer b;
    noise_buffer_set_inout(b, out_ct, plain_len, out_cap);
    if (noise_cipherstate_encrypt(c->send, &b) != NOISE_ERROR_NONE) return -1;
    return (int)b.size;
}

void telepad_noise_session_free(telepad_noise_t *c) {
    if (!c) return;
    if (c->send) { noise_cipherstate_free(c->send); c->send = NULL; }
    if (c->recv) { noise_cipherstate_free(c->recv); c->recv = NULL; }
    c->ready = 0;
}
