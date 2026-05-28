plugins {
    kotlin("jvm") version "2.1.20"
    application
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.snowflake:snowflake-jdbc:4.2.0")
}

application {
    mainClass.set("EngyJdbcProbeKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "EngyJdbcProbeKt")
    }
}