plugins {
    application
    id("com.github.johnrengelman.shadow")
}

val ktorVersion = "2.3.7"
val logbackVersion = "1.4.14"

dependencies {
    implementation(project(":wiiiv-core"))

    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // JDBC Drivers (runtime — DbExecutor용)
    implementation("com.mysql:mysql-connector-j:8.3.0")
    implementation("com.h2database:h2:2.2.224")

    // BCrypt (패스워드 해싱)
    implementation("org.mindrot:jbcrypt:0.4")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")

    // MockK (PolicyChecker 단위 테스트용)
    testImplementation("io.mockk:mockk:1.13.8")

    // Plugin 단위 테스트용 (PluginUnitTest.kt)
    testImplementation(project(":wiiiv-plugin-cron"))
    testImplementation(project(":wiiiv-plugin-pdf"))
    testImplementation(project(":wiiiv-plugin-spreadsheet"))
    testImplementation(project(":wiiiv-plugin-webfetch"))
    testImplementation(project(":wiiiv-plugin-webhook"))
}

application {
    mainClass.set("io.wiiiv.server.ApplicationKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
}
