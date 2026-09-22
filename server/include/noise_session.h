#ifndef TELEPAD_NOISE_SESSION_H
#define TELEPAD_NOISE_SESSION_H

#include <stdint.h>
#include <stddef.h>

/* Forward-declare the noise-c types so this header doesn't drag the whole
   library into every translation unit. */
typedef struct NoiseCipherState_s NoiseCipherState;

#define NOISE_IK_MSG1_LEN 96
#define NOISE_IK_MSG2_LEN 48

typedef struct {
    NoiseCipherState *send;
    NoiseCipherState *recv;
    int               ready;
    uint8_t           client_pubkey[32]; /* Extracted from Noise IK msg 1 */
} telepad_noise_t;

/* Process-wide init / cleanup. Loads or generates the server's long-term
   X25519 keypair (stored under %APPDATA%\Telepad\static.key on Windows). */
int  telepad_noise_init(void);
void telepad_noise_cleanup(void);

/* Print the server pubkey + 12-char fingerprint to stdout for pairing. */
void telepad_noise_print_pairing_banner(void);

/* Fill out with the 32-byte server static public key. */
void telepad_noise_get_pubkey(uint8_t out[32]);

/* Respond to a Noise IK msg 1 from `msg1` (length must be NOISE_IK_MSG1_LEN).
   On success, writes the 48-byte msg 2 into out_resp and sets up the cipher
   states inside *c. Returns the number of bytes written, or <0 on error. */
int  telepad_noise_handshake_respond(telepad_noise_t *c,
                                     const uint8_t *msg1, int msg1_len,
                                     uint8_t *out_resp, int out_cap);

int  telepad_noise_ready(const telepad_noise_t *c);

/* In-place authenticated decryption. Returns plaintext length or <0 on MAC
   failure / replay. */
int  telepad_noise_decrypt(telepad_noise_t *c,
                           const uint8_t *ct, int ct_len,
                           uint8_t *out_pt, int out_cap);

/* In-place authenticated encryption. Returns ciphertext length (= plain + 16)
   or <0 on error. */
int  telepad_noise_encrypt(telepad_noise_t *c,
                           const uint8_t *plain, int plain_len,
                           uint8_t *out_ct, int out_cap);

/* Tear down session keys (called when the client disconnects). */
void telepad_noise_session_free(telepad_noise_t *c);

#endif
