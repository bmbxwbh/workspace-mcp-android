package workspacemcp.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.workspace.RootfsInstallProgress
import workspacemcp.app.AppRuntime
import workspacemcp.app.data.WorkspaceRecord
import java.util.UUID


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

    /** 安装 rootfs, 对应原 WorkspaceRepository.installRootfs (取消 -> 线程中断) */
    suspend fun installRootfs(
        id: String,
        url: String = DEFAULT_ROOTFS_URL,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val record = db.getById(id) ?: error("Workspace not found: $id")
        runInterruptible {
            runtime.installer.install(record.root, url, onProgress)
        }
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
