package workspacemcp.app

import android.app.Application
import workspacemcp.app.server.McpService

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val runtime = Runtime.init(this)
        // 启动时清理 PRoot temp / rootfs /tmp (对应原 App 的 cleanupAllTempDirs)
        runtime.manager.cleanupAllTempDirs()
        // 之前若开启过服务, 进程重启后恢复前台服务
        if (runtime.settings.serviceEnabled()) {
            McpService.start(this)
        }
    }
}
