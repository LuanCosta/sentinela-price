package br.com.pricetracker.data.repository

import br.com.pricetracker.data.database.*
import br.com.pricetracker.domain.model.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncAlreadyRunningException : RuntimeException("Já existe uma sincronização em andamento")
class ResourceNotFoundException(message: String) : RuntimeException(message)
class InvalidRequestException(message: String) : RuntimeException(message)
class ExternalApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class SyncRepository(private val database: Database) {
    suspend fun start(searchTerms: List<String>): Long = startMutex.withLock {
        db {
            if (SyncExecutionTable.selectAll().where { SyncExecutionTable.status eq SyncStatus.RUNNING }.limit(1).any()) {
                throw SyncAlreadyRunningException()
            }
            try {
                SyncExecutionTable.insertAndGetId {
                    it[startedAt] = Instant.now()
                    it[status] = SyncStatus.RUNNING
                    it[searchTerm] = searchTerms.joinToString(",")
                }.value
            } catch (_: ExposedSQLException) {
                throw SyncAlreadyRunningException()
            }
        }
    }

    suspend fun finish(id: Long, statusValue: SyncStatus, counters: SyncCounters, error: String? = null) = db {
        SyncExecutionTable.update({ SyncExecutionTable.id eq id }) {
            it[finishedAt] = Instant.now()
            it[status] = statusValue
            it[itemsReceived] = counters.itemsReceived
            it[productsCreated] = counters.productsCreated
            it[productsUpdated] = counters.productsUpdated
            it[historyCreated] = counters.historyCreated
            it[promotionsCreated] = counters.promotionsCreated
            it[errorMessage] = error?.take(2_000)
        }
    }

    suspend fun get(id: Long): SyncExecutionResponse = db {
        SyncExecutionTable.selectAll().where { SyncExecutionTable.id eq id }.singleOrNull()?.toSyncResponse()
            ?: throw ResourceNotFoundException("Execução não encontrada")
    }

    suspend fun list(page: Int, limit: Int): PageResponse<SyncExecutionResponse> = db {
        val query = SyncExecutionTable.selectAll()
        val total = query.count()
        val rows = query.orderBy(SyncExecutionTable.startedAt, SortOrder.DESC)
            .limit(limit).offset(((page - 1) * limit).toLong()).map { it.toSyncResponse() }
        PageResponse(rows, page, limit, total)
    }

    private suspend fun <T> db(block: suspend Transaction.() -> T): T = newSuspendedTransaction(db = database, statement = block)

    private companion object { val startMutex = Mutex() }
}

class ProductRepository(private val database: Database) {
    data class ProcessResult(val created: Boolean, val promotionCreated: Boolean)

    suspend fun process(item: MarketItem, executionId: Long, observedAt: Instant = Instant.now()): ProcessResult = db {
        val existing = ProductTable.selectAll().where { ProductTable.externalItemId eq item.externalItemId }
            .forUpdate().singleOrNull()
        val productId = existing?.get(ProductTable.id)?.value
        val minimumPrice = PriceHistoryTable.price.min()
        val previousLowest: BigDecimal? = productId?.let { id ->
            PriceHistoryTable.select(minimumPrice)
                .where { PriceHistoryTable.productId eq id }
                .single()[minimumPrice]
        }

        val id = if (existing == null) {
            ProductTable.insertAndGetId { row -> row.write(item, observedAt, true) }.value
        } else {
            ProductTable.update({ ProductTable.id eq productId!! }) { row -> row.write(item, observedAt, false) }
            productId!!
        }

        PriceHistoryTable.insertIgnore {
            it[PriceHistoryTable.productId] = id
            it[price] = item.price.money()
            it[originalPrice] = item.originalPrice?.money()
            it[PriceHistoryTable.observedAt] = observedAt
            it[syncExecutionId] = executionId
        }

        val currentPrice = item.price.money()
        val alreadyActiveAtCurrentPrice = PromotionTable.selectAll().where {
            (PromotionTable.productId eq id) and
                (PromotionTable.active eq true) and
                (PromotionTable.promotionalPrice eq currentPrice)
        }.any()

        // Uma oferta deixa de estar ativa assim que o anúncio muda daquele preço promocional.
        PromotionTable.update({
            (PromotionTable.productId eq id) and
                (PromotionTable.active eq true) and
                (PromotionTable.promotionalPrice neq currentPrice)
        }) {
            it[active] = false
        }

        val officialReference = item.originalPrice?.money()?.takeIf { currentPrice < it }
        val historicalReference = previousLowest?.takeIf { currentPrice < it }
        val referencePrice = officialReference ?: historicalReference
        var promotionCreated = false
        if (referencePrice != null && !alreadyActiveAtCurrentPrice) {
            val amount = (referencePrice - currentPrice).money()
            val percent = amount.multiply(BigDecimal("100")).divide(referencePrice, 2, RoundingMode.HALF_UP)
            promotionCreated = PromotionTable.insertIgnore {
                it[PromotionTable.productId] = id
                it[previousLowestPrice] = referencePrice
                it[promotionalPrice] = currentPrice
                it[discountAmount] = amount
                it[discountPercent] = percent
                it[detectedAt] = observedAt
                it[syncExecutionId] = executionId
                it[active] = true
            }.insertedCount > 0
        }
        ProcessResult(existing == null, promotionCreated)
    }

