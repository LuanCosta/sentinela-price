package br.com.pricetracker

import br.com.pricetracker.application.AppConfig
import br.com.pricetracker.application.plugins.*
import br.com.pricetracker.data.remote.MercadoLivreClient
import br.com.pricetracker.data.remote.MercadoLivreTokenService
import br.com.pricetracker.data.repository.MercadoLivreTokenRepository
import br.com.pricetracker.data.repository.HighlightRotationRepository
import br.com.pricetracker.data.repository.ProductRepository
import br.com.pricetracker.data.repository.SyncRepository
import br.com.pricetracker.domain.service.ProductSyncService
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.json.Json

fun Application.module() {
    val config = AppConfig.fromEnvironment()
    val database = connectDatabase(config)
    val products = ProductRepository(database)
    val executions = SyncRepository(database)
    val http = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    }
    monitor.subscribe(ApplicationStopped) { http.close() }
    val tokenService = MercadoLivreTokenService(
        http = http,
        repository = MercadoLivreTokenRepository(database),
        clientId = config.mercadoLivreClientId,
        clientSecret = config.mercadoLivreClientSecret,
        bootstrapRefreshToken = config.mercadoLivreRefreshToken
    )
    val market = MercadoLivreClient(http, config.mercadoLivreApiUrl, tokenService)
    val service = ProductSyncService(
        marketClient = market,
        products = products,
        executions = executions,
        defaultTerms = config.searchTerms,
        highlightClient = market,
        highlightRotation = HighlightRotationRepository(database),
        highlightCategories = config.highlightCategoryIds,
        highlightBatchSize = config.highlightBatchSize,
        trackedItemClient = market,
        trackedProductsBatchSize = config.trackedProductsBatchSize
    )
    configureSerialization()
    configureStatusPages()
    configureRouting(products, executions, service, config.syncAdminSecret)
}
