plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(project(":wiiiv-core"))

    // kotlinx-serialization (ExecutionResult에서 JsonPrimitive 등 사용)
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Ktor Client (HTTP 호출)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")

    // Testing
    testImplementation(project(":wiiiv-core"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    // Fat JAR: 플러그인 의존성을 하나의 JAR에 포함 (wiiiv-core는 compileOnly이므로 제외)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("wiiiv-plugin-webhook")
}
