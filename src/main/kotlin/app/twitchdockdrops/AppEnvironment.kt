package app.twitchdockdrops

import java.nio.file.Path
import kotlin.io.path.Path

data class AppEnvironment(
    val dataDirectory: Path,
    val port: Int,
    val listenHost: String,
    val allowLanAccess: Boolean,
    val trustedHosts: Set<String>,
    val trustedOrigins: Set<String>,
    val sessionKey: String?,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppEnvironment {
            val dataDirectory = Path(
                environment["TWITCH_DROPS_DATA_DIR"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: "./data",
            ).toAbsolutePath().normalize()
            val configuredPort = environment["TWITCH_DROPS_PORT"]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val port = configuredPort?.toIntOrNull()
                ?: if (configuredPort == null) 8080 else {
                    throw IllegalArgumentException("TWITCH_DROPS_PORT must be a numeric port.")
                }
            require(port in 1..65535) { "TWITCH_DROPS_PORT must be between 1 and 65535." }

            val listenHost = environment["TWITCH_DROPS_LISTEN_HOST"]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "127.0.0.1"
            val allowLanAccess = environment.boolean("TWITCH_DROPS_ALLOW_LAN")
            val trustedHosts = environment.csv("TWITCH_DROPS_TRUSTED_HOSTS")
                .ifEmpty { setOf("localhost", "127.0.0.1", "[::1]") }
            val trustedOrigins = environment.csv("TWITCH_DROPS_TRUSTED_ORIGINS")
                .ifEmpty {
                    setOf(
                        "http://localhost:$port",
                        "http://127.0.0.1:$port",
                        "http://[::1]:$port",
                    )
                }

            return AppEnvironment(
                dataDirectory = dataDirectory,
                port = port,
                listenHost = listenHost,
                allowLanAccess = allowLanAccess,
                trustedHosts = trustedHosts,
                trustedOrigins = trustedOrigins,
                sessionKey = environment["TWITCH_DROPS_SESSION_KEY"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty),
            )
        }
    }
}

private fun Map<String, String>.csv(name: String): Set<String> = this[name]
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.toSet()
    .orEmpty()

private fun Map<String, String>.boolean(name: String): Boolean {
    val configured = this[name]
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return false
    return when (configured.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$name must be true or false.")
    }
}