    suspend fun list(page: Int, limit: Int, search: String?): PageResponse<ProductResponse> = db {
        val condition = search?.takeIf(String::isNotBlank)?.let { ProductTable.title.lowerCase() like "%${it.lowercase()}%" }
            ?: Op.TRUE
        val query = ProductTable.selectAll().where { condition }
        val total = query.count()
        val products = query.orderBy(ProductTable.updatedAt, SortOrder.DESC).limit(limit)
            .offset(((page - 1) * limit).toLong()).map { row ->
                val id = row[ProductTable.id].value
                val minimumPrice = PriceHistoryTable.price.min()
                val low = PriceHistoryTable.select(minimumPrice)
                    .where { PriceHistoryTable.productId eq id }.single()[minimumPrice]!!
                row.toProductResponse(low)
            }
        PageResponse(products, page, limit, total)
    }

    suspend fun history(productId: Long, page: Int, limit: Int): PageResponse<PriceHistoryResponse> = db {
        if (!ProductTable.selectAll().where { ProductTable.id eq productId }.any()) throw ResourceNotFoundException("Produto não encontrado")
        val query = PriceHistoryTable.selectAll().where { PriceHistoryTable.productId eq productId }
        val total = query.count()
        val rows = query.orderBy(PriceHistoryTable.observedAt, SortOrder.DESC).limit(limit)
            .offset(((page - 1) * limit).toLong()).map { it.toHistoryResponse() }
        PageResponse(rows, page, limit, total)
    }

    suspend fun promotions(page: Int, limit: Int, activeOnly: Boolean?): PageResponse<PromotionResponse> = db {
        val condition = activeOnly?.let { PromotionTable.active eq it } ?: Op.TRUE
        val base = promotionJoin().selectAll().where { condition }
        if (activeOnly == true) {
            val grouped = base.map { it.toPromotionResponse() }.bestOffers()
            val offset = (page - 1) * limit
            PageResponse(grouped.drop(offset).take(limit), page, limit, grouped.size.toLong())
        } else {
            val total = base.count()
            val rows = base.orderBy(PromotionTable.detectedAt, SortOrder.DESC).limit(limit)
                .offset(((page - 1) * limit).toLong()).map { it.toPromotionResponse() }
            PageResponse(rows, page, limit, total)
        }
    }

    suspend fun promotion(id: Long): PromotionResponse = db {
        promotionJoin().selectAll().where { PromotionTable.id eq id }
            .singleOrNull()?.toPromotionResponse() ?: throw ResourceNotFoundException("Promoção não encontrada")
    }

    suspend fun activePromotionUrls(): List<String> = db {
        promotionJoin().selectAll()
            .where { PromotionTable.active eq true }
            .map { it.toPromotionResponse() }
            .bestOffers()
            .mapNotNull(PromotionResponse::permalink)
            .filter(String::isNotBlank)
            .distinct()
    }

