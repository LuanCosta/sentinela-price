package br.com.pricetracker

import br.com.pricetracker.data.repository.ProductRepository
import br.com.pricetracker.data.repository.SyncRepository
import br.com.pricetracker.domain.model.MarketItem
import br.com.pricetracker.domain.model.SyncCounters
import br.com.pricetracker.domain.model.SyncStatus
import br.com.pricetracker.domain.model.AffiliateLinkEntryRequest
import br.com.pricetracker.domain.model.AffiliateLinksImportRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.math.BigDecimal
import java.time.Instant

class ProductRepositoryTest {
    private fun item(
        id: String = "MLB1",
        title: String = "Notebook",
        price: String,
        catalogProductId: String? = null,
        originalPrice: String? = null
    ) = MarketItem(
        id, catalogProductId, title, "https://item/$id", null, 10, "BRL", BigDecimal(price),
        originalPrice?.let(::BigDecimal), 1, "new", "active"
    )

    @Test fun `regras de minima historica e historico por execucao`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        suspend fun observe(price: String): ProductRepository.ProcessResult {
            val execution = sync.start(listOf("notebook"))
            val result = products.process(item(price = price), execution)
            sync.finish(execution, SyncStatus.SUCCESS, SyncCounters(historyCreated = 1))
            return result
        }

        assertFalse(observe("100.00").promotionCreated, "primeira observação")
        assertTrue(observe("90.00").promotionCreated, "novo mínimo")
        assertFalse(observe("95.00").promotionCreated, "preço maior")
        assertFalse(observe("92.00").promotionCreated, "caiu contra o último, não contra a mínima")
        assertTrue(observe("85.00").promotionCreated, "segunda mínima histórica")

        val productPage = products.list(1, 20, null)
        assertEquals(1, productPage.total, "produto atualizado sem duplicação")
        assertEquals("85.00", productPage.items.single().currentPrice)
        assertEquals("85.00", productPage.items.single().lowestHistoricalPrice)
        assertEquals(5, products.history(1, 1, 50).total, "histórico em toda execução")
        assertEquals(2, products.promotions(1, 20, null).total)
        assertEquals(1, products.promotions(1, 20, true).total)
        assertEquals("85.00", products.promotions(1, 20, true).items.single().promotionalPrice)
    }

    @Test fun `ids externos diferentes não são mesclados mesmo com título igual`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        val execution = sync.start(listOf("notebook"))
        products.process(item("MLB1", "Mesmo título", "10.10"), execution)
        products.process(item("MLB2", "Mesmo título", "10.10"), execution)
        sync.finish(execution, SyncStatus.SUCCESS, SyncCounters())
        assertEquals(2, products.list(1, 20, "Mesmo título").total)
    }

    @Test fun `calculo monetario usa decimal exato`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        suspend fun observe(price: String) {
            val execution = sync.start(listOf("x")); products.process(item(price = price), execution)
            sync.finish(execution, SyncStatus.SUCCESS, SyncCounters())
        }
        observe("0.30"); observe("0.10")
        val promotion = products.promotions(1, 10, null).items.single()
        assertEquals("0.20", promotion.discountAmount)
        assertEquals("66.67", promotion.discountPercent)
    }

    @Test fun `promocoes ativas do mesmo catalogo retornam apenas a oferta mais barata`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        suspend fun observe(id: String, price: String) {
            val execution = sync.start(listOf("perfume"))
            products.process(item(id, "Mesmo perfume", price, "MLB-CATALOGO-1"), execution)
            sync.finish(execution, SyncStatus.SUCCESS, SyncCounters())
        }

        observe("MLB1", "150.00"); observe("MLB1", "130.00")
        observe("MLB2", "160.00"); observe("MLB2", "120.00")

        val page = products.promotions(1, 20, true)
        assertEquals(1, page.total)
        assertEquals("MLB2", page.items.single().externalItemId)
        assertEquals("120.00", page.items.single().promotionalPrice)
        assertEquals("MLB-CATALOGO-1", page.items.single().catalogProductId)
        assertEquals(2, page.items.single().offersCount)
    }

    @Test fun `promocao deixa de estar ativa quando preco sobe`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        suspend fun observe(price: String) {
            val execution = sync.start(listOf("x")); products.process(item(price = price), execution)
            sync.finish(execution, SyncStatus.SUCCESS, SyncCounters())
        }

        observe("100.00"); observe("80.00")
        assertEquals(1, products.promotions(1, 20, true).total)
        observe("90.00")
        assertEquals(0, products.promotions(1, 20, true).total)
        assertEquals(1, products.promotions(1, 20, false).total)
    }

    @Test fun `desconto oficial cria promocao desde a primeira observacao sem duplicar`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        suspend fun observe(): ProductRepository.ProcessResult {
            val execution = sync.start(listOf("highlights"))
            val result = products.process(item(price = "80.00", originalPrice = "100.00"), execution)
            sync.finish(execution, SyncStatus.SUCCESS, SyncCounters())
            return result
        }

        assertTrue(observe().promotionCreated)
        assertFalse(observe().promotionCreated)
        val promotion = products.promotions(1, 20, true).items.single()
        assertEquals("100.00", promotion.previousLowestPrice)
        assertEquals("20.00", promotion.discountAmount)
    }

    @Test fun `monitoramento prioriza promocao ativa e depois produto mais antigo`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        suspend fun save(item: MarketItem, time: String) {
            val execution = sync.start(listOf("seed"))
            products.process(item, execution, Instant.parse(time))
            sync.finish(execution, SyncStatus.SUCCESS, SyncCounters())
        }

        save(item("MLB-ANTIGO", price = "50.00"), "2026-01-01T00:00:00Z")
        save(item("MLB-PROMO", price = "80.00", originalPrice = "100.00"), "2026-01-03T00:00:00Z")
        save(item("MLB-NOVO", price = "60.00"), "2026-01-04T00:00:00Z")

        assertEquals(listOf("MLB-PROMO", "MLB-ANTIGO"), products.monitoringItemIds(2))
    }

    @Test fun `importa link oficial e enriquece promocao`() = runBlocking {
        val db = testDatabase(); val products = ProductRepository(db); val sync = SyncRepository(db)
        val execution = sync.start(listOf("seed"))
        products.process(item(price = "80.00", originalPrice = "100.00"), execution)
        sync.finish(execution, SyncStatus.SUCCESS, SyncCounters())

        val response = products.importAffiliateLinks(
            AffiliateLinksImportRequest(listOf(AffiliateLinkEntryRequest("https://item/MLB1", "https://meli.la/abc123")))
        )
        assertEquals(1, response.imported)
        assertEquals("https://meli.la/abc123", products.promotions(1, 20, true).items.single().affiliateUrl)
        assertFailsWith<br.com.pricetracker.data.repository.InvalidRequestException> {
            products.importAffiliateLinks(
                AffiliateLinksImportRequest(listOf(AffiliateLinkEntryRequest("https://item/MLB1", "https://malicioso.test/x")))
            )
        }
    }
}
