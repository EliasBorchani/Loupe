package dev.loupe.core.profile

/**
 * Maps the named capturing groups of a regex source to their group numbers.
 *
 * `Matcher.start("name")` exists, but it re-resolves the name on every call, and the parse loop
 * runs once per entry. `Pattern.namedGroups()` would give the mapping directly — it landed in
 * Java 20, and the toolchain here is 17 — so the source is scanned instead. Deterministic, and it
 * doubles as validation: a profile referring to a group its regex does not declare is caught at
 * load time rather than producing silent nulls at scale.
 */
object NamedGroups {

    /**
     * @return group name → group number, in declaration order.
     * @throws IllegalArgumentException if a group name is declared twice.
     */
    fun indexesOf(regexSource: String): Map<String, Int> {
        val indexes: MutableMap<String, Int> = LinkedHashMap()
        var groupNumber = 0
        var index = 0
        var insideCharacterClass = false

        while (index < regexSource.length) {
            when (regexSource[index]) {
                '\\' -> index++ // whatever follows is a literal, never a group opener

                '[' -> if (!insideCharacterClass) insideCharacterClass = true

                ']' -> insideCharacterClass = false

                '(' -> if (!insideCharacterClass) {
                    val name: String? = readGroupName(regexSource, index)
                    if (name != null) {
                        groupNumber++
                        require(indexes.put(name, groupNumber) == null) {
                            "Group '$name' is declared more than once in the profile regex"
                        }
                    } else if (isCapturing(regexSource, index)) {
                        groupNumber++
                    }
                }
            }
            index++
        }
        return indexes
    }

    /** `(?<name>` — but not the `(?<=` / `(?<!` lookbehinds, which capture nothing. */
    private fun readGroupName(regexSource: String, openingParenthesis: Int): String? {
        if (regexSource.getOrNull(openingParenthesis + 1) != '?') return null
        if (regexSource.getOrNull(openingParenthesis + 2) != '<') return null
        val firstNameCharacter: Char = regexSource.getOrNull(openingParenthesis + 3) ?: return null
        if (firstNameCharacter == '=' || firstNameCharacter == '!') return null
        val closing: Int = regexSource.indexOf('>', openingParenthesis + 3)
        if (closing < 0) return null
        return regexSource.substring(openingParenthesis + 3, closing)
    }

    /** A plain `(` captures; `(?:`, `(?=`, `(?i)` and friends do not. */
    private fun isCapturing(regexSource: String, openingParenthesis: Int): Boolean =
        regexSource.getOrNull(openingParenthesis + 1) != '?'
}
