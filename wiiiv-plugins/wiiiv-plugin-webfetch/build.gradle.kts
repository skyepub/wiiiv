plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(project(":wiiiv-core"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Ktor Client (HTTP fetch)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")

    // Jsoup (HTML parsing)
    implementation("org.jsoup:jsoup:1.17.2")

    // Testing
    testImplementation(project(":wiiiv-core"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("wiiiv-plugin-webfetch")
}
