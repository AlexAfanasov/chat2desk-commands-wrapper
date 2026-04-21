plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val publishedGroup =
    (rootProject.findProperty("POM_GROUP") as String?)
        ?: "com.github.AlexAfanasov.chat2desk-commands-wrapper"
val publishedVersion =
    System.getenv("VERSION")
        ?: (rootProject.findProperty("VERSION_NAME") as String?)
        ?: "0.1.0-SNAPSHOT"

android {
    namespace = "com.alexafanasov.chat2desk.commands.sample"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("$publishedGroup:wrapper:$publishedVersion")
}
