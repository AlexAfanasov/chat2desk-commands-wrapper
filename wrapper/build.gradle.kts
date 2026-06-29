import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    id("maven-publish")
}

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    val xcf = XCFramework("Chat2DeskCommandsWrapper")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Chat2DeskCommandsWrapper"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("com.chat2desk:chat2desk_sdk:1.5.12")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("io.ktor:ktor-client-mock:3.0.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.0.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.google.code.gson:gson:2.10.1")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
                implementation("com.google.code.gson:gson:2.10.1")
                implementation("com.google.truth:truth:1.4.2")
            }
        }
        val iosMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.0.0")
            }
        }
    }
}

android {
    namespace = "com.alexafanasov.chat2desk.commands"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = false
    config.setFrom("$rootDir/config/detekt/detekt.yml")
}

afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            groupId =
                (project.findProperty("POM_GROUP") as String?)
                    ?: "com.github.AlexAfanasov.chat2desk-commands-wrapper"
            version = project.version.toString()
        }
    }
}
