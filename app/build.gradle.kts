import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val naviDistanceUnit = providers.gradleProperty("naviDistanceUnit").orElse("unverified").get()
require(naviDistanceUnit in setOf("unverified", "meters")) { "naviDistanceUnit must be unverified or meters (only after unit verification)" }

// Local signing material is never committed. Debug/logic tasks do not require it.
// https://developer.android.com/studio/publish/app-signing#secure-key
val releasePropertiesFile = rootProject.file(".local/release.properties")
// W16 verification must not read existing local signing keys/settings.
val isolatedVerification = providers.gradleProperty("isolatedVerification").orElse("false").get().toBooleanStrict()
val releaseProperties = Properties().apply {
    if (!isolatedVerification && releasePropertiesFile.isFile) releasePropertiesFile.inputStream().use { load(it) }
}
val validateReleaseSigning by tasks.registering {
    doLast {
        if (isolatedVerification) throw GradleException("Isolated verification supports Debug only; existing local signing material is not read.")
        if (!releasePropertiesFile.isFile) throw GradleException(
            "Release signing requires .local/release.properties. See README build instructions; no unsigned fallback.")
        val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        if (required.any { releaseProperties.getProperty(it).isNullOrBlank() }) throw GradleException(
            "Release signing properties must contain nonempty storeFile/storePassword/keyAlias/keyPassword.")
        if (!rootProject.file(releaseProperties.getProperty("storeFile")).isFile) throw GradleException(
            "Release keystore is missing. Generate the local keystore as described in README.")
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") dependsOn(validateReleaseSigning)
}

android {
    namespace = "lab.pleos.observe"
    // minSdk >=28: getting-started; targetSdk 35: official sample release.
    // https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app
    // Compile with the already installed platform; runtime remains API 34 compatible.
    compileSdk = 36
    useLibrary("android.car")

    defaultConfig {
        applicationId = "lab.pleos.observe"
        minSdk = 28
        targetSdk = 35
        versionCode = 25
        versionName = "1.9.1"
        // Double distance has no verified unit: opt in only after documented metre validation.
        buildConfigField("boolean", "NAVI_DISTANCE_METERS", (naviDistanceUnit == "meters").toString())
    }
    buildFeatures { buildConfig = true }
    signingConfigs.create("release") {
        storeFile = releaseProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
        storePassword = releaseProperties.getProperty("storePassword")
        keyAlias = releaseProperties.getProperty("keyAlias")
        keyPassword = releaseProperties.getProperty("keyPassword")
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "AI_PROXY_BASE_URL", "\"http://10.0.2.2:8787\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            // Judging on AAOS uses the host alias; network config permits HTTP only for this host.
            buildConfigField("String", "AI_PROXY_BASE_URL", "\"http://10.0.2.2:8787\"")
        }
    }
    signingConfigs.getByName("debug") {
        storeFile = if (isolatedVerification) rootProject.file("build/w16/debug.keystore") else rootProject.file(".local/debug.keystore")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":logic"))
    // Verified Maven latest/release=2.3.2 on 2026-10-22; see docs/API_EVIDENCE.md.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // javap verified 3.0.5 + transitive navi-data 3.0.4; rules work without guidance.
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
