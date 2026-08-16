package br.com.pricetracker

import br.com.pricetracker.data.remote.MercadoLivreClient
import br.com.pricetracker.data.remote.AccessTokenProvider
import br.com.pricetracker.data.repository.ExternalApiException
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class MercadoLivreClientTest {
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test fun `percorre catalogo e anuncios consulta preco e remove duplicatas`() = runBlocking {
        val requested = mutableListOf<String>()
        val http = client { request ->
            requested += request.url.encodedPath + request.url.encodedQuery.let { if (it.isBlank()) "" else "?$it" }
            val path = request.url.encodedPath
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val json = when {
                path == "/products/search" && offset == 0 -> catalogPage(2, 0, "MLB-P1")
                path == "/products/search" -> catalogPage(2, 1, "MLB-P2")
                path == "/products/MLB-P1/items" -> itemPage("MLB1", "MLB2")
                path == "/products/MLB-P2/items" -> itemPage("MLB2", "MLB3")
                path == "/items/MLB1" -> itemDetail("MLB1")
                path == "/items/MLB2" -> itemDetail("MLB2")
                path == "/items/MLB3" -> itemDetail("MLB3")
                path == "/items/MLB1/sale_price" -> salePrice("10.90", "12.00")
                path == "/items/MLB2/sale_price" -> salePrice("20.00", null)
                path == "/items/MLB3/sale_price" -> salePrice("30.50", "35.00")
                else -> error("URL inesperada: ${request.url}")
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val items = MercadoLivreClient(
            http, "https://api.test", tokenProvider(), catalogPageSize = 1, maxCatalogOffset = 2
        ).search("notebook")

        assertEquals(3, items.size)
        assertEquals(listOf("MLB1", "MLB2", "MLB3"), items.map { it.externalItemId })
        assertEquals("10.90", items.first().price.toPlainString())
        assertEquals("12.00", items.first().originalPrice?.toPlainString())
        assertEquals("https://produto.test/MLB1", items.first().permalink)
        assertEquals(2, requested.count { it.startsWith("/products/search") })
        assertEquals(1, requested.count { it == "/items/MLB2/sale_price" }, "anúncio repetido só consulta preço uma vez")
        assertTrue(requested.all { !it.contains("/sites/MLB/search") })
    }

    @Test fun `produto sem anuncios e sale price ausente nao abortam sincronizacao`() = runBlocking {
        val http = client { request ->
            val path = request.url.encodedPath
            when (path) {
                "/products/search" -> respond(
                    """{"paging":{"total":2,"offset":0,"limit":20},"results":[{"id":"MLB-SEM-ITENS","name":"Sem itens"},{"id":"MLB-COM-ITEM","name":"Com item"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "/products/MLB-SEM-ITENS/items" -> respond("não encontrado", HttpStatusCode.NotFound)
                "/products/MLB-COM-ITEM/items" -> respond(
                    itemPage("MLB99"), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "/items/MLB99" -> respond(
                    "não encontrado", HttpStatusCode.NotFound
                )
                "/items/MLB99/sale_price" -> respond("proibido", HttpStatusCode.Forbidden)
                else -> error("URL inesperada: ${request.url}")
            }
        }

        val items = MercadoLivreClient(http, "https://api.test", tokenProvider()).search("teste")
        assertEquals(1, items.size)
        assertEquals("MLB99", items.single().externalItemId)
        assertEquals("99.99", items.single().price.toPlainString(), "usa preço retornado em products items")
        assertEquals("https://produto.mercadolivre.com.br/MLB-99-_JM", items.single().permalink)
    }

    @Test fun `indisponibilidade externa vira erro de dominio apos retentativas`() = runBlocking {
        var calls = 0
        val http = client { calls++; respond("falha", HttpStatusCode.ServiceUnavailable) }
        assertFailsWith<ExternalApiException> { MercadoLivreClient(http, "https://api.test", tokenProvider()).search("x") }
        assertEquals(3, calls)
    }

    @Test fun `highlights combina item direto e produto de catalogo e ignora user product`() = runBlocking {
        val http = client { request ->
            val json = when (request.url.encodedPath) {
                "/highlights/MLB/category/MLB1055" -> """{"content":[{"id":"MLB1","position":1,"type":"ITEM"},{"id":"MLB-P1","position":2,"type":"PRODUCT"},{"id":"MLBU1","position":3,"type":"USER_PRODUCT"}]}"""
                "/items/MLB1" -> itemDetail("MLB1")
                "/items/MLB1/sale_price" -> salePrice("80.00", "100.00")
                "/products/MLB-P1/items" -> itemPage("MLB2")
                "/items/MLB2" -> itemDetail("MLB2")
                "/items/MLB2/sale_price" -> salePrice("90.00", null)
                "/user-products/MLBU1" -> """{"id":"MLBU1","user_id":999}"""
                "/users/999/items/search" -> """{"results":["MLB3"]}"""
                "/items/MLB3" -> itemDetail("MLB3")
                "/items/MLB3/sale_price" -> salePrice("70.00", "75.00")
                else -> error("URL inesperada: ${request.url}")
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val items = MercadoLivreClient(http, "https://api.test", tokenProvider()).highlights("MLB1055")
        assertEquals(listOf("MLB1", "MLB2", "MLB3"), items.map { it.externalItemId })
        assertEquals("100.00", items.first().originalPrice?.toPlainString())
    }

    private fun catalogPage(total: Int, offset: Int, id: String) =
        """{"paging":{"total":$total,"offset":$offset,"limit":1},"results":[{"id":"$id","name":"Notebook $id","permalink":"https://produto/$id","pictures":[{"url":"https://img/$id.jpg"}]}]}"""

    private fun itemPage(vararg ids: String): String {
        val results = ids.joinToString(",") {
            """{"item_id":"$it","seller_id":123,"currency_id":"BRL","price":99.99,"available_quantity":5,"condition":"new","status":"active"}"""
        }
        return """{"paging":{"total":${ids.size},"offset":0,"limit":100},"results":[$results]}"""
    }

    private fun salePrice(amount: String, regular: String?): String =
        """{"amount":$amount,"regular_amount":${regular ?: "null"},"currency_id":"BRL"}"""

    private fun itemDetail(id: String): String =
        """{"id":"$id","title":"Anúncio $id","permalink":"https://produto.test/$id","thumbnail":"https://img.test/$id.jpg","seller_id":123,"currency_id":"BRL","price":99.99,"available_quantity":3,"condition":"new","status":"active","catalog_product_id":"CAT-$id"}"""

    private fun tokenProvider() = AccessTokenProvider { "token" }
}
