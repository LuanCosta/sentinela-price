package br.com.pricetracker.application.plugins

import br.com.pricetracker.application.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database

fun connectDatabase(config: AppConfig): Database {
    val hikari = HikariConfig().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
        minimumIdle = 2
        connectionTimeout = 10_000
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        validate()
    }
    return Database.connect(HikariDataSource(hikari))
}

