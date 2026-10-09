plugins {
    kotlin("jvm") version "2.2.20" apply false
    id("com.gradleup.shadow") version "9.3.1" apply false
    // Builds against Forge 1.20.1 (official Mojang names) and reobfuscates the final JAR to SRG.
    id("net.neoforged.moddev.legacyforge") version "2.0.148" apply false
}

allprojects {
    group = "cn.huohuas001.virga"
    version = providers.gradleProperty("version").getOrElse("0.1.0-alpha.1")

    repositories {
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
    }
}

subprojects {
    // Minecraft 1.20.1 runs on Java 17: Kotlin targets 17 and may only link Java 17 APIs
    // (each module's JavaCompile sets release 17 the same way).
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        val isolatedTmp = layout.buildDirectory.dir("tmp/$name")
        doFirst { isolatedTmp.get().asFile.mkdirs() }
        systemProperty("java.io.tmpdir", isolatedTmp.get().asFile.absolutePath)
    }
}
