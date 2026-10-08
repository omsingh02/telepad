package com.omsingh.telepad.ui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.wifi.NetworkHint
import com.omsingh.telepad.ui.components.failureBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a PC that cannot be reached is told, depending on what the phone's own network says. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class FailureTextTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = noAnimations()

    private fun body(hint: NetworkHint) {
        compose.show { Text(failureBody(FailureReason.UNREACHABLE, "deed", hint)) }
    }

    @Test
    fun `without a hint the text still mentions the firewall`() {
        body(NetworkHint.Unknown)
        compose.onNodeWithText(string(R.string.failure_unreachable_body)).assertIsDisplayed()
        assert(string(R.string.failure_unreachable_body).contains("firewall"))
    }

    @Test
    fun `a PC on the same network is blamed on the PC's firewall, with its address`() {
        body(NetworkHint.SameNetwork("192.168.0.109"))
        compose.onNodeWithText(string(R.string.failure_unreachable_same_network, "deed", "192.168.0.109")).assertIsDisplayed()
    }

    @Test
    fun `a PC on another network names both addresses`() {
        body(NetworkHint.DifferentNetwork("192.168.1.20", "192.168.0.109"))
        compose.onNodeWithText(string(R.string.failure_unreachable_other_network, "deed", "192.168.0.109", "192.168.1.20")).assertIsDisplayed()
    }

    @Test
    fun `a phone that is on no network says to join one`() {
        body(NetworkHint.NoNetwork)
        compose.onNodeWithText(string(R.string.failure_unreachable_no_network, "deed")).assertIsDisplayed()
    }

    @Test
    fun `the hint changes nothing about the other reasons`() {
        compose.show { Text(failureBody(FailureReason.NOT_PAIRED, "deed", NetworkHint.SameNetwork("192.168.0.109"))) }
        compose.onNodeWithText(string(R.string.failure_not_paired_body)).assertIsDisplayed()
    }
}
