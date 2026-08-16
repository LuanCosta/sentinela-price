package br.com.pricetracker.data.remote

import br.com.pricetracker.data.repository.ExternalApiException
import br.com.pricetracker.domain.model.MarketItem
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal

fun interface MarketClient { suspend fun search(term: String): List<MarketItem> }
fun interface HighlightClient { suspend fun highlights(categoryId: String): List<MarketItem> }
fun interface TrackedItemClient { suspend fun refreshItems(itemIds: List<String>): List<MarketItem> }

/**
 * Usa somente recursos oficiais atuais: catálogo por termo, anúncios associados
 * ao produto e preço de venda atual de cada anúncio.
 */
class MercadoLivreClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val tokenProvider: AccessTokenProvider,
    private val catalogPageSize: Int = 20,
    private val maxCatalogOffset: Int = 1_000,
    private val itemPageSize: Int = 100
) : MarketClient, HighlightClient, TrackedItemClient {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun search(term: String): List<MarketItem> {
        val catalogProducts = fetchAllCatalogProducts(term)
        val uniqueItems = linkedMapOf<String, MarketItem>()
        var itemPages = 0

        for (product in catalogProducts) {
            val result = fetchProductItems(product, uniqueItems.keys)
            itemPages += result.pages
            result.items.forEach { uniqueItems.putIfAbsent(it.externalItemId, it) }
        }

        logger.info(
            "Busca de catálogo concluída term={} products={} itemPages={} uniqueItems={}",
            term, catalogProducts.size, itemPages, uniqueItems.size
        )
        return uniqueItems.values.toList()
    }

    override suspend fun highlights(categoryId: String): List<MarketItem> {
        val ranking = try {
            get<HighlightsResponse>("/highlights/MLB/category/$categoryId")
        } catch (_: MercadoLivreResourceNotFound) {
            logger.info("Categoria sem ranking de mais vendidos categoryId={}", categoryId)
            return emptyList()
        }
        val items = linkedMapOf<String, MarketItem>()
        for (entry in ranking.content.sortedBy(HighlightEntryDto::position)) {
            when (entry.type.uppercase()) {
                "ITEM" -> fetchDirectItem(entry.id)?.let { items.putIfAbsent(it.externalItemId, it) }
                "PRODUCT" -> fetchProductItems(CatalogProductDto(entry.id), items.keys, maxItems = 3, maxPages = 1).items
                    .forEach { items.putIfAbsent(it.externalItemId, it) }
                "USER_PRODUCT" -> fetchUserProductItems(entry.id)
                    .forEach { items.putIfAbsent(it.externalItemId, it) }
                else -> logger.info("Tipo de highlight desconhecido type={} id={}", entry.type, entry.id)
            }
        }
        logger.info("Highlights concluídos categoryId={} ranking={} uniqueItems={}", categoryId, ranking.content.size, items.size)
        return items.values.toList()
    }

    override suspend fun refreshItems(itemIds: List<String>): List<MarketItem> =
        itemIds.distinct().mapNotNull { itemId -> fetchDirectItem(itemId) }

    private data class ProductItemsResult(val items: List<MarketItem>, val pages: Int)

    private suspend fun fetchProductItems(
        product: CatalogProductDto,
        excludedItemIds: Set<String> = emptySet(),
        maxItems: Int = Int.MAX_VALUE,
        maxPages: Int = Int.MAX_VALUE
    ): ProductItemsResult {
        val items = mutableListOf<MarketItem>()
        var pages = 0
        var offset = 0
        do {
            val page = try {
                get<ProductItemsResponse>("/products/${product.id}/items") {
                    parameter("limit", itemPageSize); parameter("offset", offset)
                }
            } catch (_: MercadoLivreResourceNotFound) {
                logger.info("Produto de catálogo sem anúncios acessíveis productId={}", product.id)
                break
            }
            pages++
            val candidates = page.results.sortedBy { it.price.decimalOrNull() ?: BigDecimal.valueOf(Long.MAX_VALUE) }
            for (listing in candidates) {
                if (items.size >= maxItems) break
                if (listing.itemId in excludedItemIds || items.any { it.externalItemId == listing.itemId }) continue
                fetchListing(product, listing)?.let(items::add)
            }
            offset += page.paging.limit.coerceAtLeast(itemPageSize)
        } while (offset < page.paging.total && page.results.isNotEmpty() && pages < maxPages && items.size < maxItems)
        return ProductItemsResult(items.distinctBy(MarketItem::externalItemId), pages)
    }

    private suspend fun fetchUserProductItems(userProductId: String): List<MarketItem> {
        val userProduct = try {
            get<UserProductResponse>("/user-products/$userProductId")
        } catch (_: MercadoLivreResourceNotFound) {
            return emptyList()
        } catch (_: MercadoLivreResourceForbidden) {
            return emptyList()
        }
        val result = try {
            get<UserProductItemsResponse>("/users/${userProduct.userId}/items/search") {
                parameter("user_product_id", userProductId); parameter("limit", 3)
            }
        } catch (_: MercadoLivreResourceNotFound) {
            return emptyList()
        } catch (_: MercadoLivreResourceForbidden) {
            return emptyList()
        }
        return result.results.take(3).mapNotNull { fetchDirectItem(it) }
    }

    private suspend fun fetchListing(product: CatalogProductDto, listing: CatalogListingDto): MarketItem? {
        val detail = fetchItemDetail(listing.itemId)
        val salePrice = fetchSalePrice(listing.itemId)
        val currentPrice = salePrice.amount.decimalOrNull() ?: listing.price.decimalOrNull() ?: detail?.price.decimalOrNull()
        if (currentPrice == null) {
            logger.warn("Anúncio ignorado por preço inválido itemId={}", listing.itemId)
            return null
        }
        return listing.toMarketItem(product, detail, salePrice, currentPrice)
    }

    private suspend fun fetchDirectItem(itemId: String): MarketItem? {
        val detail = fetchItemDetail(itemId) ?: return null
        val sale = fetchSalePrice(itemId)
        val currentPrice = sale.amount.decimalOrNull() ?: detail.price.decimalOrNull() ?: return null
        return detail.toMarketItem(itemId, sale, currentPrice)
    }

    private suspend fun fetchItemDetail(itemId: String): ItemDetailResponse? = try {
        get<ItemDetailResponse>("/items/$itemId")
    } catch (_: MercadoLivreResourceNotFound) {
        logger.info("Detalhe do anúncio ausente itemId={}", itemId); null
    } catch (_: MercadoLivreResourceForbidden) {
        logger.info("Detalhe do anúncio privado itemId={}", itemId); null
    }

    private suspend fun fetchSalePrice(itemId: String): SalePriceResponse = try {
        get("/items/$itemId/sale_price")
    } catch (_: MercadoLivreResourceNotFound) {
        logger.info("Preço de venda específico ausente; usando preço público itemId={}", itemId); SalePriceResponse()
    } catch (_: MercadoLivreResourceForbidden) {
        logger.info("Preço de venda específico privado; usando preço público itemId={}", itemId); SalePriceResponse()
    }

    private suspend fun fetchAllCatalogProducts(term: String): List<CatalogProductDto> {
        val products = mutableListOf<CatalogProductDto>()
        var offset = 0
        do {
            val page = get<CatalogSearchResponse>("/products/search") {
                parameter("status", "active")
                parameter("site_id", "MLB")
                parameter("q", term)
                parameter("limit", catalogPageSize)
                parameter("offset", offset)
            }
            products += page.results.filter { it.id.isNotBlank() }
            offset += page.paging.limit.coerceAtLeast(catalogPageSize)
            val allowedTotal = minOf(page.paging.total, maxCatalogOffset)
        } while (offset < allowedTotal && page.results.isNotEmpty())
        return products.distinctBy(CatalogProductDto::id)
    }

    private suspend inline fun <reified T> get(
        path: String,
        noinline configure: HttpRequestBuilder.() -> Unit = {}
    ): T {
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            try {
                return http.get("$baseUrl$path") {
                    bearerAuth(tokenProvider.accessToken())
                    timeout {
                        requestTimeoutMillis = 20_000
                        connectTimeoutMillis = 5_000
                        socketTimeoutMillis = 20_000
                    }
                    configure()
                }.body()
            } catch (error: ClientRequestException) {
                if (error.response.status == HttpStatusCode.NotFound) throw MercadoLivreResourceNotFound(path)
                else if (error.response.status == HttpStatusCode.Forbidden) throw MercadoLivreResourceForbidden(path)
                else if (error.response.status == HttpStatusCode.TooManyRequests) lastFailure = error
                else throw ExternalApiException("Mercado Livre rejeitou $path (${error.response.status.value})", error)
            } catch (error: ServerResponseException) {
                lastFailure = error
            } catch (error: HttpRequestTimeoutException) {
                lastFailure = error
            } catch (error: IOException) {
                lastFailure = error
            }
            delay(250L * (1 shl attempt))
        }
        throw ExternalApiException("API do Mercado Livre indisponível em $path após novas tentativas", lastFailure)
    }
}

