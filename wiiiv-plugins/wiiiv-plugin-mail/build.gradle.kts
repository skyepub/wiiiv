plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(project(":wiiiv-core"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Jakarta Mail (SMTP + IMAP)
    implementation("org.eclipse.angus:jakarta.mail:2.0.2")
    implementation("jakarta.activation:jakarta.activation-api:2.1.2")

    // Testing
    testImplementation(project(":wiiiv-core"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("wiiiv-plugin-mail")
}
