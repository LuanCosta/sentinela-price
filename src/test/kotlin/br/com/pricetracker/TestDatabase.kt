package br.com.pricetracker

import br.com.pricetracker.data.database.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun testDatabase(): Database {
    val db = Database.connect("jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    transaction(db) {
        SchemaUtils.create(
            ProductTable, SyncExecutionTable, PriceHistoryTable, PromotionTable,
            MercadoLivreCredentialTable, HighlightRotationTable, AffiliateLinkTable
        )
    }
    return db
}
