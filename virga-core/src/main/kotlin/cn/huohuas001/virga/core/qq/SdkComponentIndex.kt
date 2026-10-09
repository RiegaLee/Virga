package cn.huohuas001.virga.core.qq

import io.github.kloping.qqbot.Start0
import io.github.kloping.spt.interfaces.component.PackageScanner
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.asSequence

/**
 * Lists the QQ SDK's component classes for its dependency-injection container.
 *
 * The container scans `@ComponentScan` packages by turning a class-loader URL into a plain
 * directory or `jar:` file. Mod loaders such as Forge serve mod JARs from their own file systems
 * (`union:` URLs), which that scanner cannot read, so the SDK would start with no components.
 * Walking the package through NIO works for every file system, and a scanner whose default class
 * list is filled skips its own scan.
 */
internal object SdkComponentIndex {
    /** Fills [scanner]'s default classes; leaves it untouched (own scan) when listing fails. */
    fun prefill(scanner: PackageScanner, loader: ClassLoader = Start0::class.java.classLoader): Int {
        val defaults = scanner.defaultClass
        if (defaults.isNotEmpty()) return defaults.size
        val classes = try {
            list(loader)
        } catch (_: Exception) {
            emptyList()
        }
        if (classes.isNotEmpty()) defaults.addAll(classes)
        return classes.size
    }

    /** Top-level classes (no `$`) under the SDK's root package, recursively, like the SDK scanner. */
    fun list(loader: ClassLoader): List<Class<*>> {
        val packageName = Start0::class.java.packageName
        val anchor = Start0::class.java.getResource("${Start0::class.java.simpleName}.class") ?: return emptyList()
        val uri = anchor.toURI()
        val file = try {
            Paths.get(uri)
        } catch (_: FileSystemNotFoundException) {
            // A plain jar: URL needs its zip file system opened first.
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).provider().getPath(uri)
        }
        val root = file.parent ?: return emptyList()
        return Files.walk(root).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { it.endsWith(".class") && '$' !in it }
                .map { packageName + "." + it.removeSuffix(".class").replace('/', '.') }
                .sorted()
                .map { Class.forName(it, false, loader) }
                .toList()
        }
    }
}
