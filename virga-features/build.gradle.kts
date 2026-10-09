plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api(project(":virga-api"))
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

// The bundled asset manifest hashes the exact bytes shipped in the JAR. Keep
// text resources on LF even when this repository is built from a Windows
// checkout with core.autocrlf enabled.
tasks.processResources {
    // Walk the output with plain java.io instead of the script's fileTree() so
    // the action stays compatible with the configuration cache.
    val normalizedTextExtensions = setOf("json", "md", "tsv", "txt", "yml", "yaml")
    doLast {
        val assetRoot = (this as ProcessResources).destinationDir.resolve("bundled-assets")
        if (!assetRoot.isDirectory) return@doLast
        assetRoot.walkTopDown().filter {
            it.isFile && it.extension in normalizedTextExtensions
        }.forEach { resource ->
            val bytes = resource.readBytes()
            val normalized = bytes.toString(Charsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .toByteArray(Charsets.UTF_8)
            if (!bytes.contentEquals(normalized)) resource.writeBytes(normalized)
        }
    }
}
