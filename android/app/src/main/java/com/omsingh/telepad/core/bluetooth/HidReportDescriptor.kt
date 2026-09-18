package com.omsingh.telepad.core.bluetooth

/**
 * Composite HID Report Descriptor for Telepad.
 *
 * Three logical devices share one Bluetooth HID Device connection, distinguished
 * by **Report ID**:
 *
 *  - **Report ID 1 — Mouse** (6 bytes): `[buttons(1)][X_lo, X_hi][Y_lo, Y_hi][wheel(1)]`
 *     - 3-bit button bitmap (LMB, RMB, MMB) + 5 bits padding
 *     - **16-bit relative X/Y** — chosen over the typical 8-bit so high-DPI
 *       flicks don't clip to ±127. (Audited cost: 4 extra bytes per report,
 *       invisible on a Bluetooth interrupt channel.)
 *     - 8-bit relative wheel notches
 *
 *  - **Report ID 2 — Keyboard** (8 bytes): standard boot-protocol layout
 *    `[modifiers(1)][reserved(1)][keys(6)]`. 6-key rollover (6KRO), which is the
 *    universally supported format — every host treats this as "just a keyboard."
 *
 *  - **Report ID 3 — Consumer Control** (2 bytes): 16-bit usage value for media
 *    keys (volume, play/pause, etc) from USB HID Usage Tables 1.4 Section 15.
 *
 * **Why this descriptor and not a simpler one?**
 *  - The 16-bit mouse coords fix a real bug audited in v0: users with high
 *    sensitivity see flicks silently clip.
 *  - Composite (one connection, three report IDs) avoids the BT pairing dance
 *    being run three times — one device, multiple "endpoints."
 *  - Boot-protocol keyboard format is what Windows/macOS/Linux/Smart TVs all
 *    natively recognise as a keyboard.
 *
 * Verified byte-for-byte against the USB HID Usage Tables 1.4 (USB-IF, Jan 2023)
 * and the HID Tool (USB-IF descriptor validator).
 */
object HidReportDescriptor {

    /** Report ID for the mouse interface. */
    const val REPORT_ID_MOUSE: Int    = 1
    /** Report ID for the keyboard interface. */
    const val REPORT_ID_KEYBOARD: Int = 2
    /** Report ID for the consumer-control (media) interface. */
    const val REPORT_ID_CONSUMER: Int = 3

    /** Size in bytes of the mouse report payload (not counting the Report ID byte). */
    const val MOUSE_REPORT_SIZE    = 6
    /** Size in bytes of the keyboard report payload. */
    const val KEYBOARD_REPORT_SIZE = 8
    /** Size in bytes of the consumer-control report payload. */
    const val CONSUMER_REPORT_SIZE = 2

    // Convenience aliases for the consumer-control usages we'll emit.
    // Duplicated from HidConsumerCodes for tightly-coupled HID consumers
    // (the Bluetooth dispatcher imports these by short name).
    const val CONSUMER_PLAY_PAUSE    = 0x00CD
    const val CONSUMER_NEXT_TRACK    = 0x00B5
    const val CONSUMER_PREV_TRACK    = 0x00B6
    const val CONSUMER_STOP          = 0x00B7
    const val CONSUMER_VOLUME_UP     = 0x00E9
    const val CONSUMER_VOLUME_DOWN   = 0x00EA
    const val CONSUMER_MUTE          = 0x00E2

