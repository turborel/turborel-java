// The manual's layout, as turborel.com's documentation site reads doc/
// (RULES.md R-DOC-7), and the relative links of every document.

import java.io.File

private val LINK = Regex("""\]\(([^)\s]+)\)""")
private const val CONTENTS = "[Contents](README.md)"

/** What is wrong with the manual's layout under `doc`, and with the links of `documents`. */
fun manualProblems(root: File, doc: File, documents: List<File>): List<String> {
    val problems = mutableListOf<String>()
    val pages = doc.walkTopDown().filter { it.isFile && it.extension == "md" }.sorted().toList()
    pages.forEach { page -> problems += pageProblems(root, page) }
    doc.walkTopDown().filter { it.isDirectory && it != doc }.forEach { volume ->
        problems += volumeProblems(root, volume)
    }
    (documents + pages).distinct().forEach { file -> problems += linkProblems(root, doc, file) }
    return problems
}

/** A page opens with its `# Title`; a chapter's next line holds only its contents link. */
private fun pageProblems(root: File, page: File): List<String> {
    val lines = page.readLines().filter { it.isNotBlank() }
    val name = page.relativeTo(root).invariantSeparatorsPath
    val problems = mutableListOf<String>()
    if (lines.firstOrNull()?.startsWith("# ") != true) {
        problems += "$name does not open with its # Title"
    }
    if (page.name != "README.md" && lines.getOrNull(1) != CONTENTS) {
        problems += "$name has no line $CONTENTS after its title"
    }
    return problems
}

/** A volume's README.md lists each of its pages. */
private fun volumeProblems(root: File, volume: File): List<String> {
    val readme = File(volume, "README.md")
    val name = volume.relativeTo(root).invariantSeparatorsPath
    if (!readme.isFile) {
        return listOf("$name has no README.md")
    }
    val listed = LINK.findAll(readme.readText()).map { it.groupValues[1] }.toSet()
    return volume.listFiles().orEmpty().filter { it.isFile && it.extension == "md" && it.name != "README.md" }
        .filter { it.name !in listed }.map { "$name/README.md does not list ${it.name}" }
}

/** A relative link resolves; one in doc/ stays in doc/, a link out of it being a full URL. */
private fun linkProblems(root: File, doc: File, file: File): List<String> {
    val name = file.relativeTo(root).invariantSeparatorsPath
    val inDoc = file.canonicalPath.startsWith(doc.canonicalPath + File.separator)
    return LINK.findAll(file.readText()).map { it.groupValues[1] }
        .filterNot { Regex("^[a-z]+:").containsMatchIn(it) || it.startsWith("#") }
        .mapNotNull { link ->
            val target = File(file.parentFile, link.substringBefore('#')).canonicalFile
            when {
                !target.exists() -> "$name links to $link, which is not there"
                inDoc && !target.path.startsWith(doc.canonicalPath) -> "$name links out of doc/ to $link, not by a full URL"
                else -> null
            }
        }.toList()
}
