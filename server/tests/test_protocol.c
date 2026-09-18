#include "protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

/*
 * Sanity tests for wire-format constants and packed struct sizes.
 * If these fail, the server and the Android client will silently misparse.
 */

#define CHECK(cond) do {                                          \
    if (!(cond)) {                                                \
        fprintf(stderr, "FAIL: %s (line %d)\n", #cond, __LINE__); \
        return 1;                                                 \
    }                                                             \
} while (0)

int main(void) {
    /* Wire tags */
    CHECK(WIRE_HANDSHAKE_INIT     == 0xC0);
    CHECK(WIRE_HANDSHAKE_RESP     == 0xC1);
    CHECK(WIRE_TRANSPORT          == 0xC2);
    CHECK(WIRE_DISCOVERY_PROBE    == 0xC3);
    CHECK(WIRE_DISCOVERY_REPLY    == 0xC4);
    CHECK(WIRE_PAIRING_INTRO_REQ  == 0xC5);
    CHECK(WIRE_PAIRING_INTRO_RESP == 0xC6);

    /* Message types */
    CHECK(MSG_TYPE_MOUSE_MOVE   == 0x01);
    CHECK(MSG_TYPE_MOUSE_BUTTON == 0x02);
    CHECK(MSG_TYPE_SCROLL       == 0x03);
    CHECK(MSG_TYPE_KEY_PRESS    == 0x04);
    CHECK(MSG_TYPE_KEY_RELEASE  == 0x05);
    CHECK(MSG_TYPE_TEXT_INPUT   == 0x06);

    /* Discovery magic */
    CHECK(TELEPAD_DISCOVERY_MAGIC_LEN == 8);

    /* Packed struct sizes — these must match what the Android client serialises. */
    CHECK(sizeof(MsgMouseMove)   == 5); /* type + 2 i16 */
    CHECK(sizeof(MsgMouseButton) == 3);
    CHECK(sizeof(MsgScroll)      == 3);
    CHECK(sizeof(MsgKey)         == 4);
    CHECK(sizeof(MsgMedia)       == 2);
    CHECK(sizeof(MsgVolume)      == 2);
    CHECK(sizeof(MsgLaunch)      == 2);

    /* Discovery group is administratively-scoped (239.x), not reserved (224.0.0.x) */
    CHECK(strncmp(TELEPAD_MULTICAST_GROUP, "239.", 4) == 0);
    CHECK(TELEPAD_PORT == 5000);

    printf("protocol layout: OK\n");
    return 0;
}
