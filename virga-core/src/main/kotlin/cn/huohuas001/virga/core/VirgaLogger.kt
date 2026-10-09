package cn.huohuas001.virga.core

interface VirgaLogger {
    fun info(message: String)
    fun warning(message: String)
    fun error(message: String, error: Throwable? = null)
}
