package com.omsingh.telepad.ui.screens

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.omsingh.telepad.R
import com.omsingh.telepad.connection.BluetoothAvailability
import com.omsingh.telepad.connection.BluetoothDeviceInfo
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.platform.Links
import com.omsingh.telepad.core.trust.DeviceEntry
import com.omsingh.telepad.core.wifi.PairingInvite
import com.omsingh.telepad.ui.Fixtures
import com.omsingh.telepad.ui.screens.devices.DevicesActions
import com.omsingh.telepad.ui.screens.devices.DevicesScreen
import com.omsingh.telepad.ui.screens.devices.DevicesUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class DevicesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Recorder : DevicesActions {
        val connected = mutableListOf<DeviceEntry>()
        val forgotten = mutableListOf<DeviceEntry>()
        val addresses = mutableListOf<Pair<String, Int>>()
        val bluetooth = mutableListOf<BluetoothDeviceInfo>()
        var refreshes = 0
        var disconnects = 0
        var retries = 0
        var opened = 0
        override fun connect(entry: DeviceEntry) { connected += entry }
        override fun forget(entry: DeviceEntry) { forgotten += entry }
        override fun refresh() { refreshes++ }
        override fun disconnect() { disconnects++ }
        override fun retry() { retries++ }
        override fun connectToAddress(host: String, port: Int) { addresses += host to port }
        override fun pairWithInvite(invite: PairingInvite) {}
        override fun connectBluetooth(device: BluetoothDeviceInfo) { bluetooth += device }
        override fun refreshBluetooth() {}
        override fun confirmPairing() {}
        override fun dismissPairing() {}
    }

    private val actions = Recorder()
    private val platform = RecordingPlatform()

    @Before
    fun setUp() = noAnimations()

    private var scans = 0

    private fun show(state: DevicesUiState, onOpenRemote: () -> Unit = {}) =
        compose.show(platform) { DevicesScreen(state, actions, onOpenRemote, nowMs = Fixtures.NOW, onScan = { scans++ }) }

    private fun openAddSheet() {
        // With PCs listed, the way in is the button that floats; with none, the empty state has its own.
        val button = compose.onAllNodesWithText(string(R.string.devices_add), useUnmergedTree = true)
        if (button.fetchSemanticsNodes().isNotEmpty()) {
            button[0].performClick()
        } else {
            compose.onNodeWithText(string(R.string.devices_other_ways)).performClick()
        }
        compose.settle()
    }

    private fun openOverflowOfFirstRow() {
        compose.onAllNodesWithContentDescription(string(R.string.action_more))[0].performClick()
        compose.settle()
    }

    private fun chooseForget() {
        compose.onNode(hasText(string(R.string.action_forget)) and hasAnyAncestor(isPopup())).performClick()
        compose.settle()
    }

    @Test
    fun `with no PC yet the first thing offered is to scan its code`() {
        show(DevicesUiState(searching = true))
        compose.onNodeWithText(string(R.string.devices_scan)).assertIsDisplayed().performClick()
        assertEquals(1, scans)
    }

    @Test
    fun `the add sheet leads with scanning, and closing it for the scanner`() {
        show(Fixtures.devicesList)
        openAddSheet()
        compose.onNodeWithText(string(R.string.add_scan)).assertExists()
        compose.onNodeWithText(string(R.string.add_scan_hint)).assertExists()

        compose.onNodeWithText(string(R.string.add_scan)).performClick()
        compose.settle()
        assertEquals(1, scans)
        // The sheet is gone: the scanner takes the whole screen.
        compose.onNodeWithText(string(R.string.add_scan)).assertDoesNotExist()
    }

    @Test
    fun `tapping a paired PC connects to it`() {
        show(Fixtures.devicesList)
        compose.onNodeWithText("Desk PC").performClick()
        assertEquals(listOf(Fixtures.desk), actions.connected)
    }

    @Test
    fun `a PC that is not paired has a button to pair it`() {
        show(Fixtures.devicesList)
        compose.onNodeWithText(string(R.string.devices_pair)).performClick()
        assertEquals(listOf(Fixtures.guest), actions.connected)
    }

    @Test
    fun `paired and new PCs are listed in their own sections`() {
        show(Fixtures.devicesList)
        compose.onNodeWithText(string(R.string.devices_section_yours)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.devices_section_nearby)).assertIsDisplayed()
    }

    @Test
    fun `an offline PC says so`() {
        show(Fixtures.devicesList)
        compose.onNodeWithText(string(R.string.devices_status_offline)).assertIsDisplayed()
    }

    @Test
    fun `forgetting a PC asks first`() {
        show(Fixtures.devicesList)
        openOverflowOfFirstRow()
        chooseForget()
        // Nothing is forgotten until the question is answered.
        assertTrue(actions.forgotten.isEmpty())
        compose.onNodeWithText(string(R.string.devices_forget_title, "Desk PC")).assertIsDisplayed()

        compose.onNode(hasText(string(R.string.action_forget)) and hasAnyAncestor(isDialog())).performClick()
        compose.settle()
        assertEquals(listOf(Fixtures.desk), actions.forgotten)
    }

    @Test
    fun `cancelling the question forgets nothing`() {
        show(Fixtures.devicesList)
        openOverflowOfFirstRow()
        chooseForget()
        compose.onNodeWithText(string(R.string.action_cancel)).performClick()
        compose.settle()
        assertTrue(actions.forgotten.isEmpty())
    }

    @Test
    fun `a connection shows who it is to and leads to the remote`() {
        var opened = 0
        show(Fixtures.devicesConnected, onOpenRemote = { opened++ })
        compose.onNodeWithText(string(R.string.devices_connected_to, "Desk PC")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.devices_open_remote)).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(string(R.string.devices_disconnect)).performClick()
        assertEquals(1, actions.disconnects)
    }

    @Test
    fun `a failure explains itself and offers another try`() {
        val failed = ConnectionState.Failed("Desk PC", ConnectionState.Transport.WIFI, FailureReason.UNREACHABLE)
        show(Fixtures.devicesList.copy(connection = failed))
        compose.onNodeWithText(string(R.string.failure_unreachable_title, "Desk PC")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_retry)).performClick()
        assertEquals(1, actions.retries)
        compose.onNodeWithText(string(R.string.action_close)).performClick()
        assertEquals(1, actions.disconnects)
    }

    @Test
    fun `with nothing found the screen says how to begin`() {
        show(DevicesUiState())
        compose.onNodeWithText(string(R.string.devices_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.devices_get_desktop)).performClick()
        assertEquals(listOf("open:${Links.DOWNLOAD}"), platform.calls)
    }

    @Test
    fun `an address is checked before it can be used`() {
        show(DevicesUiState())
        openAddSheet()
        compose.settle()

        compose.onNodeWithText(string(R.string.add_connect)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.add_address_label)).performTextInput("not an address!")
        compose.settle()
        compose.onNodeWithText(string(R.string.add_connect)).assertIsNotEnabled()
        assertTrue("nothing was sent", actions.addresses.isEmpty())
    }

    @Test
    fun `a good address connects with the default port`() {
        show(DevicesUiState())
        openAddSheet()
        compose.settle()
        compose.onNodeWithText(string(R.string.add_address_label)).performTextInput("192.168.1.20")
        compose.settle()
        compose.onNodeWithText(string(R.string.add_connect)).assertIsEnabled().performClick()
        compose.settle()
        assertEquals(listOf("192.168.1.20" to 5000), actions.addresses)
    }

    @Test
    fun `refresh asks discovery to look again`() {
        show(Fixtures.devicesList)
        compose.onNodeWithContentDescription(string(R.string.devices_search_again)).performClick()
        assertEquals(1, actions.refreshes)
    }

    @Test
    fun `bluetooth devices can be chosen when bluetooth is ready`() {
        show(Fixtures.devicesList.copy(bluetoothDevices = Fixtures.bluetoothDevices, bluetooth = BluetoothAvailability.READY))
        openAddSheet()
        compose.settle()
        compose.onNodeWithText(string(R.string.add_tab_bluetooth)).performClick()
        compose.settle()
        compose.onNodeWithText("Office laptop").performClick()
        compose.settle()
        assertEquals(listOf("F4:5C:89:AB:12:90"), actions.bluetooth.map { it.address })
    }

    @Test
    fun `bluetooth without permission offers to ask for it`() {
        show(DevicesUiState(bluetooth = BluetoothAvailability.NEEDS_PERMISSION))
        openAddSheet()
        compose.settle()
        compose.onNodeWithText(string(R.string.add_tab_bluetooth)).performClick()
        compose.settle()
        compose.onNodeWithText(string(R.string.add_bt_grant)).performClick()
        assertEquals(listOf("bluetooth-permission"), platform.calls)
    }
}
