package br.com.pricetracker.data.repository

import br.com.pricetracker.data.database.MercadoLivreCredentialTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

data class StoredMercadoLivreToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant
)

class MercadoLivreTokenRepository(private val database: Database) {
    suspend fun load(): StoredMercadoLivreToken? = db {
        MercadoLivreCredentialTable.selectAll()
            .where { MercadoLivreCredentialTable.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let {
                StoredMercadoLivreToken(
                    accessToken = it[MercadoLivreCredentialTable.accessToken],
                    refreshToken = it[MercadoLivreCredentialTable.refreshToken],
                    expiresAt = it[MercadoLivreCredentialTable.expiresAt]
                )
            }
    }

    suspend fun save(accessToken: String, refreshToken: String, expiresAt: Instant) = db {
        val exists = MercadoLivreCredentialTable.selectAll()
            .where { MercadoLivreCredentialTable.id eq SINGLETON_ID }.any()
        if (exists) {
            MercadoLivreCredentialTable.update({ MercadoLivreCredentialTable.id eq SINGLETON_ID }) {
                it[MercadoLivreCredentialTable.accessToken] = accessToken
                it[MercadoLivreCredentialTable.refreshToken] = refreshToken
                it[MercadoLivreCredentialTable.expiresAt] = expiresAt
                it[updatedAt] = Instant.now()
            }
        } else {
            MercadoLivreCredentialTable.insert {
                it[MercadoLivreCredentialTable.id] = SINGLETON_ID
                it[MercadoLivreCredentialTable.accessToken] = accessToken
                it[MercadoLivreCredentialTable.refreshToken] = refreshToken
                it[MercadoLivreCredentialTable.expiresAt] = expiresAt
                it[updatedAt] = Instant.now()
            }
        }
    }

    private suspend fun <T> db(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(db = database, statement = block)

    private companion object { const val SINGLETON_ID = 1 }
}
