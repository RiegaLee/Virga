pluginManagement {
    repositories {
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
    }
    plugins {
        kotlin("plugin.lombok") version "2.2.20"
    }
}

plugins {
    // Provisions the JDK 17 toolchain Forge 1.20.1 needs when none is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Virga"

include(":virga-api")
include(":virga-core")
include(":virga-features")
include(":virga-panel")
include(":virga-server")
include(":virga-forge")
