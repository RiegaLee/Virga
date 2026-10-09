package cn.huohuas001.virga.core.qq

import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import kotlin.test.Test
import kotlin.test.assertTrue

class SdkComponentIndexTest {
    @Test
    fun listsTopLevelSdkClassesRecursively() {
        val classes = SdkComponentIndex.list(Start0::class.java.classLoader)
        assertTrue(Start0::class.java in classes)
        assertTrue(Starter::class.java in classes)
        assertTrue(classes.all { '$' !in it.name && it.name.startsWith(Start0::class.java.packageName + ".") })
        // Sub-packages are included, as with the SDK's recursive scan.
        assertTrue(classes.any { it.packageName != Start0::class.java.packageName })
    }
}
