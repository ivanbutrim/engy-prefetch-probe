import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger

fun main() {
    val config = EngyConfig.fromEnvironment()
    val probe = EngyJdbcProbe(config)
    probe.run()
}

data class EngyConfig(
    val host: String,
    val account: String,
    val warehouse: String,
    val database: String,
    val user: String,
    val password: String,
    val clientPrefetchThreads: Int = 4,
) {
    val jdbcUrl: String
        get() = "jdbc:snowflake://$host/?account=$account&ssl=true&JDBC_QUERY_RESULT_FORMAT=JSON"

    companion object {
        fun fromEnvironment(): EngyConfig {
            val user = System.getenv("ENGY_USER")
                ?: System.getenv("SNOWFLAKE_USER")
                ?: promptInput("ENGY user: ")

            val password = System.getenv("ENGY_PASSWORD")
                ?: System.getenv("SNOWFLAKE_PASSWORD")
                ?: promptPassword("ENGY password: ")

            return EngyConfig(
                host = System.getenv("ENGY_HOST") ?: "engy-prd.internal.corp.traderepublic.com",
                account = System.getenv("ENGY_ACCOUNT") ?: "gm68377.eu-central-1",
                warehouse = System.getenv("ENGY_WAREHOUSE") ?: "SECURITIES_SERVICES__PIPELINES__XL",
                database = System.getenv("ENGY_DATABASE") ?: "TEAMS_PRD",
                user = user,
                password = password,
            )
        }

        private fun promptInput(prompt: String): String {
            print(prompt)
            return readlnOrNull() ?: error("No input provided")
        }

        private fun promptPassword(prompt: String): String {
            print(prompt)
            return System.console()?.readPassword()?.let { String(it) }
                ?: readlnOrNull()
                ?: error("No password provided")
        }
    }
}

class EngyJdbcProbe(private val config: EngyConfig) {

    fun run() {
        SnowflakeLogging.suppressNoisyLoggers()
        printHeader()

        createConnection().use { conn ->
            checkSessionParameter(conn)
            runSmallQuery(conn)

            SnowflakeLogging.enableChunkDownloaderLogging()
            runLargeQuery(conn)
        }

        println()
        println("=".repeat(SEPARATOR_WIDTH))
        println("Done.")
    }

    private fun createConnection(): Connection {
        println("\n[1/4] Connecting to ENGY via JDBC ...")

        val props = Properties().apply {
            setProperty("user", config.user)
            setProperty("password", config.password)
            setProperty("db", config.database)
            setProperty("warehouse", config.warehouse)
            setProperty("CLIENT_PREFETCH_THREADS", config.clientPrefetchThreads.toString())
        }

        val conn = DriverManager.getConnection(config.jdbcUrl, props)
        println("  Connected.")
        return conn
    }

    private fun checkSessionParameter(conn: Connection) {
        println("\n[2/4] Checking session parameter CLIENT_PREFETCH_THREADS ...")

        conn.createStatement().use { stmt ->
            stmt.executeQuery("SHOW PARAMETERS LIKE 'CLIENT_PREFETCH_THREADS'").use { rs ->
                if (rs.next()) {
                    val key = rs.getString("key")
                    val value = rs.getString("value")
                    val level = rs.getString("level")
                    println("  $key = $value (level: $level)")
                } else {
                    println("  Parameter not found in SHOW PARAMETERS")
                }
            }
        }
    }

    private fun runSmallQuery(conn: Connection) {
        println("\n[3/4] Running tiny query: SELECT 1 ...")

        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1 AS probe").use { rs ->
                rs.next()
                println("  Result: ${rs.getInt(1)}")
                println("  OK — small query succeeded (no chunking needed)")
            }
        }
    }

    private fun runLargeQuery(conn: Connection) {
        println("\n[4/4] Running large query ($LARGE_QUERY_ROW_COUNT rows) to trigger chunking ...")
        println()

        try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(largeQuery()).use { rs ->
                    var count = 0
                    while (rs.next()) count++
                    println()
                    println("  Rows fetched: $count")
                    println("  SUCCESS — large query completed without crash")
                }
            }
        } catch (e: Exception) {
            println("  FAILED: ${e.message}")
            if (e.message?.contains("maximumPoolSize") == true) {
                println()
                println("  *** BUG CONFIRMED ***")
                println("  SnowflakeChunkDownloader received CLIENT_PREFETCH_THREADS=0")
                println("  from ENGY, causing Executors.newFixedThreadPool(0) to throw.")
            }
            e.printStackTrace(System.out)
        }
    }

    private fun printHeader() {
        val driverVersion = net.snowflake.client.jdbc.SnowflakeDriver::class.java
            .`package`.implementationVersion ?: "unknown"

        println()
        println("=".repeat(SEPARATOR_WIDTH))
        println("ENGY JDBC 4.x Prefetch-Threads Probe")
        println("=".repeat(SEPARATOR_WIDTH))
        println("  Host:       ${config.host}")
        println("  Account:    ${config.account}")
        println("  Warehouse:  ${config.warehouse}")
        println("  Database:   ${config.database}")
        println("  JDBC URL:   ${config.jdbcUrl}")
        println("  Driver:     $driverVersion")
    }

    companion object {
        private const val SEPARATOR_WIDTH = 60
        private const val LARGE_QUERY_ROW_COUNT = 100_000

        private fun largeQuery(): String = """
            SELECT seq4() AS id, RANDSTR(100, RANDOM()) AS payload
            FROM TABLE(GENERATOR(ROWCOUNT => $LARGE_QUERY_ROW_COUNT))
        """.trimIndent()
    }
}

object SnowflakeLogging {

    private val NOISY_LOGGERS = listOf(
        "net.snowflake.client.internal.jdbc.RestRequest",
        "net.snowflake.client.jdbc.RestRequest",
        "net.snowflake.client.jdbc.internal.apache",
        "net.snowflake.client.internal.jdbc.internal.apache",
    )

    fun suppressNoisyLoggers() {
        for (name in NOISY_LOGGERS) {
            Logger.getLogger(name).level = Level.OFF
        }
    }

    fun enableChunkDownloaderLogging() {
        val root = Logger.getLogger("")
        root.level = Level.FINE
        for (handler in root.handlers) {
            if (handler is ConsoleHandler) {
                handler.level = Level.FINE
            }
        }
        // cast a wide net — the package structure varies across driver versions
        Logger.getLogger("net.snowflake").level = Level.FINE
    }
}
