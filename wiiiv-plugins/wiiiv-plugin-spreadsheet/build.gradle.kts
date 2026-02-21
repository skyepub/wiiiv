plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(project(":wiiiv-core"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Apache POI (Excel)
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // Apache Commons CSV
    implementation("org.apache.commons:commons-csv:1.10.0")

    // Testing
    testImplementation(project(":wiiiv-core"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("wiiiv-plugin-spreadsheet")
}
