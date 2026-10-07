package com.omsingh.telepad.ui.screens.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.ui.theme.Spacing

/**
 * The line the phone's own keyboard types into. What is typed here is sent to the PC
 * ([com.omsingh.telepad.core.input.TypingBridge] decides what and when), so there is nothing to
 * press "send" on, and the PC is where the text ends up.
 *
 * With [live] on, the phone's keyboard is asked for no suggestions, so every key goes to the PC
 * the moment it is pressed. Hidden text is masked and keeps suggestions off, for passwords.
 */
@Composable
fun TypingField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    live: Boolean,
    hidden: Boolean,
    onBackspaceWithNothingToDelete: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val description = stringResource(R.string.keys_field_description)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = Spacing.lg, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = Spacing.md), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(
                        stringResource(R.string.keys_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = !live && !hidden,
                        keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Text,
                    ),
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp)
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = description }
                        // A phone keyboard sends Backspace as a key event when there is nothing to delete,
                        // and the PC may well have something there.
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Backspace && event.type == KeyEventType.KeyDown && value.text.isEmpty()) {
                                onBackspaceWithNothingToDelete()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
            trailing()
        }
    }
}
