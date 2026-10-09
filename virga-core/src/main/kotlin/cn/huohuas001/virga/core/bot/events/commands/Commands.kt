package cn.huohuas001.virga.core.bot.events.commands

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Commands(
    val command: String,
    val describe: String,
    val onlyAdmin: Boolean = false
)

data class RegisteredCommand(
    val command: String,
    val describe: String,
    val onlyAdmin: Boolean = false
)
