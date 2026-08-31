package workspacemcp.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.workspace.RootfsInstallProgress
import workspacemcp.app.data.WorkspaceRecord
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
    val controller = runtime.controller

    val workspaces = remember { mutableStateOf<List<WorkspaceRecord>>(emptyList()) }
    val currentId = remember { mutableStateOf<String?>(null) }
    val running by McpService.isRunning.collectAsState()
    val serverError by McpService.lastError.collectAsState()
    val port = remember { mutableStateOf(runtime.settings.port().toString()) }
    val token = remember { mutableStateOf(runtime.settings.token().orEmpty()) }
    val showCreate = remember { mutableStateOf(false) }
    val deleteTarget = remember { mutableStateOf<WorkspaceRecord?>(null) }
    val rootfsTarget = remember { mutableStateOf<WorkspaceRecord?>(null) }
    // 安装由 controller 后台执行, 状态全局共享, 旋转屏幕/切后台不丢失进度
    val installState by controller.installState.collectAsState()
    val installRunning = installState?.finished == false
    val message = remember { mutableStateOf<String?>(null) }
    val floatingEnabled = remember { mutableStateOf(runtime.settings.floatingEnabled()) }
    val hasOverlayPermission = remember { mutableStateOf(McpService.hasOverlayPermission(context)) }
    val notificationGranted = remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun refresh() {
        workspaces.value = controller.list()
        currentId.value = runtime.settings.currentWorkspaceId()
    }

    LaunchedEffect(Unit) { refresh() }

    // 安装结束: 刷新列表; 成功则提示并自动收起对话框, 失败保留对话框展示错误
    LaunchedEffect(installState?.finished) {
        val state = installState
        if (state?.finished == true) {
            refresh()
            if (state.error == null) {
                message.value = "rootfs 安装完成"
                rootfsTarget.value = null
                controller.clearInstallState()
            }
        }
    }

    // Activity 每次回到 RESUMED (含从系统权限设置页返回) 时刷新权限状态
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission.value = McpService.hasOverlayPermission(context)
                if (hasOverlayPermission.value && floatingEnabled.value) {
                    workspacemcp.app.server.FloatingWindow.show(context)
                }
                notificationGranted.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android 13+ 首次进入请求通知权限 (只弹一次, 拒绝后由用户点"授予通知权限"按钮)
    var notificationRequested by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!notificationGranted.value && !notificationRequested) {
            notificationRequested = true
            (context as? ComponentActivity)?.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001,
            )
        }
    }

    val lanIp = remember { McpService.lanIpAddress() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("工作区 MCP 服务器") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate.value = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建工作区")
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
                            Text("MCP 服务", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (running) {
                                    Text("运行中", style = MaterialTheme.typography.bodySmall)
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
                            "服务未启动"
                        }
                        Text("连接地址: $address/mcp")
                        Text("SSE 地址: $address/sse")
                        serverError?.let { Text("错误: $it", color = MaterialTheme.colorScheme.error) }

                        OutlinedTextField(
                            value = port.value,
                            onValueChange = { port.value = it.filter(Char::isDigit).takeIf(String::isNotBlank) ?: "" },
                            label = { Text("端口") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = token.value,
                            onValueChange = { token.value = it },
                            label = { Text("访问令牌 (可选)") },
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
                                }.onSuccess { message.value = "设置已保存" }
                                    .onFailure { message.value = it.message }
                            },
                            enabled = !running,
                        ) { Text("保存设置") }

                        if (!notificationGranted.value) {
                            TextButton(onClick = {
                                McpService.requestNotificationPermission(context)
                            }) { Text("授予通知权限") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("悬浮窗保活")
                                Text(
                                    "息屏/后台保持服务在线, 可拖动小球",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = floatingEnabled.value && hasOverlayPermission.value,
                                onCheckedChange = { enabled ->
                                    if (enabled && !hasOverlayPermission.value) {
                                        McpService.requestOverlayPermission(context)
                                        message.value = "请先授予悬浮窗权限, 授予后再打开开关"
                                    } else {
                                        runtime.settings.setFloatingEnabled(enabled)
                                        floatingEnabled.value = enabled
                                        if (enabled) {
                                            workspacemcp.app.server.FloatingWindow.show(context)
                                        } else {
                                            workspacemcp.app.server.FloatingWindow.dismiss(context)
                                        }
                                    }
                                },
                            )
                        }
                        if (!hasOverlayPermission.value) {
                            Text(
                                "未授予悬浮窗权限, 点击上方开关前往系统设置授权",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        message.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            item {
                Text("工作区", style = MaterialTheme.typography.titleMedium)
            }

            items(workspaces.value, key = { it.id }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    isCurrent = workspace.id == currentId.value,
                    hasRootfs = controller.hasRootfs(workspace.id),
                    rootfsInstalling = installRunning && installState?.workspaceId == workspace.id,
                    rootfsProgress = installState?.takeIf { it.workspaceId == workspace.id }?.progress,
                    installBusy = installRunning,
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
            title = { Text("删除工作区") },
            text = { Text("确定删除「${target.name}」及其全部文件和 rootfs 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    controller.delete(target.id)
                    deleteTarget.value = null
                    refresh()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget.value = null }) { Text("取消") }
            },
        )
    }

    // 安装对话框: 未确认时显示确认文案; 确认后由共享 installState 驱动, 旋转屏幕不丢失
    if (rootfsTarget.value != null || installState != null) {
        val state = installState
        val target = rootfsTarget.value
        AlertDialog(
            onDismissRequest = {
                // 进行中不允许关闭 (安装已在后台, 关闭对话框也无意义); 结束后可关闭
                if (state == null || state.finished) {
                    rootfsTarget.value = null
                    controller.clearInstallState()
                }
            },
            title = { Text("安装 rootfs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == null || state.finished) {
                        val name = state?.workspaceName ?: target?.name.orEmpty()
                        Text("下载 Ubuntu 24.04 基础版 (arm64) 到「$name」？可能需要较长时间。")
                        state?.error?.let {
                            Text(
                                "安装失败: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Text("正在安装到「${state.workspaceName}」, 安装在后台进行, 可最小化应用等待。")
                        state.progress?.let { progress ->
                            when (progress.stage) {
                                me.rerere.workspace.RootfsInstallStage.DOWNLOADING -> {
                                    val mb = progress.bytesRead / 1024 / 1024
                                    val total = progress.totalBytes?.let { "${it / 1024 / 1024}MB" } ?: "?"
                                    Text("下载中… $mb MB / $total")
                                }
                                me.rerere.workspace.RootfsInstallStage.EXTRACTING ->
                                    Text("解压中… 已解压 ${progress.entriesExtracted} 项")
                                me.rerere.workspace.RootfsInstallStage.INSTALLED ->
                                    Text("rootfs 安装完成")
                            }
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                when {
                    state == null -> TextButton(onClick = {
                        runCatching { controller.installRootfs(target?.id.orEmpty()) }
                            .onSuccess { rootfsTarget.value = null }
                            .onFailure {
                                rootfsTarget.value = null
                                message.value = it.message
                            }
                    }) { Text("安装") }

                    state.finished -> TextButton(onClick = {
                        rootfsTarget.value = null
                        controller.clearInstallState()
                    }) { Text("关闭") }
                }
            },
            dismissButton = {
                if (state == null) {
                    TextButton(onClick = { rootfsTarget.value = null }) { Text("取消") }
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
    installBusy: Boolean,
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
                        if (hasRootfs) "rootfs 已就绪" else "未安装 rootfs",
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
                                "下载中… ${it.bytesRead / 1024 / 1024} MB"
                            me.rerere.workspace.RootfsInstallStage.EXTRACTING ->
                                "解压中… 已解压 ${it.entriesExtracted} 项"
                            me.rerere.workspace.RootfsInstallStage.INSTALLED -> "已安装"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onInstallRootfs, enabled = !installBusy) {
                    Text(if (hasRootfs) "重新安装 rootfs" else "安装 rootfs")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除工作区")
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
        title = { Text("新建工作区") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
