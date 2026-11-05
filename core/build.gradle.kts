import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: the assistant's "brain" (Claude), speech-to-text client, data model and storage.
// It has no Android dependencies, so it is unit-tested on a plain JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("com.anthropic:anthropic-java:3.3.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    // Tests tagged "live" call the real Claude / speech-to-text APIs (and cost a little); they only run
    // through :core:liveTest below, never as part of the normal test run or CI.
    useJUnitPlatform { excludeTags("live") }
}

// Real-environment tests. They read your own keys from the environment and skip themselves when a key is
// missing:
//   ANTHROPIC_API_KEY   console.anthropic.com/settings/keys   (required for the Claude tests)
//   GROQ_API_KEY        console.groq.com/keys                 (for the speech-to-text tests)
//   AIDE_LIVE_MODEL     optional, default claude-haiku-5-5 (the cheapest); e.g. claude-opus-5-5
// Run:  ./gradlew :core:liveTest
tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs the live tests against the real Claude and speech-to-text APIs (needs API keys)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
