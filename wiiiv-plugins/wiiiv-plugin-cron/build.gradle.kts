plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(project(":wiiiv-core"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines (delay 용)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Ktor Client (schedule callback 용)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")

    // Testing
    testImplementation(project(":wiiiv-core"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("wiiiv-plugin-cron")
}
