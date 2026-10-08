import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
    jacoco
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

ktlint {
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { element -> element.file.path.contains("/app/src/") }
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit4)
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir(rootProject.file(providers.gradleProperty("SERVICE_APP_SOURCE_DIR").getOrElse("app/src/main/java")))
            include("ServiceTests.kt")
            include("stubs/**/*.kt")
            include("org/carcallrouter/companion/RouterSettings.kt")
            include("org/carcallrouter/companion/SessionBridge.kt")
            include("org/carcallrouter/companion/telecom/AddressedTelecomRouter.kt")
            include("org/carcallrouter/companion/telecom/RouterInCallService.kt")
        }
    }
}

application {
    mainClass.set("ServiceTestsKt")
}

tasks.named<JavaExec>("run") {
    outputs.upToDateWhen { false }
}

tasks.test {
    useJUnit()
    outputs.upToDateWhen { false }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

val productionCoverageClasses =
    sourceSets.main.get().output.asFileTree.matching {
        include("org/carcallrouter/companion/RouterSettings*")
        include("org/carcallrouter/companion/SessionBridge*")
        include("org/carcallrouter/companion/telecom/AddressedTelecomRouter*")
        include("org/carcallrouter/companion/telecom/RouterInCallService*")
    }

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    classDirectories.setFrom(productionCoverageClasses)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.test)
    classDirectories.setFrom(productionCoverageClasses)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
