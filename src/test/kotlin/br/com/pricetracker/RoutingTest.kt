package br.com.pricetracker

import br.com.pricetracker.application.plugins.*
import br.com.pricetracker.data.remote.MarketClient
import br.com.pricetracker.data.repository.ProductRepository
import br.com.pricetracker.data.repository.SyncRepository
import br.com.pricetracker.domain.service.ProductSyncService
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import br.com.pricetracker.domain.model.MarketItem
import java.math.BigDecimal
import kotlin.test.*

class RoutingTest {
    @Test fun `segredo administrativo invalido retorna 401`() = testApplication {
        val db = testDatabase(); val products = ProductRepository(db); val executions = SyncRepository(db)
        application {
            configureSerialization(); configureStatusPages()
            configureRouting(products, executions, ProductSyncService(MarketClient { emptyList() }, products, executions, listOf("x")), "correto")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/admin/sync") { header("X-Admin-Secret", "errado") }.status)
    }

    @Test fun `segunda sincronizacao simultanea retorna conflito`() = testApplication {
        val db = testDatabase(); val products = ProductRepository(db); val executions = SyncRepository(db)
        executions.start(listOf("em andamento"))
        application {
            configureSerialization(); configureStatusPages()
            configureRouting(products, executions, ProductSyncService(MarketClient { emptyList() }, products, executions, listOf("x")), "secret")
        }
        val response = client.post("/api/admin/sync") { header("X-Admin-Secret", "secret") }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test fun `exporta urls de promocoes em csv ou linhas`() = testApplication {
        val db = testDatabase(); val products = ProductRepository(db); val executions = SyncRepository(db)
        val execution = executions.start(listOf("seed"))
        products.process(
            MarketItem(
                "MLB1", "CAT1", "Produto", "https://produto/MLB1", null, 1, "BRL",
                BigDecimal("80.00"), BigDecimal("100.00"), 1, "new", "active"
            ), execution
        )
        executions.finish(execution, br.com.pricetracker.domain.model.SyncStatus.SUCCESS, br.com.pricetracker.domain.model.SyncCounters())
        application {
            configureSerialization(); configureStatusPages()
            configureRouting(products, executions, ProductSyncService(MarketClient { emptyList() }, products, executions, listOf("x")), "secret")
        }

        assertEquals("https://produto/MLB1", client.get("/api/promotions/urls").bodyAsText())
        assertEquals("https://produto/MLB1", client.get("/api/promotions/urls?format=csv").bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/promotions/urls?format=json").status)

        val imported = client.post("/api/admin/affiliate-links/ordered") {
            header("X-Admin-Secret", "secret")
            contentType(ContentType.Text.Plain)
            setBody("https://meli.la/abc123")
        }
        assertEquals(HttpStatusCode.OK, imported.status)
        assertTrue(imported.bodyAsText().contains("\"imported\":1"))

        val wrongCount = client.post("/api/admin/affiliate-links/ordered") {
            header("X-Admin-Secret", "secret")
            contentType(ContentType.Text.Plain)
            setBody("https://meli.la/a\nhttps://meli.la/b")
        }
        assertEquals(HttpStatusCode.BadRequest, wrongCount.status)
    }

    @Test fun `importacao de afiliados exige segredo administrativo`() = testApplication {
        val db = testDatabase(); val products = ProductRepository(db); val executions = SyncRepository(db)
        application {
            configureSerialization(); configureStatusPages()
            configureRouting(products, executions, ProductSyncService(MarketClient { emptyList() }, products, executions, listOf("x")), "secret")
        }
        val body = """{"entries":[{"originalUrl":"https://produto/x","affiliateUrl":"https://meli.la/abc"}]}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/admin/affiliate-links") {
            contentType(ContentType.Application.Json); setBody(body)
        }.status)
    }
}
