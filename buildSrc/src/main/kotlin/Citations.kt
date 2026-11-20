// The citations a source or a document makes, and those it may make
// (RULES.md D6, R-TRACE-1, R-TRACE-2).

import java.io.File

/** What a citation may name: the headings of Turborel's documents, and RULES.md's decisions and rules. */
class Citable(headings: File, rules: File) {
    /** "PROTOCOL <heading>" and "CLIENT <heading>", as config/headings.txt keeps them. */
    val headings: Set<String> =
        headings.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim() }.toSet()

    private val rulesText = rules.readText()

    /** The decisions of RULES.md, by their numbers. */
    val decisions: Set<Int> =
        Regex("""(?m)^#### D(\d+)\. """).findAll(rulesText).map { it.groupValues[1].toInt() }.toSet()

    /** The requirements of RULES.md's tables, as R-ERR-1. */
    val requirements: Set<String> =
        Regex("""(?m)^\| (R-[A-Z]+-\d+) \|""").findAll(rulesText).map { it.groupValues[1] }.toSet()
}

/** A citation that names nothing, where it is. */
data class Unknown(val file: File, val line: Int, val citation: String)

private val HEADING = Regex("""\[(PROTOCOL|CLIENT) ([^\]<>{}]+)\]""")
private val DESIGN = Regex("""\[DESIGN\] D(\d+)""")
private val OURS = Regex("""(?<![Tt]urborel's )RULES\.md(?:\]\(RULES\.md\))? D(\d+)""")
private val REQUIREMENT = Regex("""\bR-[A-Z]+-\d+\b""")

/** Every citation of `text` that names a heading, decision or rule not there. */
fun unknownCitations(file: File, text: String, citable: Citable): List<Unknown> {
    val found = mutableListOf<Unknown>()
    text.lines().forEachIndexed { index, line ->
        val at = index + 1
        HEADING.findAll(line).filter { "${it.groupValues[1]} ${it.groupValues[2]}" !in citable.headings }
            .forEach { found += Unknown(file, at, it.value) }
        (DESIGN.findAll(line) + OURS.findAll(line)).filter { it.groupValues[1].toInt() !in citable.decisions }
            .forEach { found += Unknown(file, at, it.value) }
        REQUIREMENT.findAll(line).filter { it.value !in citable.requirements }
            .forEach { found += Unknown(file, at, it.value) }
    }
    return found
}

private val TEST = Regex("""@(Test|ParameterizedTest|RepeatedTest)\b""")
private val MARGIN = Regex("""\n\s*\*(?!/)""")
private val TAG =
    Regex("""^/\*\*\s*Verifies: (\[(PROTOCOL|CLIENT) [^\]]+\]|\[DESIGN\] D\d+|\[TOOL\] R-[A-Z]+-\d+)""")

/** The lines of the tests of a Java source whose Javadoc comment does not begin with a tag of D6. */
fun uncitedTests(text: String): List<Int> =
    TEST.findAll(text).map { it.range.first }.filterNot { at ->
        // The doc comment just before the test's annotations, with no
        // statement or block between them, begins with a citation.
        val before = text.substring(0, at)
        val start = before.lastIndexOf("/**")
        val end = before.lastIndexOf("*/")
        start >= 0 && end > start && before.substring(end).none { it == ';' || it == '}' } &&
            TAG.containsMatchIn(before.substring(start, end).replace(MARGIN, " "))
    }.map { at -> text.substring(0, at).count { it == '\n' } + 1 }.toList()
