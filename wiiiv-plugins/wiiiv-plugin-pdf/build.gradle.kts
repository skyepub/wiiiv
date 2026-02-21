plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(project(":wiiiv-core"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // PDFBox 3.x (서버 shadow JAR에 3.0.4 포함 — 호환 유지)
    compileOnly("org.apache.pdfbox:pdfbox:3.0.4")

    // Mustache 템플릿 엔진
    implementation("com.github.spullara.mustache.java:compiler:0.9.14")

    // Testing
    testImplementation(project(":wiiiv-core"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("wiiiv-plugin-pdf")
}