    suspend fun importAffiliateLinks(request: AffiliateLinksImportRequest): AffiliateLinksImportResponse = db {
        if (request.entries.isEmpty()) throw InvalidRequestException("Informe ao menos um link de afiliado")
        val entries = request.entries.map { entry ->
            val original = entry.originalUrl.trim()
            val affiliate = entry.affiliateUrl.trim()
            if (original.isBlank()) throw InvalidRequestException("originalUrl não pode ser vazio")
            if (!affiliate.isValidMeliAffiliateUrl()) {
                throw InvalidRequestException("affiliateUrl deve ser uma URL https://meli.la válida")
            }
            original to affiliate
        }
        if (entries.map { it.first }.distinct().size != entries.size) {
            throw InvalidRequestException("originalUrl não pode aparecer mais de uma vez")
        }

        val now = Instant.now()
        entries.forEach { (original, affiliate) ->
            val productId = ProductTable.select(ProductTable.id)
                .where { ProductTable.permalink eq original }
                .singleOrNull()?.get(ProductTable.id)?.value
                ?: throw InvalidRequestException("Produto não encontrado para originalUrl: $original")
            val exists = AffiliateLinkTable.selectAll().where { AffiliateLinkTable.productId eq productId }.any()
            if (exists) {
                AffiliateLinkTable.update({ AffiliateLinkTable.productId eq productId }) {
                    it[originalUrl] = original; it[affiliateUrl] = affiliate; it[updatedAt] = now
                }
            } else {
                AffiliateLinkTable.insert {
                    it[AffiliateLinkTable.productId] = productId
                    it[originalUrl] = original; it[affiliateUrl] = affiliate
                    it[createdAt] = now; it[updatedAt] = now
                }
            }
        }
        AffiliateLinksImportResponse(entries.size)
    }

    suspend fun importOrderedAffiliateLinks(affiliateUrls: List<String>): AffiliateLinksImportResponse {
        val originals = activePromotionUrls()
        val affiliates = affiliateUrls.map(String::trim).filter(String::isNotBlank)
        if (originals.isEmpty()) throw InvalidRequestException("Não há promoções ativas para associar")
        if (originals.size != affiliates.size) {
            throw InvalidRequestException(
                "Quantidade diferente: ${originals.size} URLs originais e ${affiliates.size} links de afiliado"
            )
        }
        return importAffiliateLinks(
            AffiliateLinksImportRequest(
                originals.indices.map { index -> AffiliateLinkEntryRequest(originals[index], affiliates[index]) }
            )
        )
    }

    /** Promoções ativas primeiro; completa o lote com os anúncios há mais tempo sem consulta. */
    suspend fun monitoringItemIds(limit: Int): List<String> = db {
        val safeLimit = limit.coerceIn(1, 500)
        val priority = PromotionTable.innerJoin(ProductTable)
            .select(ProductTable.externalItemId, ProductTable.lastSeenAt)
            .where { PromotionTable.active eq true }
            .orderBy(ProductTable.lastSeenAt, SortOrder.ASC)
            .limit(safeLimit)
            .map { it[ProductTable.externalItemId] }
            .distinct()
            .take(safeLimit)
        if (priority.size == safeLimit) return@db priority

        val selected = priority.toHashSet()
        val oldest = ProductTable.select(ProductTable.externalItemId, ProductTable.lastSeenAt)
            .orderBy(ProductTable.lastSeenAt, SortOrder.ASC)
            .limit((safeLimit + selected.size).coerceAtMost(1_000))
            .map { it[ProductTable.externalItemId] }
            .filterNot(selected::contains)
            .take(safeLimit - priority.size)
        priority + oldest
    }

    private suspend fun <T> db(block: suspend Transaction.() -> T): T = newSuspendedTransaction(db = database, statement = block)
}

