package br.com.pricetracker.data.remote

import br.com.pricetracker.data.repository.ExternalApiException
import br.com.pricetracker.data.repository.MercadoLivreTokenRepository
import br.com.pricetracker.data.repository.StoredMercadoLivreToken
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant

fun interface AccessTokenProvider { suspend fun accessToken(): String }

class MercadoLivreTokenService(
    private val http: HttpClient,
    private val repository: MercadoLivreTokenRepository,
    private val clientId: String,
    private val clientSecret: String,
    private val bootstrapRefreshToken: String
) : AccessTokenProvider {
    private val mutex = Mutex()
    private val logger = LoggerFactory.getLogger(javaClass)
    @Volatile private var cachedToken: StoredMercadoLivreToken? = null

    override suspend fun accessToken(): String {
        val memoryToken = cachedToken
        if (memoryToken != null && isValid(memoryToken)) return memoryToken.accessToken

        val current = repository.load().also { cachedToken = it }
        if (current != null && isValid(current)) {
            return current.accessToken
        }
        return mutex.withLock {
            val rechecked = cachedToken?.takeIf(::isValid) ?: repository.load().also { cachedToken = it }
            if (rechecked != null && isValid(rechecked)) {
                return@withLock rechecked.accessToken
            }
            refresh(rechecked?.refreshToken ?: bootstrapRefreshToken)
        }
    }

    private fun isValid(token: StoredMercadoLivreToken): Boolean =
        token.expiresAt.isAfter(Instant.now().plusSeconds(RENEWAL_MARGIN_SECONDS))

    private suspend fun refresh(refreshToken: String): String {
        try {
            val response = http.submitForm(
                url = "https://api.mercadolibre.com/oauth/token",
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("refresh_token", refreshToken)
                }
            ).body<TokenResponse>()
            val expiresAt = Instant.now().plusSeconds(response.expiresIn.toLong())
            repository.save(response.accessToken, response.refreshToken, expiresAt)
            cachedToken = StoredMercadoLivreToken(response.accessToken, response.refreshToken, expiresAt)
            logger.info("Token do Mercado Livre renovado; validade={}s", response.expiresIn)
            return response.accessToken
        } catch (error: ClientRequestException) {
            throw ExternalApiException(
                "Não foi possível renovar a autorização do Mercado Livre (${error.response.status.value}); refaça o OAuth se o refresh token expirou",
                error
            )
        } catch (error: Exception) {
            if (error is ExternalApiException) throw error
            throw ExternalApiException("Falha ao renovar a autorização do Mercado Livre", error)
        }
    }

    private companion object { const val RENEWAL_MARGIN_SECONDS = 300L }
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int
)