@Serializable
data class CatalogSearchResponse(
    val paging: PagingDto = PagingDto(),
    val results: List<CatalogProductDto> = emptyList()
)

@Serializable data class HighlightsResponse(val content: List<HighlightEntryDto> = emptyList())
@Serializable data class HighlightEntryDto(val id: String, val position: Int = Int.MAX_VALUE, val type: String)
@Serializable data class UserProductResponse(@SerialName("user_id") val userId: Long)
@Serializable data class UserProductItemsResponse(val results: List<String> = emptyList())

@Serializable
data class CatalogProductDto(
    val id: String,
    val name: String? = null,
    val permalink: String? = null,
    val pictures: List<PictureDto> = emptyList()
)

@Serializable data class PictureDto(val url: String? = null)

@Serializable
data class ProductItemsResponse(
    val paging: PagingDto = PagingDto(),
    val results: List<CatalogListingDto> = emptyList()
)

@Serializable
data class CatalogListingDto(
    @SerialName("item_id") val itemId: String,
    @SerialName("seller_id") val sellerId: Long? = null,
    @SerialName("currency_id") val currencyId: String? = null,
    val price: JsonElement? = null,
    @SerialName("available_quantity") val availableQuantity: Int? = null,
    val condition: String? = null,
    val status: String? = null
) {
    fun toMarketItem(
        product: CatalogProductDto,
        detail: ItemDetailResponse?,
        sale: SalePriceResponse,
        currentPrice: BigDecimal
    ) = MarketItem(
        externalItemId = itemId,
        catalogProductId = detail?.catalogProductId ?: product.id,
        title = detail?.title ?: product.name ?: "Produto ${product.id}",
        permalink = detail?.permalink ?: product.permalink ?: canonicalItemUrl(itemId),
        thumbnailUrl = detail?.thumbnail ?: product.pictures.firstOrNull()?.url,
        sellerId = detail?.sellerId ?: sellerId,
        currency = sale.currencyId ?: detail?.currencyId ?: currencyId ?: "BRL",
        price = currentPrice,
        originalPrice = sale.regularAmount.decimalOrNull() ?: detail?.originalPrice.decimalOrNull(),
        availableQuantity = detail?.availableQuantity ?: availableQuantity,
        condition = detail?.condition ?: condition,
        status = detail?.status ?: status ?: "active"
    )
}

