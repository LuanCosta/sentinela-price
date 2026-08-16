package br.com.pricetracker.domain.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant

data class MarketItem(
    val externalItemId: String,
    val catalogProductId: String?,
    val title: String,
    val permalink: String?,
    val thumbnailUrl: String?,
    val sellerId: Long?,
    val currency: String,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val availableQuantity: Int?,
    val condition: String?,
    val status: String?
)

data class ProductRecord(val id: Long, val created: Boolean)

@Serializable
data class ProductResponse(
    val id: Long,
    val externalItemId: String,
    val catalogProductId: String? = null,
    val title: String,
    val permalink: String? = null,
    val thumbnailUrl: String? = null,
    val currency: String,
    val currentPrice: String,
    val originalPrice: String? = null,
    val lowestHistoricalPrice: String,
    val availableQuantity: Int? = null,
    val condition: String? = null,
    val status: String? = null,
    val lastSeenAt: String
)

@Serializable
data class PriceHistoryResponse(
    val id: Long,
    val productId: Long,
    val price: String,
    val originalPrice: String? = null,
    val observedAt: String,
    val syncExecutionId: Long
)

@Serializable
data class PromotionResponse(
    val id: Long,
    val productId: Long,
    val externalItemId: String,
    val catalogProductId: String? = null,
    val title: String,
    val permalink: String? = null,
    val thumbnailUrl: String? = null,
    val currency: String,
    val previousLowestPrice: String,
    val promotionalPrice: String,
    val discountAmount: String,
    val discountPercent: String,
    val detectedAt: String,
    val active: Boolean,
    val offersCount: Int = 1,
    val affiliateUrl: String? = null
)

@Serializable data class AffiliateLinkEntryRequest(val originalUrl: String, val affiliateUrl: String)
@Serializable data class AffiliateLinksImportRequest(val entries: List<AffiliateLinkEntryRequest>)
@Serializable data class AffiliateLinksImportResponse(val imported: Int)

@Serializable
data class SyncRequest(val searchTerms: List<String>? = null)

@Serializable
data class SyncExecutionResponse(
    val executionId: Long,
    val status: String,
    val searchTerm: String,
    val itemsReceived: Int,
    val productsCreated: Int,
    val productsUpdated: Int,
    val historyCreated: Int,
    val promotionsCreated: Int,
    val startedAt: String,
    val finishedAt: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class PageResponse<T>(val items: List<T>, val page: Int, val limit: Int, val total: Long)

@Serializable data class HealthResponse(val status: String)
@Serializable data class ErrorResponse(val code: String, val error: String)

enum class SyncStatus { RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED }

data class SyncCounters(
    var itemsReceived: Int = 0,
    var productsCreated: Int = 0,
    var productsUpdated: Int = 0,
    var historyCreated: Int = 0,
    var promotionsCreated: Int = 0
)

fun BigDecimal.money(): BigDecimal = setScale(2, java.math.RoundingMode.HALF_UP)
fun Instant.text(): String = toString()