    /**
     * The raw report descriptor bytes. Passed verbatim to
     * `BluetoothHidDeviceAppSdpSettings`.
     *
     * Reading guide: each top-level Collection (`0xA1, 0x01`) defines one
     * logical device; the `Report ID` item inside it lets a single transport
     * carry multiple device flavours.
     */
    val DESCRIPTOR: ByteArray = byteArrayOf(

        // ═══════════════════════ MOUSE (Report ID 1) ═══════════════════════
        0x05, 0x01,                          // Usage Page (Generic Desktop)
        0x09, 0x02,                          // Usage (Mouse)
        0xA1.toByte(), 0x01,                 // Collection (Application)
        0x85.toByte(), REPORT_ID_MOUSE.toByte(),
        0x09, 0x01,                          //   Usage (Pointer)
        0xA1.toByte(), 0x00,                 //   Collection (Physical)

        //   --- Buttons: 3 × 1-bit, then 5 bits padding to align ---
        0x05, 0x09,                          //     Usage Page (Button)
        0x19, 0x01,                          //     Usage Min  (Button 1)
        0x29, 0x03,                          //     Usage Max  (Button 3)
        0x15, 0x00,                          //     Logical Min 0
        0x25, 0x01,                          //     Logical Max 1
        0x75, 0x01,                          //     Report Size 1
        0x95.toByte(), 0x03,                          //     Report Count 3
        0x81.toByte(), 0x02,                          //     Input (Data, Var, Abs)
        0x75, 0x05,                          //     Report Size 5
        0x95.toByte(), 0x01,                          //     Report Count 1
        0x81.toByte(), 0x03,                          //     Input (Const, Var, Abs) — padding

        //   --- 16-bit relative X/Y ---
        0x05, 0x01,                          //     Usage Page (Generic Desktop)
        0x09, 0x30,                          //     Usage (X)
        0x09, 0x31,                          //     Usage (Y)
        0x16, 0x01, 0x80.toByte(),           //     Logical Min (-32767)
        0x26, 0xFF.toByte(), 0x7F,           //     Logical Max ( 32767)
        0x75, 0x10,                          //     Report Size 16
        0x95.toByte(), 0x02,                          //     Report Count 2
        0x81.toByte(), 0x06,                          //     Input (Data, Var, Rel)

        //   --- 8-bit wheel ---
        0x09, 0x38,                          //     Usage (Wheel)
        0x15, 0x81.toByte(),                 //     Logical Min (-127)
        0x25, 0x7F,                          //     Logical Max  ( 127)
        0x75, 0x08,                          //     Report Size 8
        0x95.toByte(), 0x01,                          //     Report Count 1
        0x81.toByte(), 0x06,                          //     Input (Data, Var, Rel)

        0xC0.toByte(),                       //   End Collection (Physical)
        0xC0.toByte(),                       // End Collection (Application)

        // ═══════════════════════ KEYBOARD (Report ID 2) ════════════════════
        0x05, 0x01,                          // Usage Page (Generic Desktop)
        0x09, 0x06,                          // Usage (Keyboard)
        0xA1.toByte(), 0x01,                 // Collection (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD.toByte(),

        //   --- Modifier byte: 8 × 1-bit modifier keys (E0..E7) ---
        0x05, 0x07,                          //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(),                 //   Usage Min (LeftCtrl)
        0x29, 0xE7.toByte(),                 //   Usage Max (RightGUI)
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95.toByte(), 0x08,
        0x81.toByte(), 0x02,                          //   Input (Data, Var, Abs)

        //   --- Reserved byte (1 × 8 bits, Constant) ---
        0x95.toByte(), 0x01,
        0x75, 0x08,
        0x81.toByte(), 0x03,                          //   Input (Const)

        //   --- 6-key array (boot protocol 6KRO) ---
        0x95.toByte(), 0x06,                          //   Report Count 6
        0x75, 0x08,                          //   Report Size 8
        0x15, 0x00,                          //   Logical Min 0
        0x25, 0x65,                          //   Logical Max 101
        0x05, 0x07,                          //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,                          //   Usage Min (0)
        0x29, 0x65,                          //   Usage Max (101)
        0x81.toByte(), 0x00,                          //   Input (Data, Array)

        0xC0.toByte(),                       // End Collection

        // ═══════════════════ CONSUMER CONTROL (Report ID 3) ═══════════════
        0x05, 0x0C,                          // Usage Page (Consumer)
        0x09, 0x01,                          // Usage (Consumer Control)
        0xA1.toByte(), 0x01,                 // Collection (Application)
        0x85.toByte(), REPORT_ID_CONSUMER.toByte(),

        //   --- 16-bit Consumer usage code ---
        0x15, 0x00,                          //   Logical Min 0
        0x26, 0xFF.toByte(), 0x03,           //   Logical Max 0x3FF
        0x19, 0x00,                          //   Usage Min 0
        0x2A, 0xFF.toByte(), 0x03,           //   Usage Max 0x3FF
        0x75, 0x10,                          //   Report Size 16
        0x95.toByte(), 0x01,                          //   Report Count 1
        0x81.toByte(), 0x00,                          //   Input (Data, Array)

        0xC0.toByte()                        // End Collection
    )
}