private fun UpdateBuilder<*>.write(item: MarketItem, now: Instant, inserting: Boolean) {
    this[ProductTable.catalogProductId] = item.catalogProductId
    this[ProductTable.title] = item.title.take(512)
    this[ProductTable.permalink] = item.permalink
    this[ProductTable.thumbnailUrl] = item.thumbnailUrl
    this[ProductTable.sellerId] = item.sellerId
    this[ProductTable.currency] = item.currency
    this[ProductTable.currentPrice] = item.price.money()
    this[ProductTable.originalPrice] = item.originalPrice?.money()
    this[ProductTable.availableQuantity] = item.availableQuantity
    this[ProductTable.condition] = item.condition
    this[ProductTable.status] = item.status
    this[ProductTable.updatedAt] = now
    this[ProductTable.lastSeenAt] = now
    if (inserting) {
        this[ProductTable.externalItemId] = item.externalItemId
        this[ProductTable.createdAt] = now
    }
}

private fun ResultRow.toProductResponse(low: BigDecimal) = ProductResponse(
    this[ProductTable.id].value, this[ProductTable.externalItemId], this[ProductTable.catalogProductId],
    this[ProductTable.title], this[ProductTable.permalink], this[ProductTable.thumbnailUrl], this[ProductTable.currency],
    this[ProductTable.currentPrice].toPlainString(), this[ProductTable.originalPrice]?.toPlainString(), low.toPlainString(),
    this[ProductTable.availableQuantity], this[ProductTable.condition], this[ProductTable.status], this[ProductTable.lastSeenAt].text()
)

private fun ResultRow.toHistoryResponse() = PriceHistoryResponse(
    this[PriceHistoryTable.id].value, this[PriceHistoryTable.productId].value, this[PriceHistoryTable.price].toPlainString(),
    this[PriceHistoryTable.originalPrice]?.toPlainString(), this[PriceHistoryTable.observedAt].text(), this[PriceHistoryTable.syncExecutionId].value
)

private fun ResultRow.toPromotionResponse() = PromotionResponse(
    this[PromotionTable.id].value, this[ProductTable.id].value, this[ProductTable.externalItemId],
    this[ProductTable.catalogProductId], this[ProductTable.title],
    this[ProductTable.permalink], this[ProductTable.thumbnailUrl], this[ProductTable.currency],
    this[PromotionTable.previousLowestPrice].toPlainString(), this[PromotionTable.promotionalPrice].toPlainString(),
    this[PromotionTable.discountAmount].toPlainString(), this[PromotionTable.discountPercent].toPlainString(),
    this[PromotionTable.detectedAt].text(), this[PromotionTable.active], affiliateUrl = getOrNull(AffiliateLinkTable.affiliateUrl)
)

private fun promotionJoin(): Join = PromotionTable.innerJoin(ProductTable).leftJoin(
    AffiliateLinkTable,
    { ProductTable.id },
    { AffiliateLinkTable.productId }
)

private fun String.isValidMeliAffiliateUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("meli.la", ignoreCase = true) &&
        !uri.path.isNullOrBlank() && uri.path != "/"
}.getOrDefault(false)

private fun List<PromotionResponse>.bestOffers(): List<PromotionResponse> =
    groupBy { it.catalogProductId?.takeIf(String::isNotBlank) ?: "item:${it.externalItemId}" }
        .values
        .map { offers ->
            offers.minWith(
                compareBy<PromotionResponse> { it.promotionalPrice.toBigDecimal() }
                    .thenByDescending { it.discountPercent.toBigDecimal() }
                    .thenByDescending { it.detectedAt }
            ).copy(offersCount = offers.size)
        }
        .sortedByDescending(PromotionResponse::detectedAt)

private fun ResultRow.toSyncResponse() = SyncExecutionResponse(
    this[SyncExecutionTable.id].value, this[SyncExecutionTable.status].name, this[SyncExecutionTable.searchTerm],
    this[SyncExecutionTable.itemsReceived], this[SyncExecutionTable.productsCreated], this[SyncExecutionTable.productsUpdated],
    this[SyncExecutionTable.historyCreated], this[SyncExecutionTable.promotionsCreated], this[SyncExecutionTable.startedAt].text(),
    this[SyncExecutionTable.finishedAt]?.text(), this[SyncExecutionTable.errorMessage]
)
