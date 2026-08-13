package app.twitchdockdrops.security

object SafeText {
    private const val DefaultDiagnosticLimit = 512

    private val labeledSecret = Regex(
        "(?i)((?:authorization|access[_-]?token|device[_-]?code|session[_-]?key|encryption[_-]?key|twitch_drops_session_key)\\s*[=:]\\s*[\\\"']?(?:oauth\\s+|bearer\\s+)?)[^\\s\\\"',;}]+",
    )
    private val authorizationSecret = Regex("(?i)\\b(OAuth|Bearer)\\s+[A-Za-z0-9._~+/=-]+")

    fun diagnostic(value: String?, maximumLength: Int = DefaultDiagnosticLimit): String {
        if (value.isNullOrBlank()) return "No diagnostic detail was provided."
        val normalized = value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString("")
            .replace(labeledSecret, "$1[redacted]")
            .replace(authorizationSecret, "$1 [redacted]")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maximumLength) return normalized
        return normalized.take((maximumLength - 1).coerceAtLeast(0)) + "…"
    }
}
