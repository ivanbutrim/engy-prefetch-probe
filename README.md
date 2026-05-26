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

## Key finding

The Python probe shows `CLIENT_PREFETCH_THREADS = 4` (fix working), but production JDBC still shows `#threads: 0`. ENGY returns different responses depending on client type. **Use the JDBC probe to reproduce the actual failure path.**

---

## Python probe

Inspects raw HTTP response parameters. Useful for seeing what ENGY returns, but connects as a Python client (different from JDBC).

### Setup

```bash
git clone https://github.com/ivanbutrim/engy-prefetch-probe.git
cd engy-prefetch-probe

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Usage

```bash
export ENGY_USER=your_username
export ENGY_PASSWORD=your_password
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

Uses the actual Snowflake JDBC 4.2.0 driver — the same driver and code path as production. Enables `SnowflakeChunkDownloader` DEBUG logging to show:

```
#chunks: N #threads: X #slots: Y -> pool: Z
```

If `#threads: 0`, the bug is confirmed and the query will crash with `IllegalArgumentException`.

### Prerequisites

- JDK 17+
- Gradle (or use the wrapper if included)

### Setup & run

```bash
cd jdbc-probe

export ENGY_USER=your_username
export ENGY_PASSWORD=your_password
gradle run

# Or override host for beta:
ENGY_HOST=engy-beta.internal.corp.traderepublic.com gradle run
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

### Example output (bug present)

```
============================================================
ENGY JDBC 4.x Prefetch-Threads Probe
============================================================
  Host:       engy-prd.internal.corp.traderepublic.com
  Driver:     4.2.0

[1/3] Connecting to ENGY via JDBC ...
  Connected.

[2/3] Running tiny query: SELECT 1 ...
  OK — small query succeeded (no chunking needed)

[3/3] Running large query (100k rows) to trigger chunking ...

18:51:52.193 [main] DEBUG n.s.c.i.j.SnowflakeChunkDownloader - #chunks: 2 #threads: 0 #slots: 0 -> pool: 0

  FAILED: JDBC driver internal error: exception creating result
  *** BUG CONFIRMED ***
  SnowflakeChunkDownloader received CLIENT_PREFETCH_THREADS=0
  from ENGY, causing Executors.newFixedThreadPool(0) to throw.
```

---

## Related

- Fix PR: [data_engy#1034](https://github.com/traderepublic/data_engy/pull/1034)
- Ticket: DFSD-6349
- Affected consumer: [taxes/reporting](https://github.com/traderepublic/taxes) (MiFIR extractor)
- JDBC driver source: [SnowflakeChunkDownloader.java](https://github.com/snowflakedb/snowflake-jdbc/blob/master/src/main/java/net/snowflake/client/internal/jdbc/SnowflakeChunkDownloader.java), [SessionUtil.java](https://github.com/snowflakedb/snowflake-jdbc/blob/master/src/main/java/net/snowflake/client/internal/core/SessionUtil.java)