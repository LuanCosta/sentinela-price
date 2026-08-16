package br.com.pricetracker.application.plugins

import br.com.pricetracker.data.repository.ProductRepository
import br.com.pricetracker.data.repository.SyncRepository
import br.com.pricetracker.domain.model.HealthResponse
import br.com.pricetracker.domain.model.SyncRequest
import br.com.pricetracker.domain.model.AffiliateLinksImportRequest
import br.com.pricetracker.domain.service.ProductSyncService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest

fun Application.configureRouting(
    products: ProductRepository,
    executions: SyncRepository,
    syncService: ProductSyncService,
    adminSecret: String
) {
    routing {
        get("/health") { call.respond(HealthResponse("ok")) }
        route("/api") {
            get("/products") {
                val (page, limit) = call.pagination()
                call.respond(products.list(page, limit, call.request.queryParameters["search"]))
            }
            get("/products/{productId}/history") {
                val id = call.parameters["productId"]?.toLongOrNull()
                    ?: throw br.com.pricetracker.data.repository.InvalidRequestException("productId inválido")
                val (page, limit) = call.pagination(50)
                call.respond(products.history(id, page, limit))
            }
            get("/promotions") {
                val (page, limit) = call.pagination()
                val active = call.request.queryParameters["active"]?.let {
                    it.toBooleanStrictOrNull() ?: throw br.com.pricetracker.data.repository.InvalidRequestException("active deve ser true ou false")
                }
                call.respond(products.promotions(page, limit, active))
            }
            get("/promotions/urls") {
                val separator = when (call.request.queryParameters["format"]?.lowercase()) {
                    null, "lines" -> "\n"
                    "csv" -> ","
                    else -> throw br.com.pricetracker.data.repository.InvalidRequestException("format deve ser csv ou lines")
                }
                call.respondText(products.activePromotionUrls().joinToString(separator), ContentType.Text.Plain)
            }
            get("/promotions/{promotionId}") {
                val id = call.parameters["promotionId"]?.toLongOrNull()
                    ?: throw br.com.pricetracker.data.repository.InvalidRequestException("promotionId inválido")
                call.respond(products.promotion(id))
            }
            route("/admin") {
                intercept(ApplicationCallPipeline.Call) { requireAdmin(call, adminSecret) }
                post("/affiliate-links") {
                    call.respond(products.importAffiliateLinks(call.receive<AffiliateLinksImportRequest>()))
                }
                post("/affiliate-links/ordered") {
                    val links = call.receiveText().lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                    call.respond(products.importOrderedAffiliateLinks(links))
                }
                post("/sync") {
                    val request = if ((call.request.contentLength() ?: 0L) == 0L) SyncRequest() else call.receive<SyncRequest>()
                    call.respond(syncService.synchronize(request.searchTerms))
                }
                get("/sync/executions") {
                    val (page, limit) = call.pagination()
                    call.respond(executions.list(page, limit))
                }
            }
        }
    }
}

private fun ApplicationCall.pagination(defaultLimit: Int = 20): Pair<Int, Int> {
    val page = request.queryParameters["page"]?.toIntOrNull() ?: 1
    val limit = request.queryParameters["limit"]?.toIntOrNull() ?: defaultLimit
    if (page < 1 || limit !in 1..100) throw br.com.pricetracker.data.repository.InvalidRequestException("page deve ser >= 1 e limit entre 1 e 100")
    return page to limit
}

private fun requireAdmin(call: ApplicationCall, expected: String) {
    val supplied = call.request.headers["X-Admin-Secret"] ?: throw UnauthorizedException()
    if (!MessageDigest.isEqual(supplied.toByteArray(), expected.toByteArray())) throw UnauthorizedException()
}
