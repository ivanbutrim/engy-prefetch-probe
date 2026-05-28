import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

fun main() {
    val config = EngyConfig.fromEnvironment()
    EngyJdbcProbe(config).run()
}

data class EngyConfig(
    val host: String,
    val account: String,
    val warehouse: String,
    val database: String,
    val user: String,
    val password: String,
    val clientPrefetchThreads: Int = 2,
    val warmup: Boolean = false,
) {
    val jdbcUrl: String
        get() = "jdbc:snowflake://$host/?account=$account&ssl=true&JDBC_QUERY_RESULT_FORMAT=JSON"

    companion object {
        fun fromEnvironment(): EngyConfig {
            val user = env("ENGY_USER")
                ?: env("SNOWFLAKE_USER")
                ?: promptInput("ENGY user: ")

            val password = env("ENGY_PASSWORD")
                ?: env("SNOWFLAKE_PASSWORD")
                ?: promptPassword("ENGY password: ")

            return EngyConfig(
                host = env("ENGY_HOST") ?: "engy-prd.internal.corp.traderepublic.com",
                account = env("ENGY_ACCOUNT") ?: "gm68377.eu-central-1",
                warehouse = env("ENGY_WAREHOUSE") ?: "SECURITIES_SERVICES__PIPELINES__XL",
                database = env("ENGY_DATABASE") ?: "TEAMS_PRD",
                user = user,
                password = password,
                clientPrefetchThreads = env("CLIENT_PREFETCH_THREADS")?.toInt() ?: 2,
                // PROBE_WARMUP=true inserts a SELECT 1 between ENGY SET and the large query.
                // With warm-up: large query succeeds (driver cache healed).
                // Without warm-up: large query crashes with "maximumPoolSize must be positive".
                warmup = env("PROBE_WARMUP")?.equals("true", ignoreCase = true) == true,
            )
        }

        // Real process environment wins; otherwise fall back to a gitignored .env file.
        // Lets the IntelliJ run button supply secrets without a plugin or env-var setup.
        private fun env(name: String): String? = System.getenv(name) ?: dotenv[name]

        private val dotenv: Map<String, String> by lazy { loadDotenv() }

        private fun loadDotenv(): Map<String, String> {
            // Walk up from the working directory (IntelliJ's working dir varies) and
            // also check a jdbc-probe/ subdir, taking the first .env found.
            val file = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .flatMap { sequenceOf(java.io.File(it, ".env"), java.io.File(it, "jdbc-probe/.env")) }
                .firstOrNull { it.isFile }
                ?: return emptyMap()

            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .associate { line ->
                    val (key, value) = line.split("=", limit = 2)
                    key.trim() to value.trim()
                }
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
        suppressNoisyLoggers()
        printHeader()

        createDirectConnection().use { conn ->
            runEngySet(conn)
            if (config.warmup) {
                runWarmupQuery(conn)
            }
            runLargeQuery(conn)
        }

        println()
        println("=".repeat(SEPARATOR_WIDTH))
        println("Done.")
    }

    private fun createDirectConnection(): Connection {
        println("\n[1/3] Connecting to ENGY via JDBC (DriverManager) ...")

        val props = Properties().apply {
            setProperty("user", config.user)
            setProperty("password", config.password)
            setProperty("CLIENT_PREFETCH_THREADS", config.clientPrefetchThreads.toString())
        }
        println("  CLIENT_PREFETCH_THREADS in props: ${config.clientPrefetchThreads}")

        val conn = DriverManager.getConnection(config.jdbcUrl, props)
        println("  Connected.")
        return conn
    }

    private fun runEngySet(conn: Connection) {
        val setClause = "ENGY SET database = ${config.database}, warehouse = ${config.warehouse}, engine = snowflake, spark_size = XXLARGE"
        println("\n[2/3] Running ENGY SET ...")
        println("  SQL: $setClause")

        conn.createStatement().use { stmt ->
            stmt.execute(setClause)
        }
        println("  OK")
    }

    private fun runWarmupQuery(conn: Connection) {
        println("\n[warm-up] Running SELECT 1 to heal the driver's parameter cache ...")
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1 AS probe").use { rs ->
                rs.next()
                println("  Result: ${rs.getInt(1)}")
            }
        }
    }

    private fun runLargeQuery(conn: Connection) {
        println("\n[3/3] Running large query ($LARGE_QUERY_ROW_COUNT rows — forces chunked download) ...")

        try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(LARGE_QUERY).use { rs ->
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
        println("  Prefetch:   ${config.clientPrefetchThreads}")
        println("  Warm-up:    ${if (config.warmup) "yes (inserts SELECT 1 between ENGY SET and large query)" else "no"}")
    }

    companion object {
        private const val SEPARATOR_WIDTH = 60
        private const val LARGE_QUERY_ROW_COUNT = 100_000

        private val LARGE_QUERY = """
            SELECT seq4() AS id, RANDSTR(100, RANDOM()) AS payload
            FROM TABLE(GENERATOR(ROWCOUNT => $LARGE_QUERY_ROW_COUNT))
        """.trimIndent()

        private val NOISY_LOGGERS = listOf(
            "net.snowflake.client.internal.jdbc.RestRequest",
            "net.snowflake.client.jdbc.RestRequest",
            "net.snowflake.client.jdbc.internal.apache",
            "net.snowflake.client.internal.jdbc.internal.apache",
        )

        private fun suppressNoisyLoggers() {
            for (name in NOISY_LOGGERS) {
                Logger.getLogger(name).level = Level.OFF
            }
        }
    }
}
