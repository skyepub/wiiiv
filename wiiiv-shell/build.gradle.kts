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

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.wiiiv.shell.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.wiiiv.shell.MainKt"
    }

    // Fat JAR
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
