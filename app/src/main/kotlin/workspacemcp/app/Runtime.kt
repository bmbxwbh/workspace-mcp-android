package workspacemcp.app

import android.content.Context
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import workspacemcp.app.data.SettingsStore
import workspacemcp.app.data.WorkspaceDb
import java.io.File

/**
 * 全局运行时装配, 对应原 App 的 Koin DI (RepositoryModule 中 WorkspaceManager 的装配).
 */
object Runtime {
    @Volatile
    private var initialized: AppRuntime? = null

    fun init(context: Context): AppRuntime {
        initialized?.let { return it }
        synchronized(this) {
            initialized?.let { return it }
            val appContext = context.applicationContext
            val manager = WorkspaceManager(
                baseDir = File(appContext.filesDir, "workspaces"),
                shellRunner = ProotShellRunner(
                    nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir),
                ),
            )
            val runtime = AppRuntime(
                manager = manager,
                installer = RootfsInstaller(manager),
                db = WorkspaceDb(appContext),
                settings = SettingsStore(appContext),
            )
            initialized = runtime
            return runtime
        }
    }

    fun get(): AppRuntime = requireNotNull(initialized) {
        "Runtime not initialized"
    }
}

data class AppRuntime(
    val manager: WorkspaceManager,
    val installer: RootfsInstaller,
    val db: WorkspaceDb,
    val settings: SettingsStore,
)
