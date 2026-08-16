package br.com.pricetracker

import br.com.pricetracker.data.remote.MercadoLivreTokenService
import br.com.pricetracker.data.repository.ExternalApiException
import br.com.pricetracker.data.repository.MercadoLivreTokenRepository
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.*

class MercadoLivreTokenServiceTest {
    @Test fun `renova uma vez salva tokens rotativos e reutiliza access token valido`() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            assertEquals("/oauth/token", request.url.encodedPath)
            respond(
                """{"access_token":"access-novo","refresh_token":"refresh-novo","expires_in":21600}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repository = MercadoLivreTokenRepository(testDatabase())
        val service = MercadoLivreTokenService(http, repository, "client", "secret", "refresh-inicial")

        assertEquals("access-novo", service.accessToken())
        assertEquals("access-novo", service.accessToken())
        assertEquals(1, calls)
        val stored = assertNotNull(repository.load())
        assertEquals("refresh-novo", stored.refreshToken)
        assertTrue(stored.expiresAt.isAfter(Instant.now().plusSeconds(21_000)))
    }

    @Test fun `token expirado usa refresh salvo e substitui o estado`() = runBlocking {
        val repository = MercadoLivreTokenRepository(testDatabase())
        repository.save("access-velho", "refresh-salvo", Instant.now().minusSeconds(1))
        val http = HttpClient(MockEngine {
            respond(
                """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":21600}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val service = MercadoLivreTokenService(http, repository, "client", "secret", "refresh-inicial")
        assertEquals("access-2", service.accessToken())
        assertEquals("refresh-2", repository.load()?.refreshToken)
    }

    @Test fun `refresh rejeitado vira erro externo sem expor credenciais`() = runBlocking {
        val http = HttpClient(MockEngine { respond("invalid_grant", HttpStatusCode.BadRequest) }) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = MercadoLivreTokenService(
            http, MercadoLivreTokenRepository(testDatabase()), "client", "secret", "refresh-ultrassecreto"
        )

        val error = assertFailsWith<ExternalApiException> { service.accessToken() }
        assertFalse(error.message.orEmpty().contains("refresh-ultrassecreto"))
        assertTrue(error.message.orEmpty().contains("OAuth"))
    }
}
