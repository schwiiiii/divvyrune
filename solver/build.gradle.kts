// A developer tool: finds many solutions per level and writes their metrics for the in-game histograms.
// `gradle :solver:baselines` rewrites app/src/main/assets/baselines.json. Not part of the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":engine"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.register<JavaExec>("baselines") {
    description = "Finds solutions for every level and writes their metrics to the app's baselines.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.schwii.divvyrune.solver.BaselinesKt")
    args(rootProject.file("app/src/main/assets/baselines.json").absolutePath)
}
