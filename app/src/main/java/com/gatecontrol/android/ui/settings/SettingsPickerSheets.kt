package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.ios.IosListSection
import com.gatecontrol.android.ui.components.ios.IosNavigationRow
import com.gatecontrol.android.ui.components.ios.IosPrimaryButton
import com.gatecontrol.android.ui.components.ios.IosTintedButton
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Account / server editor sheet. Triggered from the Settings "Server" or
 * "API Token" rows. Lets the user update both fields, test the connection,
 * and (optionally) import a .conf file. Replaces the inline server form that
 * used to sit on the Settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorSheet(
    currentUrl: String,
    currentToken: String,
    connectionTestStatus: ConnectionTestStatus,
    isLoading: Boolean,
    onTest: (url: String, token: String) -> Unit,
    onSave: (url: String, token: String) -> Unit,
    onImportFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var urlField by remember(currentUrl) {
        mutableStateOf(currentUrl.removePrefix("https://").removePrefix("http://"))
    }
    var tokenField by remember(currentToken) { mutableStateOf(currentToken) }
    var tokenVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.settings_section_account),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
            )

            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it.removePrefix("https://").removePrefix("http://") },
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
                prefix = { Text("https://") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                colors = OutlinedTextFieldDefaults.colors(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = tokenField,
                onValueChange = { tokenField = it },
                label = { Text(stringResource(R.string.settings_api_token)) },
                placeholder = { Text(stringResource(R.string.settings_api_token_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                                      else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Default.VisibilityOff
                                          else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )

            Spacer(Modifier.height(8.dp))

            when (connectionTestStatus) {
                ConnectionTestStatus.Success -> Text(
                    text = stringResource(R.string.settings_connection_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
                ConnectionTestStatus.Failure -> Text(
                    text = stringResource(R.string.settings_connection_fail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp),
                )
                ConnectionTestStatus.Testing -> Text(
                    text = stringResource(R.string.settings_connection_testing),
                    style = MaterialTheme.typography.bodySmall,
                    color = GateControlTheme.extraColors.warn,
                    modifier = Modifier.padding(start = 4.dp),
                )
                else -> Unit
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IosTintedButton(
                    text = stringResource(R.string.settings_test_connection),
                    onClick = { onTest("https://$urlField", tokenField) },
                    enabled = connectionTestStatus != ConnectionTestStatus.Testing
                        && urlField.isNotBlank() && tokenField.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.weight(1f)) {
                    IosPrimaryButton(
                        text = stringResource(R.string.settings_save_register),
                        onClick = {
                            onSave("https://$urlField", tokenField)
                            onDismiss()
                        },
                        loading = isLoading,
                        enabled = urlField.isNotBlank() && tokenField.isNotBlank(),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Alternative entry: import from .conf file
            IosListSection {
                IosNavigationRow(
                    title = stringResource(R.string.settings_import_file),
                    onClick = {
                        onImportFile()
                        onDismiss()
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Theme picker sheet — System / Light / Dark, displayed as iOS-style choose-one rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerSheet(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerSheet(
        title = stringResource(R.string.settings_theme),
        options = listOf(
            "system" to stringResource(R.string.settings_theme_system),
            "light" to stringResource(R.string.settings_theme_light),
            "dark" to stringResource(R.string.settings_theme_dark),
        ),
        current = current,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** Language picker sheet — single-choice rows for the two supported locales. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerSheet(
        title = stringResource(R.string.settings_language),
        options = listOf(
            "en" to "English",
            "de" to "Deutsch",
        ),
        current = current,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/**
 * Generic single-choice picker bottom sheet. Each row shows the option label
 * on the left; the currently selected row has a green check on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 16.dp),
            )
            IosListSection {
                options.forEachIndexed { idx, (value, label) ->
                    PickerRow(
                        label = label,
                        selected = value == current,
                        onClick = { onSelect(value) },
                        showDivider = idx < options.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    com.gatecontrol.android.ui.components.ios.IosListRow(
        onClick = onClick,
        showDivider = showDivider,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
