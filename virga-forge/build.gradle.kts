import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("net.neoforged.moddev.legacyforge")
    id("com.gradleup.shadow")
}

val minecraftVersion = property("minecraft_version").toString()

base {
    archivesName.set("Virga-forge-$minecraftVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Forge 1.20.1 compiles against official Mojang names; the shaded JAR is reobfuscated to SRG
// at the end. NeoForge 1.20.1 (47.1.x) shares this API, so the same JAR serves both loaders.
legacyForge {
    version = property("forge_version").toString()

    runs {
        create("server") {
            server()
        }
    }

    mods {
        create("virga") {
            sourceSet(sourceSets.main.get())
        }
    }
}

val shade: Configuration by configurations.creating {
    // Minecraft already ships Gson and the SLF4J API; a second copy would split the module graph.
    exclude(group = "com.google.code.gson")
    exclude(group = "org.slf4j")
    exclude(group = "org.jetbrains", module = "annotations")
    // Quartz's optional JDBC pools; Virga never configures a database job store.
    exclude(group = "com.mchange")
    exclude(group = "com.zaxxer")
}

dependencies {
    implementation(project(":virga-server"))
    shade(project(":virga-server"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.processResources {
    val properties = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to minecraftVersion
    )
    inputs.properties(properties)
    filesMatching("META-INF/mods.toml") { expand(properties) }
    from(rootProject.file("LICENSE.txt")) { into("META-INF"); rename { "LICENSE-Virga.txt" } }
    from(rootProject.file("INVENTORY_THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dev-shadow")
    configurations.set(listOf(shade))
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    // Quartz's optional Terracotta clustering support.
    exclude("org/terracotta/**")
    // Forge puts every mod into one module layer, where two JARs may not share a package:
    // move bundled libraries under Virga's own namespace. The QQ SDK family (io.github.kloping)
    // stays put: it names its own classes inside strings such as "{io.github.kloping.qqbot.Starter.net}",
    // which relocation cannot rewrite.
    val prefix = "cn.huohuas001.virga.libs"
    relocate("kotlin", "$prefix.kotlin")
    relocate("okhttp3", "$prefix.okhttp3")
    relocate("okio", "$prefix.okio")
    relocate("org.yaml.snakeyaml", "$prefix.snakeyaml")
    relocate("org.java_websocket", "$prefix.java_websocket")
    relocate("org.jsoup", "$prefix.jsoup")
    relocate("com.alibaba", "$prefix.alibaba")
    relocate("com.google.zxing", "$prefix.zxing")
    relocate("org.quartz", "$prefix.quartz")
    relocate("org.fusesource.jansi", "$prefix.jansi")
    relocate("org.intellij.lang.annotations", "$prefix.annotations.intellij")
}

val reobfShadowJar = obfuscation.reobfuscate(shadowJar, sourceSets.main.get()) {
    archiveFileName.set("Virga-forge-$minecraftVersion-${project.version}.jar")
}
tasks.named("assemble") { dependsOn(reobfShadowJar) }

val gatherJar by tasks.registering(Copy::class) {
    from(reobfShadowJar)
    into(rootProject.layout.buildDirectory.dir("gather-jar"))
}
tasks.named("build") { finalizedBy(gatherJar) }
