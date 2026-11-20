// turborel-jdbc: the JDBC 4.3 driver for turborel (RULES.md D1, D2).
//
// `./gradlew check` runs every check a change must pass (RULES.md R-PROC-1):
// javac with every lint as an error, Error Prone and NullAway, Spotless,
// Checkstyle, SpotBugs, Javadoc, the tests, their citations, and the coverage
// floor. Versions are in gradle/libs.versions.toml.

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    `maven-publish`
    signing
    checkstyle
    jacoco
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.spotless)
}

group = "com.turborel"
// The turborel release the driver is made for: the server's number (VERSIONING.md).
version = "0.0.0"
description = "The JDBC driver for turborel, speaking its native protocol in Java alone"

// The release the library is compiled for and its JDK, the oldest it runs on (RULES.md D3).
val javaRelease = 17

// The JDK the tests run on: 17 unless `-PtestJava=<n>` names another, as CI
// does for each supported release (RULES.md D3).
val testJava = providers.gradleProperty("testJava").map(String::toInt).orElse(javaRelease)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaRelease)
    }
    withSourcesJar()
    withJavadocJar()
}

// Error Prone and NullAway, kept out of the `errorprone` configuration the
// plugin puts on every compile's processor path: javac loads a plug-in from
// that path even where it is not enabled, and JDK 17 cannot load this one.
val errorProneTools = configurations.dependencyScope("errorProneTools")
val errorProneToolsPath = configurations.resolvable("errorProneToolsPath") {
    extendsFrom(errorProneTools.get())
}

dependencies {
    // Annotations alone, read as the code compiles; `requires static` in module-info (RULES.md D10).
    compileOnly(libs.jspecify)
    testCompileOnly(libs.jspecify)

    errorProneTools(libs.errorprone.core)
    errorProneTools(libs.nullaway)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Every javac lint is an error (RULES.md R-LINT-1). Error Prone runs in
// tasks of its own, below.
tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    options.errorprone.enabled = false
}

// The library's own doc comments are checked as it compiles, every public
// and protected item (RULES.md R-DOC-1).
tasks.compileJava {
    options.compilerArgs.add("-Xdoclint:all/protected")
}

// The JDK the analysis tools run on. Error Prone and Checkstyle run only on
// JDK 21 or later (RULES.md D10).
val toolsJava = 21

// Error Prone compiles the sources a second time, on the tools' JDK, for
// release 17 still, and its classes are thrown away: the classes published
// are JDK 17's javac's.

fun errorProne(name: String, set: SourceSet, classes: FileCollection) =
    tasks.register<JavaCompile>(name) {
        group = "verification"
        description = "Compiles the ${set.name} sources under Error Prone and NullAway (RULES.md R-LINT-2)."
        source = set.java
        classpath = classes
        options.annotationProcessorPath = errorProneToolsPath.get()
        destinationDirectory = layout.buildDirectory.dir("errorprone/${set.name}")
        javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(toolsJava) }
        options.errorprone {
            enabled = true
            disableWarningsInGeneratedCode = true
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:OnlyNullMarked", "true")
            option("NullAway:JSpecifyMode", "true")
        }
    }

val errorProneMain = errorProne("errorProneMain", sourceSets.main.get(), sourceSets.main.get().compileClasspath)
val errorProneTest = errorProne(
    "errorProneTest",
    sourceSets.test.get(),
    sourceSets.test.get().compileClasspath,
)

tasks.javadoc {
    // The API is the exported package; the internal packages are documented
    // in their source, checked by javac above.
    exclude("com/turborel/jdbc/internal/**")
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}

tasks.test {
    useJUnitPlatform()
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = testJava.map { JavaLanguageVersion.of(it) }
    }
    finalizedBy(tasks.jacocoTestReport)
}

spotless {
    kotlin {
        target("buildSrc/src/**/*.kt")
        trimTrailingWhitespace()
        endWithNewline()
    }
    java {
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "buildSrc/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Checkstyle>().configureEach {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(toolsJava) }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configDirectory = layout.projectDirectory.dir("config/checkstyle")
    maxWarnings = 0
    isIgnoreFailures = false
}

spotbugs {
    toolVersion = libs.versions.spotbugs.get()
    effort = Effort.MAX
    reportLevel = Confidence.LOW
    ignoreFailures = false
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

// The coverage floor (RULES.md R-COV-1): it only rises (R-COV-2).
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

// The checks tool's own checks (RULES.md D4, D6): every test cites what it
// verifies, every citation names what is there, and the manual keeps the
// layout the docs site reads (buildSrc/).
val traceability = tasks.register<TraceabilityTask>("traceability") {
    group = "verification"
    description = "Checks citations and the manual's layout (RULES.md R-TRACE-1, R-TRACE-2, R-DOC-7)."
    tests.from(fileTree("src/test/java") { include("**/*.java") })
    cited.from(
        fileTree("src") { include("**/*.java") },
        fileTree("buildSrc/src") { include("**/*.kt") },
        fileTree("config") { include("**/*.xml") },
        fileTree(".github") { include("**/*.yml", "**/*.md") },
        fileTree("doc") { include("**/*.md") },
        files("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml"),
        files(".githooks/pre-commit"),
        fileTree(".") { include("*.md") },
    )
    documents.from(fileTree(".") { include("*.md") }, fileTree(".github") { include("**/*.md") })
    manual = layout.projectDirectory.dir("doc")
    root = layout.projectDirectory
    headings = layout.projectDirectory.file("config/headings.txt")
    rules = layout.projectDirectory.file("RULES.md")
    stamp = layout.buildDirectory.file("traceability/ok")
}

tasks.check {
    dependsOn(traceability, errorProneMain, errorProneTest, tasks.javadoc, tasks.jacocoTestCoverageVerification)
}

tasks.withType<Jar>().configureEach {
    // The license travels inside every jar (RULES.md D11).
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "turborel-jdbc"
            from(components["java"])
            pom {
                name = "turborel-jdbc"
                description = project.description
                url = "https://github.com/turborel/turborel-java"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "BSD-2-Clause-Patent"
                        url = "https://spdx.org/licenses/BSD-2-Clause-Patent.html"
                        distribution = "repo"
                    }
                }
                organization {
                    name = "Turborel Inc."
                    url = "https://github.com/turborel"
                }
                developers {
                    developer {
                        id = "turborel"
                        name = "Turborel Inc."
                        email = "hello@turborel.com"
                    }
                }
                scm {
                    url = "https://github.com/turborel/turborel-java"
                    connection = "scm:git:https://github.com/turborel/turborel-java.git"
                    developerConnection = "scm:git:ssh://git@github.com/turborel/turborel-java.git"
                }
                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/turborel/turborel-java/issues"
                }
            }
        }
    }
    // Publishing writes to a directory in the build, and nothing leaves the
    // machine: the release workflow uploads it only once the owner enables
    // that step (VERSIONING.md).
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

// Signed only where a key is given, as the release workflow will give it.
signing {
    val key = providers.gradleProperty("signingKey")
    val password = providers.gradleProperty("signingPassword")
    isRequired = false
    if (key.isPresent) {
        useInMemoryPgpKeys(key.get(), password.orNull)
        sign(publishing.publications["maven"])
    }
}
