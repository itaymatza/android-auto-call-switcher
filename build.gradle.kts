import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<Test>().configureEach {
            javaLauncher.set(
                toolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("TEST_JAVA_VERSION").getOrElse("17")))
                },
            )
            doFirst { logger.lifecycle("Test runtime: JDK ${javaLauncher.get().metadata.languageVersion}") }
        }
    }
}
