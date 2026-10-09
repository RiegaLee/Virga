plugins {
    kotlin("jvm")
    `java-library`
    kotlin("plugin.lombok")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceSets.named("main") {
        java.srcDir(rootProject.file("deps/qqpd-bot-java/src/main/java"))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":virga-api"))
    implementation(kotlin("stdlib"))

    implementation("io.github.kloping:SpringTool:0.6.4") {
        exclude(group = "io.github.Kloping", module = "JvUtils")
    }
    implementation("io.github.kloping:JvUtils:0.4.9-Beta1") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.slf4j:slf4j-nop:2.0.12")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jsoup:jsoup:1.15.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alibaba:fastjson:2.0.32")
    implementation("org.yaml:snakeyaml:2.2")

    // The QQ SDK source is unchanged except for webhook signing, which uses the JDK's Ed25519
    // instead of BouncyCastle; its annotation processor is modernized because Lombok 1.18.26
    // cannot run on the Java 21 compiler.
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

