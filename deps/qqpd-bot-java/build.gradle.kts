plugins {
    `java-library`
}

group = "io.github.kloping"
version = "1.5.3-L4"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    // 依赖与 pom.xml 保持一致（含排除项）
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

    compileOnly("org.projectlombok:lombok:1.18.26")
    annotationProcessor("org.projectlombok:lombok:1.18.26")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
