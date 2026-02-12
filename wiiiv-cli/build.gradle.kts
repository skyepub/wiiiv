plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    // Core dependencies
    implementation(project(":wiiiv-core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // JLine3 — multiline terminal input
    implementation("org.jline:jline:3.25.1")

    // SLF4J NOP — suppress logging warnings from Ktor/HTTP clients
    implementation("org.slf4j:slf4j-nop:2.0.9")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.wiiiv.cli.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8"
    )
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.wiiiv.cli.MainKt"
    }

    // Fat JAR
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