@Serializable
data class ItemDetailResponse(
    val id: String? = null,
    val title: String? = null,
    val permalink: String? = null,
    val thumbnail: String? = null,
    @SerialName("seller_id") val sellerId: Long? = null,
    @SerialName("currency_id") val currencyId: String? = null,
    val price: JsonElement? = null,
    @SerialName("original_price") val originalPrice: JsonElement? = null,
    @SerialName("available_quantity") val availableQuantity: Int? = null,
    val condition: String? = null,
    val status: String? = null,
    @SerialName("catalog_product_id") val catalogProductId: String? = null
) {
    fun toMarketItem(itemId: String, sale: SalePriceResponse, currentPrice: BigDecimal) = MarketItem(
        externalItemId = id ?: itemId,
        catalogProductId = catalogProductId,
        title = title ?: "Anúncio $itemId",
        permalink = permalink ?: canonicalItemUrl(itemId),
        thumbnailUrl = thumbnail,
        sellerId = sellerId,
        currency = sale.currencyId ?: currencyId ?: "BRL",
        price = currentPrice,
        originalPrice = sale.regularAmount.decimalOrNull() ?: originalPrice.decimalOrNull(),
        availableQuantity = availableQuantity,
        condition = condition,
        status = status ?: "active"
    )
}

@Serializable
data class SalePriceResponse(
    val amount: JsonElement? = null,
    @SerialName("regular_amount") val regularAmount: JsonElement? = null,
    @SerialName("currency_id") val currencyId: String? = null
)

@Serializable
data class PagingDto(val total: Int = 0, val offset: Int = 0, val limit: Int = 50)

private fun JsonElement?.decimalOrNull(): BigDecimal? =
    this?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()

private fun canonicalItemUrl(itemId: String): String {
    val number = itemId.removePrefix("MLB")
    return "https://produto.mercadolivre.com.br/MLB-$number-_JM"
}

private class MercadoLivreResourceNotFound(path: String) : RuntimeException("Recurso não encontrado: $path")
private class MercadoLivreResourceForbidden(path: String) : RuntimeException("Recurso não permitido: $path")
