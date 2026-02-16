plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val wiiivVersion: String by rootProject.extra

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    val buildNumberFile = rootProject.file("build-number.txt")
    inputs.file(buildNumberFile)
    outputs.dir(outputDir)
    mustRunAfter(":incrementBuildNumber")
    doLast {
        val buildNumber = buildNumberFile.readText().trim().toIntOrNull() ?: 1
        val dir = outputDir.get().asFile.resolve("io/wiiiv")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            """
            |package io.wiiiv
            |
            |object BuildInfo {
            |    const val VERSION = "$wiiivVersion"
            |    const val BUILD_NUMBER = $buildNumber
            |    const val FULL_VERSION = "v$wiiivVersion.$buildNumber"
            |}
            """.trimMargin()
        )
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildInfo)
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/buildinfo"))
    }
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // PDF parsing (for RAG)
    implementation("org.apache.pdfbox:pdfbox:3.0.4")

    // Ktor Client (for RAG embedding providers)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.h2database:h2:2.2.224")

    // Ktor Server (for MockApiServer in tests)
    testImplementation("io.ktor:ktor-server-core:2.3.7")
    testImplementation("io.ktor:ktor-server-netty:2.3.7")
    testImplementation("io.ktor:ktor-server-content-negotiation:2.3.7")
}

tasks.test {
    // -PskipE2E=true 로 E2E 테스트 제외 가능
    if (project.hasProperty("skipE2E")) {
        exclude("io/wiiiv/integration/**")
    }
}
