plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test {
    useJUnit()
    // Golden challenge fixtures live in /shared and are also run by the Cloud Functions tests.
    systemProperty("bandwagon.shared", rootProject.file("shared").absolutePath)
}
