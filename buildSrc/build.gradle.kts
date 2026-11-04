plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

dependencies {
    implementation(libs.gradle.agp)
    // AGP 9's built-in Kotlin already puts kotlin-compiler-embeddable on the
    // buildSrc classpath; a second copy from the KGP dependency causes
    // duplicate-class / metadata mismatches. Keep only AGP's copy.
    implementation(libs.gradle.kotlin) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
    }
    implementation(libs.gradle.serialization)
    implementation(libs.gradle.kotlinter)
}
