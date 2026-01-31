plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    // CLI Framework
    implementation("com.github.ajalt.clikt:clikt:4.2.1")

    // HTTP Client
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Shared DTOs from wiiiv-api
    implementation(project(":wiiiv-api"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.ktor:ktor-server-test-host:2.3.7")
    testImplementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation(project(":wiiiv-api"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.wiiiv.cli.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.wiiiv.cli.MainKt"
    }

    // Fat JAR
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
