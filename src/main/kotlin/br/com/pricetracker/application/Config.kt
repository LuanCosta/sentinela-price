package br.com.pricetracker.application

import java.nio.file.Files
import java.nio.file.Path

data class AppConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val mercadoLivreApiUrl: String,
    val mercadoLivreClientId: String,
    val mercadoLivreClientSecret: String,
    val mercadoLivreRefreshToken: String,
    val syncAdminSecret: String,
    val searchTerms: List<String>,
    val highlightCategoryIds: List<String>,
    val highlightBatchSize: Int,
    val trackedProductsBatchSize: Int
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = localEnvironment()) = AppConfig(
            dbUrl = env.required("DB_URL"),
            dbUser = env.required("DB_USER"),
            dbPassword = env.required("DB_PASSWORD"),
            mercadoLivreApiUrl = env["MERCADO_LIVRE_API_URL"]?.trimEnd('/') ?: "https://api.mercadolibre.com",
            mercadoLivreClientId = env.required("MERCADO_LIVRE_CLIENT_ID"),
            mercadoLivreClientSecret = env.required("MERCADO_LIVRE_CLIENT_SECRET"),
            mercadoLivreRefreshToken = env.required("MERCADO_LIVRE_REFRESH_TOKEN"),
            syncAdminSecret = env.required("SYNC_ADMIN_SECRET"),
            searchTerms = env.csv("SEARCH_TERMS"),
            highlightCategoryIds = env.csv("HIGHLIGHT_CATEGORY_IDS").ifEmpty { DEFAULT_HIGHLIGHT_CATEGORIES },
            highlightBatchSize = env["HIGHLIGHT_BATCH_SIZE"]?.toIntOrNull()?.coerceIn(1, 10) ?: 3,
            trackedProductsBatchSize = env["TRACKED_PRODUCTS_BATCH_SIZE"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        )

        private val DEFAULT_HIGHLIGHT_CATEGORIES = listOf(
            "MLB1055", "MLB1652", "MLB1002", "MLB270287",
            "MLB6284", "MLB264874", "MLB9206", "MLB123100",
            "MLB122102", "MLB6883", "MLB48611", "MLB31532"
        )
    }
}

/** Carrega .env localmente; variáveis reais do sistema sempre têm prioridade. */
private fun localEnvironment(): Map<String, String> = loadDotEnv() + System.getenv()

private fun loadDotEnv(path: Path = Path.of(".env")): Map<String, String> {
    if (!Files.isRegularFile(path)) return emptyMap()
    return Files.readAllLines(path).mapNotNull { rawLine ->
        val line = rawLine.trim().removePrefix("\uFEFF")
        if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@mapNotNull null
        val (name, rawValue) = line.split('=', limit = 2)
        val key = name.trim().removePrefix("export ").trim()
        if (key.isBlank()) return@mapNotNull null
        val value = rawValue.trim().let {
            if (it.length >= 2 && ((it.startsWith('"') && it.endsWith('"')) || (it.startsWith('\'') && it.endsWith('\''))))
                it.substring(1, it.length - 1) else it
        }
        key to value
    }.toMap()
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf { it.isNotBlank() } ?: error("Variável de ambiente obrigatória ausente: $name")

private fun Map<String, String>.csv(name: String): List<String> =
    get(name).orEmpty().split(',').map(String::trim).filter(String::isNotBlank).distinct()
