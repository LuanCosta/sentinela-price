package br.com.pricetracker.application.plugins

import br.com.pricetracker.data.repository.*
import br.com.pricetracker.domain.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.SerializationException

class UnauthorizedException : RuntimeException("Segredo administrativo ausente ou incorreto")

fun Application.configureStatusPages() {
    val logger = environment.log
    install(StatusPages) {
        exception<InvalidRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST", e.message!!)) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_JSON", "Corpo JSON inválido")) }
        exception<UnauthorizedException> { call, e -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", e.message!!)) }
        exception<ResourceNotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message!!)) }
        exception<SyncAlreadyRunningException> { call, e -> call.respond(HttpStatusCode.Conflict, ErrorResponse("SYNC_ALREADY_RUNNING", e.message!!)) }
        exception<ExternalApiException> { call, e -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("MERCADO_LIVRE_UNAVAILABLE", e.message!!)) }
        exception<Throwable> { call, e ->
            logger.error("Erro interno não tratado", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "Erro interno inesperado"))
        }
    }
}
