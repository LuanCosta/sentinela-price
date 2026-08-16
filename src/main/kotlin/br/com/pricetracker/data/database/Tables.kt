package br.com.pricetracker.data.database

import br.com.pricetracker.domain.model.SyncStatus
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.Table

object ProductTable : LongIdTable("products") {
    val externalItemId = varchar("external_item_id", 64).uniqueIndex()
    val catalogProductId = varchar("catalog_product_id", 64).nullable()
    val title = varchar("title", 512)
    val permalink = text("permalink").nullable()
    val thumbnailUrl = text("thumbnail_url").nullable()
    val sellerId = long("seller_id").nullable()
    val currency = varchar("currency", 8)
    val currentPrice = decimal("current_price", 19, 2)
    val originalPrice = decimal("original_price", 19, 2).nullable()
    val availableQuantity = integer("available_quantity").nullable()
    val condition = varchar("condition", 32).nullable()
    val status = varchar("status", 32).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val lastSeenAt = timestamp("last_seen_at")
}

object SyncExecutionTable : LongIdTable("sync_executions") {
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
    val status = enumerationByName<SyncStatus>("status", 32)
    val searchTerm = text("search_term")
    val itemsReceived = integer("items_received").default(0)
    val productsCreated = integer("products_created").default(0)
    val productsUpdated = integer("products_updated").default(0)
    val historyCreated = integer("history_created").default(0)
    val promotionsCreated = integer("promotions_created").default(0)
    val errorMessage = text("error_message").nullable()
}

object PriceHistoryTable : LongIdTable("price_history") {
    val productId = reference("product_id", ProductTable)
    val price = decimal("price", 19, 2)
    val originalPrice = decimal("original_price", 19, 2).nullable()
    val observedAt = timestamp("observed_at").index()
    val syncExecutionId = reference("sync_execution_id", SyncExecutionTable)
    init { index(false, productId, observedAt); uniqueIndex(productId, syncExecutionId) }
}

object PromotionTable : LongIdTable("promotions") {
    val productId = reference("product_id", ProductTable)
    val previousLowestPrice = decimal("previous_lowest_price", 19, 2)
    val promotionalPrice = decimal("promotional_price", 19, 2)
    val discountAmount = decimal("discount_amount", 19, 2)
    val discountPercent = decimal("discount_percent", 7, 2)
    val detectedAt = timestamp("detected_at").index()
    val syncExecutionId = reference("sync_execution_id", SyncExecutionTable)
    val active = bool("active").default(true)
    init { uniqueIndex(productId, promotionalPrice, syncExecutionId) }
}

/** Uma única autorização OAuth para a conta proprietária deste MVP. */
object MercadoLivreCredentialTable : Table("mercado_livre_credentials") {
    val id = integer("id")
    val accessToken = text("access_token")
    val refreshToken = text("refresh_token")
    val expiresAt = timestamp("expires_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Cursor durável da rotação de categorias de mais vendidos. */
object HighlightRotationTable : Table("highlight_rotation") {
    val id = integer("id")
    val nextIndex = integer("next_index")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AffiliateLinkTable : LongIdTable("affiliate_links") {
    val productId = reference("product_id", ProductTable).uniqueIndex()
    val originalUrl = text("original_url")
    val affiliateUrl = text("affiliate_url")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
