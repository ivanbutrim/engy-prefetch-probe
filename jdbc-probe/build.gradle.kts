plugins {
    kotlin("jvm") version "1.9.22"
    application
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