#ifndef TELEPAD_PROTOCOL_H
#define TELEPAD_PROTOCOL_H

#include <stdint.h>

// All multi-byte integers are transmitted in LITTLE-ENDIAN format.

// Protocol Message Types
#define MSG_TYPE_MOUSE_MOVE    0x01
#define MSG_TYPE_MOUSE_BUTTON  0x02
#define MSG_TYPE_SCROLL        0x03
#define MSG_TYPE_KEY_PRESS     0x04
#define MSG_TYPE_KEY_RELEASE   0x05
#define MSG_TYPE_TEXT_INPUT    0x06
#define MSG_TYPE_MEDIA_CMD     0x07
#define MSG_TYPE_VOLUME_CMD    0x08
#define MSG_TYPE_LOCK_SCREEN   0x09
#define MSG_TYPE_PING          0x0A
#define MSG_TYPE_PONG          0x0B
#define MSG_TYPE_AUTH          0xFE
#define MSG_TYPE_DISCONNECT    0xFF

// Mouse Buttons
#define BUTTON_LEFT   0x00
#define BUTTON_RIGHT  0x01
#define BUTTON_MIDDLE 0x02

// Media Actions
#define MEDIA_PLAY_PAUSE 0x00
#define MEDIA_NEXT       0x01
#define MEDIA_PREV       0x02
#define MEDIA_STOP       0x03

// Volume Directions
#define VOLUME_UP   0x00
#define VOLUME_DOWN 0x01
#define VOLUME_MUTE 0x02

// Modifier Bitmask
#define MOD_LCTRL  0x01
#define MOD_LSHIFT 0x02
#define MOD_LALT   0x04
#define MOD_LMETA  0x08
#define MOD_RCTRL  0x10
#define MOD_RSHIFT 0x20
#define MOD_RALT   0x40
#define MOD_RMETA  0x80

#pragma pack(push, 1)

// Common Header
typedef struct {
    uint8_t type;
} MsgHeader;

// 0x01: Mouse Move (5 bytes)
typedef struct {
    uint8_t type;
    int16_t dx;
    int16_t dy;
} MsgMouseMove;

// 0x02: Mouse Button (3 bytes)
typedef struct {
    uint8_t type;
    uint8_t button;
    uint8_t pressed; // 1 = pressed, 0 = released
} MsgMouseButton;

// 0x03: Scroll (3 bytes)
typedef struct {
    uint8_t type;
    int16_t delta;
} MsgScroll;

// 0x04 / 0x05: Key Press/Release (4 bytes)
typedef struct {
    uint8_t type;
    uint16_t keycode;
    uint8_t modifiers;
} MsgKey;

// 0x08: Volume Command (2 bytes)
typedef struct {
    uint8_t type;
    uint8_t direction;
} MsgVolume;

// 0x07: Media Command (2 bytes + optional payload)
typedef struct {
    uint8_t type;
    uint8_t action;
} MsgMedia;

#pragma pack(pop)

#endif // TELEPAD_PROTOCOL_H
