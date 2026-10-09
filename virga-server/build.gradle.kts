plugins {
    kotlin("jvm")
    `java-library`
}

// Platform-neutral Virga composition root and feature services. Minecraft types never
// appear here: the platform layer (virga-forge) implements the small game abstraction in `server.game`.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":virga-api"))
    api(project(":virga-core"))
    api(project(":virga-features"))
    api(project(":virga-panel"))
    api(kotlin("stdlib"))
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.google.code.gson:gson:2.10.1")
    // QR codes drawn onto in-game maps for connecting the QQ bot.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
