// The checks tool's task `traceability` (RULES.md D4): every test cites what
// it verifies (R-TRACE-1), every citation names a heading, decision or rule
// that is there (D6, R-TRACE-2), and the manual keeps the layout the docs
// site reads (R-DOC-7).

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Checks citations and the manual, failing with every problem found. */
abstract class TraceabilityTask : DefaultTask() {
    /** The tests, whose citations R-TRACE-1 asks for. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tests: ConfigurableFileCollection

    /** Every source, configuration and document whose citations R-TRACE-2 checks. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cited: ConfigurableFileCollection

    /** The documents whose relative links must resolve. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    /** The manual. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manual: DirectoryProperty

    /** The repository's root, against which paths are reported; not itself an input. */
    @get:Internal
    abstract val root: DirectoryProperty

    /** config/headings.txt. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headings: RegularFileProperty

    /** RULES.md. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rules: RegularFileProperty

    /** Written once every check passes, so an unchanged tree is not checked again. */
    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val root = root.get().asFile
        val citable = Citable(headings.get().asFile, rules.get().asFile)
        val problems = mutableListOf<String>()
        tests.files.sorted().forEach { file ->
            uncitedTests(file.readText()).forEach { line ->
                problems += "${file.relativeTo(root).invariantSeparatorsPath}:$line: a test with no `Verifies:` citation (R-TRACE-1)"
            }
        }
        cited.files.sorted().forEach { file ->
            unknownCitations(file, file.readText(), citable).forEach {
                problems += "${it.file.relativeTo(root).invariantSeparatorsPath}:${it.line}: ${it.citation} names nothing there (R-TRACE-2)"
            }
        }
        problems += manualProblems(root, manual.get().asFile, documents.files.toList()).map { "$it (R-DOC-7)" }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", "traceability:\n"))
        }
        stamp.get().asFile.writeText("ok\n")
    }
}
