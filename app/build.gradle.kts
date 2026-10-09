import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
}

val configuredApplicationId =
    providers
        .gradleProperty("APP_APPLICATION_ID")
        .orElse("org.carcallrouter.companion")
val configuredVersionCode =
    providers
        .gradleProperty("APP_VERSION_CODE")
        .map { it.toInt() }
        .getOrElse(18)
val configuredVersionName =
    providers
        .gradleProperty("APP_VERSION_NAME")
        .orElse("0.3.0-beta.16")
val enableBetaSigning =
    providers
        .gradleProperty("ENABLE_BETA_SIGNING")
        .map { it.toBoolean() }
        .getOrElse(false)

android {
    namespace = "org.carcallrouter.companion"
    // Compile the public endpoint-request callback; retain the qualified target behavior.
    compileSdk = 37

    defaultConfig {
        applicationId = configuredApplicationId.get()
        minSdk = 34
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName.get()
    }

    buildFeatures { buildConfig = true }
    signingConfigs {
        if (enableBetaSigning) {
            create("beta") {
                storeFile = file(providers.environmentVariable("BETA_KEYSTORE_FILE").get())
                storePassword = providers.environmentVariable("BETA_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BETA_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BETA_KEY_PASSWORD").get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    buildTypes {
        debug {
            if (enableBetaSigning) signingConfig = signingConfigs.getByName("beta")
        }
        release {
            isMinifyEnabled = false
            if (enableBetaSigning) signingConfig = signingConfigs.getByName("beta")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        baseline = file("lint-baseline.xml")
        // Dependabot owns version-availability reporting; lint still enforces source findings.
        disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable")
        warningsAsErrors = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

ktlint {
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit4)
}
