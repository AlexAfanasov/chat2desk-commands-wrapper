plugins {
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
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
