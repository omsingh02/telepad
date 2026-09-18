#ifndef TELEPAD_PAIRING_STORE_H
#define TELEPAD_PAIRING_STORE_H

#include <stdint.h>

/* Trusted-client persistence. Holds the set of client static pubkeys we've
   completed at least one handshake with. Persisted on disk so the user
   doesn't have to re-trust on every server restart.
 
   Storage: %APPDATA%\Telepad\trusted_clients.bin
   Format: tightly-packed sequence of 32-byte X25519 public keys. No header.
   This is intentionally trivial — a bigger format would be over-engineered
   for a list of binary keys with no metadata. */

int  pairing_store_init(void);
void pairing_store_cleanup(void);

/* Returns 1 if pubkey is known, 0 if not. */
int  pairing_store_is_trusted(const uint8_t pubkey[32]);

/* Adds pubkey to the trusted set. Idempotent. */
void pairing_store_trust(const uint8_t pubkey[32]);

/* Removes pubkey. No-op if not present. */
void pairing_store_forget(const uint8_t pubkey[32]);

/* Removes every trusted client. Used by the server CLI --reset flag. */
void pairing_store_forget_all(void);

/* Returns the number of trusted clients. */
int  pairing_store_count(void);

#endif
