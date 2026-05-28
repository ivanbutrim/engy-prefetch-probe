# engy-prefetch-probe

Diagnostic tool to check whether the [ENGY](https://github.com/traderepublic/data_engy) proxy returns a valid `CLIENT_PREFETCH_THREADS` value in Snowflake query result metadata.

Two probes are included:
- **Python probe** — inspects raw HTTP response parameters via `snowflake-connector-python`
- **JDBC probe** — uses the actual Snowflake JDBC 4.x driver (same code path as production)

## Background

Snowflake JDBC 4.x drivers read `CLIENT_PREFETCH_THREADS` from the query result `data.parameters` to size the thread pool that downloads result chunks. When ENGY forwards Snowflake's raw value of `0`, the driver calls `Executors.newFixedThreadPool(0)` which throws:

```
java.lang.IllegalArgumentException: maximumPoolSize must be positive
  at java.util.concurrent.ThreadPoolExecutor.<init>(...)
  at net.snowflake.client.internal.jdbc.SnowflakeChunkDownloader
      .createChunkDownloaderExecutorService(SnowflakeChunkDownloader.java:203)
```

Small result sets (inline, no chunks) are unaffected. Only queries returning enough rows to trigger chunked download hit this bug.

See the full write-up: [Confluence](https://traderepublic.atlassian.net/wiki/spaces/~712020a2d817aaa220474fa1d74a0340578de1/pages/5375623278).

## Key findings

- **The Python probe shows `CLIENT_PREFETCH_THREADS = 4` (fix working), but production JDBC still crashes.** ENGY's response envelopes differ depending on the statement and client type — use the JDBC probe to reproduce the actual failure path.
- **The crash is ordering-dependent.** It only manifests when a chunked query is the **first statement after `ENGY SET`**. Any intervening query — even `SELECT 1` or `SHOW PARAMETERS` — replaces the bad `CLIENT_PREFETCH_THREADS = 0` in the JDBC driver's parameter cache, and the subsequent large query succeeds. This narrows the bug to the response envelope of `ENGY SET` specifically. `SHOW PARAMETERS` confirms the real session-side value is whatever the client passed in (e.g. `2`); the `0` only ever lives in the driver's in-memory cache.

---

## Python probe

Inspects raw HTTP response parameters. Useful for seeing what ENGY returns, but connects as a Python client (different from JDBC).

### Setup

```bash
git clone https://github.com/ivanbutrim/engy-prefetch-probe.git
cd engy-prefetch-probe/python-probe

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Usage

```bash
export ENGY_USER=your_username
read -s ENGY_PASSWORD && export ENGY_PASSWORD   # prompts silently, nothing in shell history
python engy_prefetch_probe.py

# Test against beta
python engy_prefetch_probe.py --host engy-beta.internal.corp.traderepublic.com

# Verbose (debug-level logging from snowflake-connector)
python engy_prefetch_probe.py -v
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--host` | `engy-prd.internal.corp.traderepublic.com` | ENGY endpoint |
| `--account` | `gm68377.eu-central-1` | Snowflake account identifier |
| `--warehouse` | `SECURITIES_SERVICES__PIPELINES__XL` | Warehouse to use |
| `--database` | `TEAMS_PRD` | Database to connect to |
| `--role` | _(none)_ | Optional Snowflake role |
| `--user` | `$ENGY_USER` or `$SNOWFLAKE_USER` | Username |
| `--password` | `$ENGY_PASSWORD` or `$SNOWFLAKE_PASSWORD` | Password |
| `-v` | off | Enable debug logging |

---

## JDBC probe (Kotlin)

Uses the actual Snowflake JDBC 4.2.0 driver — the same driver and code path as production.

The probe runs three steps:

1. Connects to ENGY with `CLIENT_PREFETCH_THREADS=2` in JDBC properties.
2. Runs `ENGY SET database = ..., warehouse = ...`.
3. Runs a 100k-row synthetic query that forces chunked download → crashes with `IllegalArgumentException: maximumPoolSize must be positive`.

Setting `PROBE_WARMUP=true` inserts a `SELECT 1` between steps 2 and 3. This demonstrates the workaround: any intervening query heals the JDBC driver's parameter cache, so the large query then succeeds.

### Prerequisites

- JDK 21+

### Setup & run

```bash
cd jdbc-probe

export ENGY_USER=your_username
read -s ENGY_PASSWORD && export ENGY_PASSWORD   # prompts silently, nothing in shell history

# Reproduce the crash:
./gradlew run

# Verify the heal (workaround) — large query succeeds:
PROBE_WARMUP=true ./gradlew run

# Override host for beta:
ENGY_HOST=engy-beta.internal.corp.traderepublic.com ./gradlew run
```

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ENGY_HOST` | `engy-prd.internal.corp.traderepublic.com` | ENGY endpoint |
| `ENGY_ACCOUNT` | `gm68377.eu-central-1` | Snowflake account |
| `ENGY_WAREHOUSE` | `SECURITIES_SERVICES__PIPELINES__XL` | Warehouse |
| `ENGY_DATABASE` | `TEAMS_PRD` | Database |
| `ENGY_USER` | _(prompt)_ | Username |
| `ENGY_PASSWORD` | _(prompt)_ | Password |
| `CLIENT_PREFETCH_THREADS` | `2` | Value passed in JDBC connection properties |
| `PROBE_WARMUP` | `false` | If `true`, insert `SELECT 1` between `ENGY SET` and the large query (heals the driver cache) |

### Example output (bug reproduced)

```
============================================================
ENGY JDBC 4.x Prefetch-Threads Probe
============================================================
  Host:       engy-prd.internal.corp.traderepublic.com
  Driver:     4.2.0
  Prefetch:   2
  Warm-up:    no

[1/3] Connecting to ENGY via JDBC (DriverManager) ...
  CLIENT_PREFETCH_THREADS in props: 2
  Connected.

[2/3] Running ENGY SET ...
  SQL: ENGY SET database = TEAMS_PRD, warehouse = SECURITIES_SERVICES__PIPELINES__XL, engine = snowflake, spark_size = XXLARGE
  OK

[3/3] Running large query (100000 rows — forces chunked download) ...
  FAILED: JDBC driver internal error: exception creating result
  *** BUG CONFIRMED ***
  SnowflakeChunkDownloader received CLIENT_PREFETCH_THREADS=0
  from ENGY, causing Executors.newFixedThreadPool(0) to throw.
```

### Example output (heal applied, `PROBE_WARMUP=true`)

```
...
[2/3] Running ENGY SET ...
  OK

[warm-up] Running SELECT 1 to heal the driver's parameter cache ...
  Result: 1

[3/3] Running large query (100000 rows — forces chunked download) ...
  Rows fetched: 100000
  SUCCESS — large query completed without crash
```

---

## Related

- Fix PR: [data_engy#1034](https://github.com/traderepublic/data_engy/pull/1034)
- Ticket: DFSD-6349
- Affected consumer: [taxes/reporting](https://github.com/traderepublic/taxes) (MiFIR extractor)
- JDBC driver source: [SnowflakeChunkDownloader.java](https://github.com/snowflakedb/snowflake-jdbc/blob/master/src/main/java/net/snowflake/client/internal/jdbc/SnowflakeChunkDownloader.java), [SessionUtil.java](https://github.com/snowflakedb/snowflake-jdbc/blob/master/src/main/java/net/snowflake/client/internal/core/SessionUtil.java)