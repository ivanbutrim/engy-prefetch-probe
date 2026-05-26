import java.sql.DriverManager
import java.util.Properties
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger

fun main() {
    suppressNoisyLoggers()

    val host = System.getenv("ENGY_HOST") ?: "engy-prd.internal.corp.traderepublic.com"
    val account = System.getenv("ENGY_ACCOUNT") ?: "gm68377.eu-central-1"
    val warehouse = System.getenv("ENGY_WAREHOUSE") ?: "SECURITIES_SERVICES__PIPELINES__XL"
    val database = System.getenv("ENGY_DATABASE") ?: "TEAMS_PRD"
    val user = System.getenv("ENGY_USER") ?: System.getenv("SNOWFLAKE_USER") ?: run {
        print("ENGY user: ")
        readlnOrNull() ?: error("No user provided")
    }
    val password = System.getenv("ENGY_PASSWORD") ?: System.getenv("SNOWFLAKE_PASSWORD") ?: run {
        print("ENGY password: ")
        System.console()?.readPassword()?.let { String(it) }
            ?: readlnOrNull()
            ?: error("No password provided")
    }

    val jdbcUrl = "jdbc:snowflake://$host/?account=$account&ssl=true&JDBC_QUERY_RESULT_FORMAT=JSON"

    println()
    println("=".repeat(60))
    println("ENGY JDBC 4.x Prefetch-Threads Probe")
    println("=".repeat(60))
    println("  Host:       $host")
    println("  Account:    $account")
    println("  Warehouse:  $warehouse")
    println("  Database:   $database")
    println("  JDBC URL:   $jdbcUrl")
    println("  Driver:     ${net.snowflake.client.jdbc.SnowflakeDriver::class.java.`package`.implementationVersion ?: "unknown"}")

    val props = Properties().apply {
        setProperty("user", user)
        setProperty("password", password)
        setProperty("db", database)
        setProperty("warehouse", warehouse)
        setProperty("CLIENT_PREFETCH_THREADS", "4")  // same as production EngyConfig.kt
    }

    println("\n[1/4] Connecting to ENGY via JDBC ...")
    val conn = DriverManager.getConnection(jdbcUrl, props)
    println("  Connected.")

    // ---------- Check session parameter value ----------
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

    // ---------- Test 1: small query (no chunks) ----------
    println("\n[3/4] Running tiny query: SELECT 1 ...")
    conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT 1 AS probe").use { rs ->
            rs.next()
            println("  Result: ${rs.getInt(1)}")
            println("  OK — small query succeeded (no chunking needed)")
        }
    }

    // ---------- Test 2: large query (forces chunked download) ----------
    // Enable chunk downloader logging right before the large query
    enableChunkDownloaderLogging()

    val largeQuery = """
        SELECT seq4() AS id, RANDSTR(100, RANDOM()) AS payload
        FROM TABLE(GENERATOR(ROWCOUNT => 100000))
    """.trimIndent()

    println("\n[4/4] Running large query (100k rows) to trigger chunking ...")
    println()

    try {
        conn.createStatement().use { stmt ->
            stmt.executeQuery(largeQuery).use { rs ->
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

    conn.close()

    println()
    println("=".repeat(60))
    println("Done.")
}

fun suppressNoisyLoggers() {
    // suppress cloud-metadata probes and HTTP retry noise (runs on every local connection)
    for (prefix in listOf(
        "net.snowflake.client.internal.jdbc.RestRequest",
        "net.snowflake.client.jdbc.RestRequest",
        "net.snowflake.client.jdbc.internal.apache",
        "net.snowflake.client.internal.jdbc.internal.apache",
    )) {
        Logger.getLogger(prefix).level = Level.OFF
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
