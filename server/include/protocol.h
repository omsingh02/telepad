#ifndef TELEPAD_PROTOCOL_H
#define TELEPAD_PROTOCOL_H

#include <stdint.h>

/*
 * Telepad wire protocol.
 *
 * All multi-byte integers are LITTLE-ENDIAN.
 *
 * Wire envelope (first byte of every datagram):
 *   0xC0  WIRE_HANDSHAKE_INIT      (phone → PC) — 96-byte Noise IK msg 1
 *   0xC1  WIRE_HANDSHAKE_RESP      (PC → phone) — 48-byte Noise IK msg 2
 *   0xC2  WIRE_TRANSPORT           — ciphertext + 16-byte Poly1305 tag
 *   0xC3  WIRE_DISCOVERY_PROBE     — opaque 8-byte magic
 *   0xC4  WIRE_DISCOVERY_REPLY     — "TELEPAD_PONG:<hostname>"
 *   0xC5  WIRE_PAIRING_INTRO_REQ   — bare query for server's static pubkey
 *   0xC6  WIRE_PAIRING_INTRO_RESP  — 32-byte X25519 pubkey
 */

#define WIRE_HANDSHAKE_INIT      0xC0
#define WIRE_HANDSHAKE_RESP      0xC1
#define WIRE_TRANSPORT           0xC2
#define WIRE_DISCOVERY_PROBE     0xC3
#define WIRE_DISCOVERY_REPLY     0xC4
#define WIRE_PAIRING_INTRO_REQ   0xC5
#define WIRE_PAIRING_INTRO_RESP  0xC6

/* Plaintext message types inside a WIRE_TRANSPORT envelope. */
#define MSG_TYPE_MOUSE_MOVE      0x01
#define MSG_TYPE_MOUSE_BUTTON    0x02
#define MSG_TYPE_SCROLL          0x03
#define MSG_TYPE_KEY_PRESS       0x04
#define MSG_TYPE_KEY_RELEASE     0x05
#define MSG_TYPE_TEXT_INPUT      0x06
#define MSG_TYPE_MEDIA_CMD       0x07
#define MSG_TYPE_VOLUME_CMD      0x08
#define MSG_TYPE_LOCK_SCREEN     0x09
#define MSG_TYPE_CLIPBOARD_GET   0x0E
#define MSG_TYPE_CLIPBOARD_SET   0x0F
#define MSG_TYPE_LAUNCH_ACTION   0x10
#define MSG_TYPE_NOW_PLAYING_Q   0x11

/* Server → client transport-layer reply types. */
#define MSG_TYPE_CLIPBOARD_DATA  0x80
#define MSG_TYPE_NOW_PLAYING     0x81

/* Network */
#define TELEPAD_PORT             5000
#define TELEPAD_MULTICAST_GROUP  "239.255.42.67"

/* Discovery magic (8 bytes, opaque). */
#define TELEPAD_DISCOVERY_MAGIC_LEN 8
extern const uint8_t TELEPAD_DISCOVERY_MAGIC[TELEPAD_DISCOVERY_MAGIC_LEN];

/* Mouse buttons */
#define BUTTON_LEFT   0x00
#define BUTTON_RIGHT  0x01
#define BUTTON_MIDDLE 0x02

/* Media actions (must match Android InputEvent.MediaAction.ordinal) */
#define MEDIA_PLAY_PAUSE 0x00
#define MEDIA_NEXT       0x01
#define MEDIA_PREV       0x02
#define MEDIA_STOP       0x03

/* Volume directions */
#define VOLUME_UP   0x00
#define VOLUME_DOWN 0x01
#define VOLUME_MUTE 0x02

/* Launch actions (must match Android InputEvent.SystemAction.ordinal) */
#define LAUNCH_SHOW_DESKTOP    0x00
#define LAUNCH_TASK_VIEW       0x01
#define LAUNCH_BROWSER         0x02
#define LAUNCH_FILE_MANAGER    0x03
#define LAUNCH_TASK_MANAGER    0x04
#define LAUNCH_SCREENSHOT      0x05

/* Modifier bitmask (matches HID report byte 0). */
#define MOD_LCTRL  0x01
#define MOD_LSHIFT 0x02
#define MOD_LALT   0x04
#define MOD_LMETA  0x08
#define MOD_RCTRL  0x10
#define MOD_RSHIFT 0x20
#define MOD_RALT   0x40
#define MOD_RMETA  0x80

/* Wire layouts. Plaintext (post-decrypt) packed structs. */
#pragma pack(push, 1)

typedef struct { uint8_t type; int16_t dx; int16_t dy; }            MsgMouseMove;
typedef struct { uint8_t type; uint8_t button; uint8_t pressed; }   MsgMouseButton;
typedef struct { uint8_t type; int16_t delta; }                     MsgScroll;
typedef struct { uint8_t type; uint16_t keycode; uint8_t mods; }    MsgKey;
typedef struct { uint8_t type; uint8_t action; }                    MsgMedia;
typedef struct { uint8_t type; uint8_t direction; }                 MsgVolume;
typedef struct { uint8_t type; uint8_t action; }                    MsgLaunch;
/* MsgText / MsgClipboardSet have a u16 length prefix; treated specially. */

#pragma pack(pop)

#endif /* TELEPAD_PROTOCOL_H */
