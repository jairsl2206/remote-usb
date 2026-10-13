// Plain JVM module: the USB/IP protocol and session logic, testable without Android.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
