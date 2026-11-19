package app.clipt.data

/**
 * Remembers text that Clipverse itself put on the clipboard (incoming clips, the in-app Copy
 * button, "Copy latest") or already uploaded, so the auto-send watcher never echoes it back.
 */
object LoopGuard {
    private const val MAX = 50
    private val recent = LinkedHashSet<String>()

    @Synchronized
    fun remember(text: String?) {
        if (text.isNullOrEmpty()) return
        recent.remove(text)
        recent.add(text)
        while (recent.size > MAX) recent.remove(recent.first())
    }

    @Synchronized
    fun isKnown(text: String): Boolean = text in recent
}
