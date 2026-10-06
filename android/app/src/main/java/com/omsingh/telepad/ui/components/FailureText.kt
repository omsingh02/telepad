package com.omsingh.telepad.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.FailureReason

/** What to call the PC when the connection has no name for it. */
@Composable
private fun nameOrFallback(name: String?): String = name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.generic_your_pc)

/** A short headline for why a connection failed. */
@Composable
fun failureTitle(reason: FailureReason, deviceName: String?): String {
    val name = nameOrFallback(deviceName)
    return when (reason) {
        FailureReason.UNREACHABLE -> stringResource(R.string.failure_unreachable_title, name)
        FailureReason.NOT_PAIRED -> stringResource(R.string.failure_not_paired_title, name)
        FailureReason.KEY_CHANGED -> stringResource(R.string.failure_key_changed_title, name)
        FailureReason.HANDSHAKE_FAILED -> stringResource(R.string.failure_handshake_title)
        FailureReason.CONNECTION_LOST -> stringResource(R.string.failure_lost_title, name)
        FailureReason.BLUETOOTH_UNSUPPORTED -> stringResource(R.string.failure_bt_unsupported_title)
        FailureReason.BLUETOOTH_DISABLED -> stringResource(R.string.failure_bt_disabled_title)
        FailureReason.BLUETOOTH_PERMISSION -> stringResource(R.string.failure_bt_permission_title)
        FailureReason.BLUETOOTH_FAILED -> stringResource(R.string.failure_bt_failed_title)
    }
}

/** What the person can do about it. */
@Composable
fun failureBody(reason: FailureReason, deviceName: String?): String {
    val name = nameOrFallback(deviceName)
    return when (reason) {
        FailureReason.UNREACHABLE -> stringResource(R.string.failure_unreachable_body)
        FailureReason.NOT_PAIRED -> stringResource(R.string.failure_not_paired_body)
        FailureReason.KEY_CHANGED -> stringResource(R.string.failure_key_changed_body)
        FailureReason.HANDSHAKE_FAILED -> stringResource(R.string.failure_handshake_body, name)
        FailureReason.CONNECTION_LOST -> stringResource(R.string.failure_lost_body)
        FailureReason.BLUETOOTH_UNSUPPORTED -> stringResource(R.string.failure_bt_unsupported_body)
        FailureReason.BLUETOOTH_DISABLED -> stringResource(R.string.failure_bt_disabled_body)
        FailureReason.BLUETOOTH_PERMISSION -> stringResource(R.string.failure_bt_permission_body)
        FailureReason.BLUETOOTH_FAILED -> stringResource(R.string.failure_bt_failed_body, name)
    }
}
