plugins {
    kotlin("jvm") version "2.3.10"
}

group = "krill.zone"
version = "1.0.680"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}