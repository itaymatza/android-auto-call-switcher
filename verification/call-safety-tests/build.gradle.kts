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
        include("stubs/SuppressLint.kt")
        include("org/carcallrouter/companion/telecom/CellularClassifier.kt")
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
            include("org/carcallrouter/companion/telecom/CellularClassifier*")
        },
    )
    reports { xml.required.set(true) }
}
