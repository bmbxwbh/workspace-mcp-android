package workspacemcp.app.mcp

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

/** 生成 unified diff, 无变化时返回 null。移植自 RikkaHub DiffUtils.kt */
fun generateUnifiedDiff(original: String, updated: String, path: String): String? {
    val originalLines = original.lines()
    val updatedLines = updated.lines()
    val patch = runCatching { DiffUtils.diff(originalLines, updatedLines) }.getOrNull() ?: return null
    if (patch.deltas.isEmpty()) return null
    return runCatching {
        UnifiedDiffUtils.generateUnifiedDiff(path, path, originalLines, patch, 3)
            .joinToString("\n")
    }.getOrNull()
}
