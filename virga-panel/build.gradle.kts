plugins {
    kotlin("jvm")
    `java-library`
}

// Platform-neutral management panel: loopback HTTP server, authentication, request
// guards and the QQ QR connector. No Minecraft types; virga-server supplies the backend.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

// ---------- Front end (TypeScript + Vite) ----------
// Built with a portable Node at build time only; servers never need Node. VIRGA_NODE_HOME
// or ../tools/node next to the repository provides it.
val webDir = layout.projectDirectory.dir("web")
val webOutput = layout.buildDirectory.dir("panel-web")
val nodeHome: Provider<String> = providers.environmentVariable("VIRGA_NODE_HOME")
    .orElse(rootProject.layout.projectDirectory.dir("../tools/node").asFile.absolutePath)
val isWindows = System.getProperty("os.name").lowercase().contains("windows")

fun npmCommand(home: String): String =
    if (isWindows) File(home, "npm.cmd").absolutePath else File(home, "bin/npm").absolutePath

fun nodePath(home: String): String =
    (if (isWindows) home else File(home, "bin").absolutePath) + File.pathSeparator + System.getenv("PATH").orEmpty()

val installPanelWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the locked panel front-end dependencies (npm ci)."
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("node_modules"))
    workingDir(webDir)
    // Plain strings only: the configuration cache cannot serialize script references.
    val npm = npmCommand(nodeHome.get())
    doFirst {
        require(File(npm).isFile) {
            "构建管理面板前端需要 Node.js：请设置 VIRGA_NODE_HOME，或把便携 Node 放在仓库同级的 tools/node（当前：$npm）"
        }
    }
    environment("PATH", nodePath(nodeHome.get()))
    commandLine(npm, "ci", "--no-audit", "--no-fund")
}

val buildPanelWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "Type-checks and bundles the panel front end with Vite."
    dependsOn(installPanelWeb)
    inputs.dir(webDir.dir("src"))
    inputs.dir(webDir.dir("public"))
    inputs.files(webDir.file("vite.config.ts"), webDir.file("tsconfig.json"), webDir.file("package-lock.json"))
    outputs.dir(webOutput)
    workingDir(webDir)
    environment("PATH", nodePath(nodeHome.get()))
    commandLine(npmCommand(nodeHome.get()), "run", "build")
}

tasks.processResources {
    dependsOn(buildPanelWeb)
    from(webOutput)
    from(webDir.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") ; rename { "PANEL_THIRD_PARTY_NOTICES.md" } }
}
