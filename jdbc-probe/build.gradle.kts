plugins {
    kotlin("jvm") version "2.1.20"
    application
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "23"
    targetCompatibility = "23"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_23)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.snowflake:snowflake-jdbc:4.2.0")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

application {
    mainClass.set("EngyJdbcProbeKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}