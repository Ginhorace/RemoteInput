package com.github.remotecontrol.util.template

import android.content.Context
import com.github.remotecontrol.util.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 一个模板文件。
 *
 * 文件名（去掉扩展名）就是模板的唯一标识和列表显示名，
 * 目录本身就是索引，因此不需要任何额外的元数据文件。
 */
data class MacroTemplateFile(
    val name: String,
    val file: File,
    val updatedAt: Long,
)

/**
 * 加载模板时校验出的坏模板。
 *
 * 只统计 ERROR —— 警告不阻塞保存与发送，不值得打断用户。
 *
 * @param name 模板名（文件名去扩展名）
 * @param file 模板文件
 * @param errorCount 解析出的错误条数
 */
data class MacroTemplateLoadError(
    val name: String,
    val file: File,
    val errorCount: Int,
)

/**
 * 模板文件仓库。
 *
 * 整个存储只有一个目录：`filesDir/templates/`，里面一个模板一个 `.txt`。
 * 增删改都直接作用在文件上，列表通过扫描目录得到。
 */
class MacroTemplateStore private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val dir = File(appContext.filesDir, DIRECTORY_NAME)

    private val _templates = MutableStateFlow<List<MacroTemplateFile>>(emptyList())

    /** 当前目录下的所有模板，按最近修改时间倒序 */
    val templates: StateFlow<List<MacroTemplateFile>> = _templates

    private val _loadErrors = MutableStateFlow<List<MacroTemplateLoadError>>(emptyList())

    /** 加载时校验出的坏模板（只含 ERROR），随 [refresh] 更新 */
    val loadErrors: StateFlow<List<MacroTemplateLoadError>> = _loadErrors

    /** 「模板加载错误」提示是否已在本次进程内弹过 */
    private var startupNoticeShown = false

    /** 重新扫描模板目录，并顺带把所有模板解析一遍 */
    fun refresh() {
        seedIfFirstLaunch()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) } ?: emptyArray()
        _templates.value = files
            .map { MacroTemplateFile(it.nameWithoutExtension, it, it.lastModified()) }
            .sortedByDescending { it.updatedAt }

        // 文件放在应用私有目录里也可能被外部改动（root / adb 备份还原），
        // 而「目录即索引」意味着这里就是所有模板的唯一入口，所以每次扫描都重新校验一遍
        _loadErrors.value = files.mapNotNull { file ->
            val parsed = MacroTemplateParser.parse(readText(file))
            if (parsed.hasError) {
                MacroTemplateLoadError(file.nameWithoutExtension, file, parsed.errorCount)
            } else {
                null
            }
        }
    }

    /**
     * 首次进入模板模式时要弹窗提示的坏模板。
     *
     * 进程内只返回一次：这是「主动告知」，不是「每次刷新都提醒」——
     * 保存模板本身也会走 [refresh]，若每次都弹，修完之前会被反复打断。
     * 告知之后，列表卡片的错误标记、编辑器的诊断列表、发送门禁三层继续兜底。
     */
    fun takeStartupLoadErrors(): List<MacroTemplateLoadError> {
        if (startupNoticeShown) return emptyList()
        startupNoticeShown = true
        return _loadErrors.value
    }

    /** 读取模板正文 */
    fun readText(file: File): String = runCatching { file.readText() }.getOrDefault("")

    /**
     * 保存模板。
     *
     * @param file 为 null 表示新建；否则为被编辑的模板文件
     * @param name 目标名字（重命名也走这里，会同步改名文件与文件内的 `name` 头部）
     * @param content 模板正文
     */
    fun save(file: File?, name: String, content: String): Result<File> = runCatching {
        val safeName = sanitizeName(name)
        val target = when {
            file == null -> availableFile(safeName)
            file.nameWithoutExtension == safeName -> file
            else -> {
                val renamed = availableFile(safeName)
                if (file.renameTo(renamed)) renamed else file
            }
        }
        writeAtomically(target, MacroTemplateParser.syncNameHeader(content, target.nameWithoutExtension))
        refresh()
        target
    }

    /** 删除模板文件 */
    fun delete(file: File) {
        runCatching { file.delete() }
        refresh()
    }

    /** 取一个未占用的名字，用于「新建」时的默认名 */
    fun uniqueName(base: String): String {
        val safeName = sanitizeName(base)
        if (!File(dir, "$safeName$EXTENSION").exists()) return safeName
        var index = 2
        while (File(dir, "$safeName $index$EXTENSION").exists()) index++
        return "$safeName $index"
    }

    private fun availableFile(baseName: String): File {
        var candidate = File(dir, "$baseName$EXTENSION")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($index)$EXTENSION")
            index++
        }
        return candidate
    }

    /** 先写临时文件再 rename，避免写到一半崩溃留下半个文件 */
    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}$TEMP_SUFFIX")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.writeText(content)
            temp.delete()
        }
    }

    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim('.', ' ')
        return cleaned.ifBlank { "template" }
    }

    /**
     * 首次启动（目录还不存在）时写入内置示例模板。
     *
     * 只写 [MacroTemplatePresets.SEEDED]，其余预设留在「新建」时供选择。
     *
     * 落盘前两步，顺序固定：先把正文里的字符集占位符换成界面语言对应的宿主码页
     * （种子模板就是 OEM Alt Code，取的是 OEMCP），再按当前界面语言补说明 —— 见
     * [MacroTemplatePresets.resolveCharset] / [MacroTemplatePresets.localize]。
     *
     * store 拿的是 `applicationContext`，它的 `resources` 永远是系统语言，所以界面语言要走
     * `LocaleHelper.currentLocale`（读偏好，不看 configuration），补说明还要 `wrapContext`
     * 包一层才能命中对应语包；这些开销都放在写盘之前，目录已存在时上面就返回了。
     */
    private fun seedIfFirstLaunch() {
        if (dir.exists()) return
        if (!dir.mkdirs()) return
        val locale = LocaleHelper.currentLocale(appContext)
        val uiContext = LocaleHelper.wrapContext(appContext)
        MacroTemplatePresets.SEEDED.forEach { preset ->
            val seeded = MacroTemplatePresets.localize(
                uiContext,
                MacroTemplatePresets.resolveCharset(preset, locale),
            )
            runCatching { File(dir, "${seeded.name}$EXTENSION").writeText(seeded.content) }
        }
    }

    companion object {
        const val DIRECTORY_NAME = "templates"
        const val EXTENSION = ".txt"
        private const val TEMP_SUFFIX = ".tmp"

        @Volatile
        private var instance: MacroTemplateStore? = null

        fun getInstance(context: Context): MacroTemplateStore =
            instance ?: synchronized(this) {
                instance ?: MacroTemplateStore(context).also {
                    it.refresh()
                    instance = it
                }
            }
    }
}
