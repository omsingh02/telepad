package com.omsingh.telepad.core.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sanity checks for the report descriptor layout. We can't actually transmit
 * over Bluetooth in unit tests, but we can verify the byte layouts of report
 * sizes and consumer code constants against the spec values.
 */
class HidReportEncodingTest {

    @Test
    fun `report sizes match descriptor`() {
        assertEquals(6, HidReportDescriptor.MOUSE_REPORT_SIZE)
        assertEquals(8, HidReportDescriptor.KEYBOARD_REPORT_SIZE)
        assertEquals(2, HidReportDescriptor.CONSUMER_REPORT_SIZE)
    }

    @Test
    fun `report IDs are 1-2-3 mouse-keyboard-consumer`() {
        assertEquals(1, HidReportDescriptor.REPORT_ID_MOUSE)
        assertEquals(2, HidReportDescriptor.REPORT_ID_KEYBOARD)
        assertEquals(3, HidReportDescriptor.REPORT_ID_CONSUMER)
    }

    @Test
    fun `consumer codes match HID Usage Tables 1_4`() {
        // Source: USB HID Usage Tables 1.4, Section 15 (Consumer Page 0x0C)
        assertEquals(0x00CD, HidReportDescriptor.CONSUMER_PLAY_PAUSE)
        assertEquals(0x00B5, HidReportDescriptor.CONSUMER_NEXT_TRACK)
        assertEquals(0x00B6, HidReportDescriptor.CONSUMER_PREV_TRACK)
        assertEquals(0x00B7, HidReportDescriptor.CONSUMER_STOP)
        assertEquals(0x00E9, HidReportDescriptor.CONSUMER_VOLUME_UP)
        assertEquals(0x00EA, HidReportDescriptor.CONSUMER_VOLUME_DOWN)
        assertEquals(0x00E2, HidReportDescriptor.CONSUMER_MUTE)
    }

    @Test
    fun `descriptor starts with Mouse Usage Page byte 0x05 0x01`() {
        // First two bytes must be Usage Page (Generic Desktop) = 05 01
        // per descriptor spec convention.
        assertEquals(0x05, HidReportDescriptor.DESCRIPTOR[0].toInt() and 0xFF)
        assertEquals(0x01, HidReportDescriptor.DESCRIPTOR[1].toInt() and 0xFF)
    }
}
