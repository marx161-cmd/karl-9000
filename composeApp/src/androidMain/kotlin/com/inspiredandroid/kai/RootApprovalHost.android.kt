package com.inspiredandroid.kai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inspiredandroid.kai.tools.RootApprovalRegistry
import com.inspiredandroid.kai.tools.RootApprovalRequest

@Composable
actual fun PlatformRootApprovalHost() {
    val pending by RootApprovalRegistry.pending.collectAsStateWithLifecycle()
    val request = pending.firstOrNull() ?: return
    RootApprovalDialog(
        request = request,
        onApprove = { RootApprovalRegistry.approve(request.id) },
        onDeny = { RootApprovalRegistry.deny(request.id) },
    )
}

@Composable
private fun RootApprovalDialog(
    request: RootApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Root request") },
        text = {
            Column(Modifier.widthIn(max = 520.dp)) {
                Text(request.reason, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text("Scope: ${request.scope}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    request.command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            Button(onClick = onApprove) {
                Text("Approve once")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("Deny")
            }
        },
    )
}
