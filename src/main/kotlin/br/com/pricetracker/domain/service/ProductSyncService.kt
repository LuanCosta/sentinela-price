package br.com.pricetracker.domain.service

import br.com.pricetracker.data.remote.MarketClient
import br.com.pricetracker.data.remote.HighlightClient
import br.com.pricetracker.data.remote.TrackedItemClient
import br.com.pricetracker.data.repository.*
import br.com.pricetracker.domain.model.*
import org.slf4j.LoggerFactory
import kotlin.time.measureTimedValue

class ProductSyncService(
    private val marketClient: MarketClient,
    private val products: ProductRepository,
    private val executions: SyncRepository,
    private val defaultTerms: List<String>,
    private val highlightClient: HighlightClient? = null,
    private val highlightRotation: HighlightRotationRepository? = null,
    private val highlightCategories: List<String> = emptyList(),
    private val highlightBatchSize: Int = 3,
    private val trackedItemClient: TrackedItemClient? = null,
    private val trackedProductsBatchSize: Int = 50
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun synchronize(requestedTerms: List<String>?): SyncExecutionResponse {
        val manualTerms = requestedTerms?.map(String::trim)?.filter(String::isNotBlank)?.distinct()
        if (requestedTerms != null && manualTerms.isNullOrEmpty()) throw InvalidRequestException("Informe ao menos um termo de busca")
        val automatic = requestedTerms == null && highlightClient != null && highlightRotation != null && highlightCategories.isNotEmpty()
        val executionLabels = if (automatic) listOf("AUTO_HIGHLIGHTS") + defaultTerms else (manualTerms ?: defaultTerms)
        if (executionLabels.isEmpty()) throw InvalidRequestException("Não há termos nem categorias configuradas")
        val executionId = executions.start(executionLabels)
        val categories = if (automatic) highlightRotation!!.nextBatch(highlightCategories, highlightBatchSize) else emptyList()
        val terms = if (automatic) defaultTerms else (manualTerms ?: defaultTerms)
        val counters = SyncCounters()
        var failedSources = 0
        var monitoringAttempted = false
        val errors = mutableListOf<String>()
        val processedItemIds = hashSetOf<String>()
        logger.info("Sincronização iniciada id={} terms={} highlightCategories={}", executionId, terms, categories)

        val timed = measureTimedValue {
            for (term in terms) {
                try {
                    processItems(marketClient.search(term), executionId, counters, processedItemIds)
                } catch (error: Exception) {
                    failedSources++
                    errors += "$term: ${error.message ?: error::class.simpleName}"
                    logger.error("Falha ao sincronizar term={} execution={}", term, executionId, error)
                }
            }
            for (category in categories) {
                try {
                    processItems(highlightClient!!.highlights(category), executionId, counters, processedItemIds)
                } catch (error: Exception) {
                    failedSources++
                    errors += "highlights:$category: ${error.message ?: error::class.simpleName}"
                    logger.error("Falha ao sincronizar highlights category={} execution={}", category, executionId, error)
                }
            }
            if (automatic && trackedItemClient != null) {
                val candidates = products.monitoringItemIds(
                    trackedProductsBatchSize + minOf(processedItemIds.size, trackedProductsBatchSize)
                ).filterNot(processedItemIds::contains).take(trackedProductsBatchSize)
                if (candidates.isNotEmpty()) {
                    monitoringAttempted = true
                    try {
                        processItems(trackedItemClient.refreshItems(candidates), executionId, counters, processedItemIds)
                    } catch (error: Exception) {
                        failedSources++
                        errors += "monitoring: ${error.message ?: error::class.simpleName}"
                        logger.error("Falha ao revisar produtos monitorados execution={}", executionId, error)
                    }
                }
            }
        }
        val sourceCount = terms.size + categories.size + if (monitoringAttempted) 1 else 0
        val status = when (failedSources) {
            0 -> SyncStatus.SUCCESS
            sourceCount -> SyncStatus.FAILED
            else -> SyncStatus.PARTIAL_SUCCESS
        }
        executions.finish(executionId, status, counters, errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
        logger.info("Sincronização finalizada id={} status={} items={} promotions={} duration={}",
            executionId, status, counters.itemsReceived, counters.promotionsCreated, timed.duration)
        if (status == SyncStatus.FAILED) throw ExternalApiException("Não foi possível consultar o Mercado Livre")
        return executions.get(executionId)
    }

    private suspend fun processItems(
        items: List<MarketItem>,
        executionId: Long,
        counters: SyncCounters,
        processedItemIds: MutableSet<String>
    ) {
        val newItems = items.filter { processedItemIds.add(it.externalItemId) }
        counters.itemsReceived += newItems.size
        newItems.forEach { item ->
            val result = products.process(item, executionId)
            if (result.created) counters.productsCreated++ else counters.productsUpdated++
            counters.historyCreated++
            if (result.promotionCreated) counters.promotionsCreated++
        }
    }
}
