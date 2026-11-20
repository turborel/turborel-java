// The checks tool's tasks, in Kotlin, as the build itself is (RULES.md D4,
// R-PROC-5). Every warning of the compiler is an error (R-LINT-1).

plugins {
    `kotlin-dsl`
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}
