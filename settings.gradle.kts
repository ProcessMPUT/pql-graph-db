plugins {
    // Lets Gradle provision the JDK declared by `jvmToolchain` when the machine
    // does not already have it, which is what the README promises.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "processm-interpreter"
