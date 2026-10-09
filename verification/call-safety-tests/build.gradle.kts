plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    jacoco
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit4)
}

sourceSets.main {
    kotlin {
        srcDir("src/main/kotlin")
        srcDir(rootProject.file("app/src/main/java"))
        srcDir(rootProject.file("verification/service-tests/src/main/kotlin"))
        include("safetystubs/**/*.kt")
        include("stubs/SuppressLint.kt", "stubs/Os.kt")
        include("org/carcallrouter/companion/telecom/CellularClassifier.kt", "org/carcallrouter/companion/telecom/AuthorizationMonitor.kt")
    }
}

ktlint {
    filter { exclude { it.file.path.contains("/app/src/") || it.file.path.contains("/service-tests/") } }
}

tasks.test {
    useJUnit()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        sourceSets.main.get().output.asFileTree.matching {
            include("org/carcallrouter/companion/telecom/CellularClassifier*", "org/carcallrouter/companion/telecom/AuthorizationMonitor*")
        },
    )
    reports { xml.required.set(true) }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
