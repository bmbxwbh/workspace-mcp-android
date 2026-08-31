package workspacemcp.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceManager
import workspacemcp.app.AppRuntime
import workspacemcp.app.data.WorkspaceRecord
import java.util.UUID

/** rootfs 安装状态, 供 UI 与 MCP 工具共享观察 */
data class RootfsInstallState(
    val workspaceId: String,
    val workspaceName: String,
    val progress: RootfsInstallProgress? = null,
    val error: String? = null,
    val finished: Boolean = false,
)


/**
 * 工作区业务层, 对应原 App 的 WorkspaceRepository (创建/切换/重命名/删除/rootfs 安装).
 */
class WorkspaceController(private val runtime: AppRuntime) {

    /** 暴露给 MCP 工具层的 WorkspaceManager (文件/shell 操作入口) */
    val manager: WorkspaceManager get() = runtime.manager

    private val db get() = runtime.db
    private val settings get() = runtime.settings

    fun list(): List<WorkspaceRecord> = db.list()

    fun getById(id: String): WorkspaceRecord? = db.getById(id)

    fun current(): WorkspaceRecord? =
        settings.currentWorkspaceId()?.let { db.getById(it) }

    /** 当前工作区, 未选择时抛出带引导信息的异常 */
    fun requireCurrent(): WorkspaceRecord = current()
        ?: error("No workspace selected. Call workspace_create or workspace_switch first.")

    fun create(name: String): WorkspaceRecord {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Workspace name is required" }
        require(db.getByName(trimmed) == null) { "Workspace name already taken: $trimmed" }
        val now = System.currentTimeMillis()
        val record = WorkspaceRecord(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            root = generateRoot(),
            createdAt = now,
            updatedAt = now,
        )
        manager.ensureWorkspace(record.root)
        db.insert(record)
        // 首个工作区自动选中, 与原 App 体验一致
        if (settings.currentWorkspaceId() == null) {
            settings.setCurrentWorkspaceId(record.id)
        }
        return record
    }

    fun switch(id: String): WorkspaceRecord {
        val record = db.getById(id) ?: error("Workspace not found: $id")
        db.touchAccess(record.id, System.currentTimeMillis())
        settings.setCurrentWorkspaceId(record.id)
        return record
    }

    fun rename(id: String, name: String): WorkspaceRecord {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Workspace name is required" }
        val record = db.getById(id) ?: error("Workspace not found: $id")
        val taken = db.getByName(trimmed)
        require(taken == null || taken.id == id) { "Workspace name already taken: $trimmed" }
        val updated = record.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        db.update(updated)
        return updated
    }

    fun delete(id: String): Boolean {
        val record = db.getById(id) ?: return false
        manager.deleteWorkspace(record.root)
        db.delete(id)
        if (settings.currentWorkspaceId() == id) {
            settings.setCurrentWorkspaceId(db.list().firstOrNull()?.id)
        }
        return true
    }

    fun hasRootfs(id: String): Boolean {
        val record = db.getById(id) ?: return false
        return manager.hasRootfs(record.root)
    }

    /**
     * 安装 rootfs — 后台单飞任务:
     * - 在 controller 自己的 scope 中执行, 不随 UI 界面销毁或 MCP HTTP 连接断开而取消,
     *   避免旧安装的 finally 清理与新安装并发读写同一个 tmp/rootfs.tar.gz 导致 ENOENT
     * - 全局同一时刻只允许一个安装, 已有安装进行中时直接返回当前状态
     */
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _installState = MutableStateFlow<RootfsInstallState?>(null)
    val installState: StateFlow<RootfsInstallState?> = _installState.asStateFlow()

    fun installRootfs(id: String, url: String = DEFAULT_ROOTFS_URL): RootfsInstallState {
        val record = db.getById(id) ?: error("Workspace not found: $id")
        val initial = RootfsInstallState(workspaceId = record.id, workspaceName = record.name)
        synchronized(this) {
            _installState.value?.takeIf { !it.finished }?.let { return it }
            _installState.value = initial
        }
        installScope.launch {
            val result = runCatching {
                runInterruptible {
                    runtime.installer.install(record.root, url) { progress ->
                        _installState.value = _installState.value?.copy(progress = progress)
                    }
                }
            }
            _installState.value = _installState.value?.copy(
                finished = true,
                error = result.exceptionOrNull()?.let { it.message ?: it.toString() },
            )
        }
        return initial
    }

    /** 清除已结束的安装状态 (UI 关闭对话框 / 下次安装前) */
    fun clearInstallState() {
        if (_installState.value?.finished == true) {
            _installState.value = null
        }
    }

    fun shutdown() {
        installScope.cancel()
    }

    companion object {
        // 与原 App 一致的默认 rootfs (Ubuntu 24.04 base arm64)
        const val DEFAULT_ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

        /** root 必须满足 WorkspaceManager.ROOT_NAME_REGEX */
        private fun generateRoot(): String =
            "ws-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
    }
}
