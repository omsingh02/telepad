package com.omsingh.telepad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.omsingh.telepad.ui.theme.Dimens

/**
 * Manual IP+port connect dialog.
 *
 * Used when discovery isn't finding the PC (e.g. user is on a router that
 * blocks UDP multicast, or PC and phone are on different subnets connected
 * via VPN). All fields validated client-side before the Connect button enables.
 *
 * The IP regex below accepts dotted-quad IPv4 only. Hostnames and IPv6 are out
 * of scope for v1 — Telepad is a same-LAN tool; manual IP is the fallback for
 * when discovery breaks, and dotted-quad covers ~100% of that case.
 */
@Composable
fun ManualConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int) -> Unit,
) {
    var ip by remember { mutableStateOf("192.168.") }
    var portText by remember { mutableStateOf("5000") }

    val ipValid = remember(ip) {
        ip.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) &&
        ip.split('.').all { it.toIntOrNull()?.let { v -> v in 0..255 } == true }
    }
    val portValid = remember(portText) {
        portText.toIntOrNull()?.let { it in 1..65535 } == true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                Text(
                    "Enter the IP address shown in your PC's Telepad window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.filter { c -> c.isDigit() || c == '.' }.take(15) },
                    label = { Text("IP address") },
                    singleLine = true,
                    isError = ip.isNotBlank() && !ipValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = portText.isNotBlank() && !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = ipValid && portValid,
                onClick = { onConnect(ip, portText.toInt()) }
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
