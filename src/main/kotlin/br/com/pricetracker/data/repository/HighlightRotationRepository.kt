package br.com.pricetracker.data.repository

import br.com.pricetracker.data.database.HighlightRotationTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class HighlightRotationRepository(private val database: Database) {
    suspend fun nextBatch(categories: List<String>, batchSize: Int): List<String> =
        newSuspendedTransaction(db = database) {
            if (categories.isEmpty()) return@newSuspendedTransaction emptyList()
            val row = HighlightRotationTable.selectAll()
                .where { HighlightRotationTable.id eq SINGLETON_ID }.forUpdate().singleOrNull()
            val start = (row?.get(HighlightRotationTable.nextIndex) ?: 0).mod(categories.size)
            val size = minOf(batchSize.coerceAtLeast(1), categories.size)
            val selected = (0 until size).map { categories[(start + it) % categories.size] }
            val next = (start + size) % categories.size
            if (row == null) {
                HighlightRotationTable.insert {
                    it[id] = SINGLETON_ID; it[nextIndex] = next; it[updatedAt] = Instant.now()
                }
            } else {
                HighlightRotationTable.update({ HighlightRotationTable.id eq SINGLETON_ID }) {
                    it[nextIndex] = next; it[updatedAt] = Instant.now()
                }
            }
            selected
        }

    private companion object { const val SINGLETON_ID = 1 }
}
