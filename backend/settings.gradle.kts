plugins {
    // Lets Gradle fetch a JDK 21 toolchain when the machine does not have one. The
    // build declares languageVersion 21; without a resolver, a developer whose only JDK
    // is something else gets "No matching toolchains found" and a project that looks
    // broken. The Docker build is unaffected either way.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "savings-account-api"
