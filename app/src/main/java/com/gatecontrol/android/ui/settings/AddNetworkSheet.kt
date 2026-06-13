package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Validation
import com.gatecontrol.android.ui.components.ios.IosPrimaryButton

/**
 * Bottom sheet for adding a custom CIDR + label to the split-tunnel network list.
 *
 * The "Add" button is enabled only when the CIDR validates and isn't already
 * in [existing]. A short error hint appears below the field for invalid input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkSheet(
    existing: List<NetworkEntry>,
    onAdd: (NetworkEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var cidr by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    val cidrValid by remember {
        derivedStateOf {
            cidr.isNotBlank() && Validation.parseSplitRoutes(cidr).isNotEmpty()
        }
    }
    val isDuplicate by remember(cidr, existing) {
        derivedStateOf { existing.any { it.cidr == cidr.trim() } }
    }
    val canAdd = cidrValid && !isDuplicate

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.split_tunnel_add_network),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
            )

            OutlinedTextField(
                value = cidr,
                onValueChange = { cidr = it.trim() },
                label = { Text(stringResource(R.string.split_tunnel_cidr_label)) },
                placeholder = { Text("10.0.1.0/24") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = cidr.isNotBlank() && (!cidrValid || isDuplicate),
                supportingText = {
                    when {
                        cidr.isBlank() -> Unit
                        !cidrValid -> Text(stringResource(R.string.split_tunnel_cidr_invalid))
                        isDuplicate -> Text(stringResource(R.string.split_tunnel_cidr_duplicate))
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Next,
                ),
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.split_tunnel_label)) },
                placeholder = { Text(stringResource(R.string.split_tunnel_label_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
            )

            Spacer(Modifier.height(24.dp))

            IosPrimaryButton(
                text = stringResource(R.string.split_tunnel_add_network),
                enabled = canAdd,
                onClick = {
                    onAdd(NetworkEntry(cidr.trim(), label.trim().ifBlank { cidr.trim() }))
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
