import java.sql.DriverManager
import java.util.Properties

/**
 * Reproduces the ENGY CLIENT_PREFETCH_THREADS bug using the actual Snowflake JDBC 4.x driver.
 *
 * Unlike the Python probe (which uses snowflake-connector-python), this uses the same
 * driver and code path as production — SnowflakeChunkDownloader, getCommonParams, etc.
 *
 * The DEBUG log from SnowflakeChunkDownloader will print:
 *   #chunks: N #threads: X #slots: Y -> pool: Z
 *
 * If #threads is 0, the bug is confirmed (and the driver will throw
 * IllegalArgumentException: maximumPoolSize must be positive).
 */
fun main() {
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
        // Console may be null in some environments
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
        setProperty("CLIENT_PREFETCH_THREADS", "2")  // same as production EngyConfig.kt
    }

    println("\n[1/3] Connecting to ENGY via JDBC ...")
    val conn = DriverManager.getConnection(jdbcUrl, props)
    println("  Connected.")

    // ---------- Test 1: small query (no chunks) ----------
    println("\n[2/3] Running tiny query: SELECT 1 ...")
    conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT 1 AS probe").use { rs ->
            rs.next()
            println("  Result: ${rs.getInt(1)}")
            println("  OK — small query succeeded (no chunking needed)")
        }
    }

    // ---------- Test 2: large query (forces chunked download) ----------
    val largeQuery = """
        SELECT seq4() AS id, RANDSTR(100, RANDOM()) AS payload
        FROM TABLE(GENERATOR(ROWCOUNT => 100000))
    """.trimIndent()

    println("\n[3/3] Running large query (100k rows) to trigger chunking ...")
    println("  Watch for the SnowflakeChunkDownloader DEBUG log above.")
    println("  If '#threads: 0' appears, the bug is confirmed.")
    println()

    try {
        conn.createStatement().use { stmt ->
            stmt.executeQuery(largeQuery).use { rs ->
                var count = 0
                while (rs.next()) count++
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
    println("Done. Check the SnowflakeChunkDownloader log line above for the")
    println("'#threads' value to see what ENGY returned.")
}