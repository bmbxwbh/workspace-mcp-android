package workspacemcp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.workspace.RootfsInstallProgress
import workspacemcp.app.data.WorkspaceRecord
import workspacemcp.app.domain.WorkspaceController
import workspacemcp.app.server.McpService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = Runtime.init(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(runtime)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(runtime: AppRuntime) {
    val context = LocalContext.current
    val controller = remember { WorkspaceController(runtime) }
    val scope = rememberCoroutineScope()

    val workspaces = remember { mutableStateOf<List<WorkspaceRecord>>(emptyList()) }
    val currentId = remember { mutableStateOf<String?>(null) }
    val running by McpService.isRunning.collectAsState()
    val serverError by McpService.lastError.collectAsState()
    val port = remember { mutableStateOf(runtime.settings.port().toString()) }
    val token = remember { mutableStateOf(runtime.settings.token().orEmpty()) }
    val showCreate = remember { mutableStateOf(false) }
    val deleteTarget = remember { mutableStateOf<WorkspaceRecord?>(null) }
    val rootfsTarget = remember { mutableStateOf<WorkspaceRecord?>(null) }
    val rootfsProgress = remember { MutableStateFlow<RootfsInstallProgress?>(null) }
    val rootfsProgressState by rootfsProgress.collectAsState()
    val message = remember { mutableStateOf<String?>(null) }

    fun refresh() {
        workspaces.value = controller.list()
        currentId.value = runtime.settings.currentWorkspaceId()
    }

    LaunchedEffect(Unit) { refresh() }

    val lanIp = remember { McpService.lanIpAddress() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Workspace MCP Server") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate.value = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Create workspace")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("MCP Server", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (running) {
                                    Text("Running", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = running,
                                    onCheckedChange = { enabled ->
                                        if (enabled) McpService.start(context) else McpService.stop(context)
                                    },
                                )
                            }
                        }
                        val address = if (running) {
                            "http://${lanIp ?: "0.0.0.0"}:${port.value}"
                        } else {
                            "Service stopped"
                        }
                        Text("Streamable HTTP: $address/mcp")
                        Text("SSE: $address/sse")
                        serverError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

                        OutlinedTextField(
                            value = port.value,
                            onValueChange = { port.value = it.filter(Char::isDigit).takeIf(String::isNotBlank) ?: "" },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = token.value,
                            onValueChange = { token.value = it },
                            label = { Text("Bearer token (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running,
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                val newPort = port.value.toIntOrNull() ?: return@Button
                                runCatching {
                                    runtime.settings.setPort(newPort)
                                    runtime.settings.setToken(token.value)
                                }.onSuccess { message.value = "Settings saved" }
                                    .onFailure { message.value = it.message }
                            },
                            enabled = !running,
                        ) { Text("Save settings") }
                        message.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            item {
                Text("Workspaces", style = MaterialTheme.typography.titleMedium)
            }

            items(workspaces.value, key = { it.id }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    isCurrent = workspace.id == currentId.value,
                    hasRootfs = controller.hasRootfs(workspace.id),
                    rootfsInstalling = rootfsTarget.value?.id == workspace.id && rootfsProgressState != null,
                    rootfsProgress = rootfsProgressState,
                    onSwitch = {
                        runCatching { controller.switch(workspace.id) }
                            .onSuccess { refresh() }
                            .onFailure { message.value = it.message }
                    },
                    onInstallRootfs = { rootfsTarget.value = workspace },
                    onDelete = { deleteTarget.value = workspace },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showCreate.value) {
        CreateWorkspaceDialog(
            onDismiss = { showCreate.value = false },
            onConfirm = { name ->
                runCatching { controller.create(name) }
                    .onSuccess {
                        refresh()
                        showCreate.value = false
                    }
                    .onFailure { message.value = it.message }
            },
        )
    }

    deleteTarget.value?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget.value = null },
            title = { Text("Delete workspace") },
            text = { Text("Delete \"${target.name}\" with all its files and rootfs? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.delete(target.id)
                    deleteTarget.value = null
                    refresh()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget.value = null }) { Text("Cancel") }
            },
        )
    }

    rootfsTarget.value?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (rootfsProgressState == null) rootfsTarget.value = null
            },
            title = { Text("Install rootfs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download Ubuntu 24.04 base (arm64) into \"${target.name}\"? This may take a while.")
                    rootfsProgressState?.let { progress ->
                        when (progress.stage) {
                            me.rerere.workspace.RootfsInstallStage.DOWNLOADING -> {
                                val mb = progress.bytesRead / 1024 / 1024
                                val total = progress.totalBytes?.let { "${it / 1024 / 1024}MB" } ?: "?"
                                Text("Downloading… $mb MB / $total")
                            }
                            me.rerere.workspace.RootfsInstallStage.EXTRACTING ->
                                Text("Extracting… ${progress.entriesExtracted} entries")
                            me.rerere.workspace.RootfsInstallStage.INSTALLED ->
                                Text("Rootfs installed")
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (rootfsProgressState == null) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                controller.installRootfs(target.id) { progress ->
                                    rootfsProgress.value = progress
                                }
                            }.onSuccess {
                                rootfsProgress.value = null
                                rootfsTarget.value = null
                                message.value = "Rootfs installed"
                            }.onFailure {
                                rootfsProgress.value = null
                                rootfsTarget.value = null
                                message.value = it.message
                            }
                        }
                    }) { Text("Install") }
                }
            },
            dismissButton = {
                if (rootfsProgressState == null) {
                    TextButton(onClick = { rootfsTarget.value = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceRecord,
    isCurrent: Boolean,
    hasRootfs: Boolean,
    rootfsInstalling: Boolean,
    rootfsProgress: RootfsInstallProgress?,
    onSwitch: () -> Unit,
    onInstallRootfs: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(workspace.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (hasRootfs) "rootfs ready" else "no rootfs",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasRootfs) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (rootfsInstalling) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                } else {
                    Checkbox(checked = isCurrent, onCheckedChange = { onSwitch() })
                }
            }
            if (rootfsInstalling) {
                rootfsProgress?.let {
                    Text(
                        when (it.stage) {
                            me.rerere.workspace.RootfsInstallStage.DOWNLOADING ->
                                "Downloading… ${it.bytesRead / 1024 / 1024} MB"
                            me.rerere.workspace.RootfsInstallStage.EXTRACTING ->
                                "Extracting… ${it.entriesExtracted} entries"
                            me.rerere.workspace.RootfsInstallStage.INSTALLED -> "Installed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onInstallRootfs, enabled = !rootfsInstalling) {
                    Text(if (hasRootfs) "Reinstall rootfs" else "Install rootfs")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete workspace")
                }
            }
        }
    }
}

@Composable
private fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workspace") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
