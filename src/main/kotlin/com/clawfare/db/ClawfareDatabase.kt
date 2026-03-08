package com.clawfare.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

/**
 * Database connection and schema management for Clawfare.
 */
object ClawfareDatabase {
    private var initialized = false
    private var currentDb: Database? = null

    /**
     * Default database path: ~/.clawfare/data.db
     */
    val defaultPath: String by lazy {
        val home = System.getProperty("user.home")
        "$home/.clawfare/data.db"
    }

    /**
     * Connect to a SQLite database at the given path.
     * Creates parent directories if needed.
     *
     * @param path Path to SQLite file, or ":memory:" for in-memory database
     * @param createSchema Whether to create tables if they don't exist
     */
    fun connect(
        path: String? = defaultPath,
        createSchema: Boolean = true,
    ): Database {
        val jdbcUrl =
            when {
                path == null || path == ":memory:" -> "jdbc:sqlite::memory:"
                else -> {
                    // Regular file path - ensure parent directory exists
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    "jdbc:sqlite:$path"
                }
            }

        val db =
            Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC",
            )

        // Set transaction isolation level for SQLite
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        currentDb = db

        if (createSchema) {
            createTables()
        }

        initialized = true
        return db
    }

    /**
     * Connect to an in-memory SQLite database.
     * Uses a temp file to ensure tables persist across connections.
     * Useful for testing.
     */
    fun connectInMemory(createSchema: Boolean = true): Database {
        // Use a temp file for consistent connection behavior in tests
        val tempFile = File.createTempFile("clawfare-test-", ".db")
        tempFile.deleteOnExit()

        val db =
            Database.connect(
                url = "jdbc:sqlite:${tempFile.absolutePath}",
                driver = "org.sqlite.JDBC",
            )

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        currentDb = db

        if (createSchema) {
            createTables()
        }

        initialized = true
        return db
    }

    /**
     * Create all tables if they don't exist.
     */
    fun createTables() {
        transaction {
            SchemaUtils.create(Investigations, Flights, PriceHistory)
        }
    }

    /**
     * Drop all tables. Use with caution!
     */
    fun dropTables() {
        transaction {
            SchemaUtils.drop(PriceHistory, Flights, Investigations)
        }
    }

    /**
     * Check if the database has been initialized.
     */
    fun isInitialized(): Boolean = initialized
}
