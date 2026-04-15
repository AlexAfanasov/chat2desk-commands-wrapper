plugins {
    id("com.android.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}

val jitpackVersion = System.getenv("VERSION")

subprojects {
    group =
        (rootProject.findProperty("POM_GROUP") as String?)
            ?: "com.github.AlexAfanasov.chat2desk-commands-wrapper"
    version = jitpackVersion ?: (rootProject.findProperty("VERSION_NAME") as String?) ?: "0.1.0-SNAPSHOT"
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}