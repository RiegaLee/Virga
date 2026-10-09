package cn.huohuas001.virga.panel.http

/** Serves the compiled panel front end from the classpath under `panel/`. */
class StaticAssets(
    private val loader: ClassLoader,
    private val root: String = "panel/"
) {
    class Asset(val bytes: ByteArray, val contentType: String)

    /** Resolves a request path; unknown non-file paths fall back to index.html (single-page app). */
    fun resolve(path: String): Asset? {
        val relative = path.removePrefix("/").ifEmpty { "index.html" }
        if (!SAFE_PATH.matches(relative) || relative.split('/').any { it == ".." || it == "." }) return null
        load(relative)?.let { return it }
        if (relative == "index.html") return FALLBACK
        return if (relative.substringAfterLast('/').contains('.')) null else load("index.html") ?: FALLBACK
    }

    private fun load(relative: String): Asset? {
        val bytes = loader.getResourceAsStream(root + relative)?.use { it.readBytes() } ?: return null
        return Asset(bytes, contentType(relative))
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "json" -> "application/json; charset=utf-8"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private companion object {
        val SAFE_PATH = Regex("[A-Za-z0-9._/-]{1,200}")
        val FALLBACK = Asset(
            ("<!doctype html><meta charset=\"utf-8\"><title>Virga 管理面板</title>" +
                "<p>Virga 管理面板的前端资源缺失了，请使用完整构建的模组 JAR。</p>").toByteArray(),
            "text/html; charset=utf-8"
        )
    }
}
