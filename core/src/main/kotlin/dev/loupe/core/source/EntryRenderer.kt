package dev.loupe.core.source

import dev.loupe.core.index.LogIndex
import dev.loupe.core.io.TextSources
import dev.loupe.core.profile.CompiledProfile

/**
 * One entry, split into the parts a row needs to draw.
 *
 * [message] is the profile's message group; [continuations] are the wrapped lines and stack-trace
 * frames that belong to the same entry, with the declared indent stripped.
 */
class RenderedEntry(val raw: String, val message: String, val continuations: List<String>) {
    val hasContinuations: Boolean get() = continuations.isNotEmpty()
}

/**
 * Turns an entry's bytes into something a row can draw.
 *
 * Deliberately re-runs the profile regex rather than storing a message offset per entry. Only the
 * forty-odd rows on screen are ever rendered, so this costs microseconds per frame — against four
 * bytes per entry for all nine million, most of which will never be looked at.
 */
object EntryRenderer {

    fun render(source: LogSource, entry: Int): RenderedEntry = render(profile = source.profile, raw = source.rawText(entry))

    fun render(profile: CompiledProfile, raw: String): RenderedEntry {
        val lines: List<String> = raw.split('\n')
        val opening: String = lines.first()

        val continuations: List<String> = lines.drop(1).map { line ->
            if (profile.stripContinuationIndent) line.trimStart(' ') else line
        }

        val matcher = profile.pattern.matcher(opening)
        val message: String = if (profile.messageGroup != CompiledProfile.NO_GROUP && matcher.matches()) {
            matcher.group(profile.messageGroup) ?: ""
        } else {
            // A profile with no message group, or a line that somehow no longer matches: showing
            // the whole line beats showing nothing.
            opening
        }

        return RenderedEntry(raw = raw, message = message, continuations = continuations)
    }
}
