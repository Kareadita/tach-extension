plugins {
    id("com.android.library")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "keiyoushi.utils"

    sourceSets {
        named("main") {
            java.setSrcDirs(listOf("src"))
            kotlin.setSrcDirs(listOf("src"))
        }
    }

    buildFeatures {
        resValues = false
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}
