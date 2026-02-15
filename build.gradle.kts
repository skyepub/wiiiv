plugins {
    kotlin("jvm") version "1.9.22" apply false
    kotlin("plugin.serialization") version "1.9.22" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

val wiiivVersion by extra("2.2")

allprojects {
    group = "io.wiiiv"
    version = "2.2.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

val incrementBuildNumber by tasks.registering {
    doLast {
        val file = rootProject.file("build-number.txt")
        val current = if (file.exists()) file.readText().trim().toIntOrNull() ?: 1 else 1
        file.writeText("${current + 1}")
        println("Build number: $current -> ${current + 1}")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    plugins.withId("com.github.johnrengelman.shadow") {
        tasks.named("shadowJar") {
            dependsOn(":incrementBuildNumber")
        }
    }
}
