# engy-prefetch-probe

Diagnostic tool to check whether the [ENGY](https://github.com/traderepublic/data_engy) proxy returns a valid `CLIENT_PREFETCH_THREADS` value in Snowflake query result metadata.

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

## What this tool does

1. Connects to ENGY using `snowflake-connector-python` (same REST protocol as JDBC)
2. Monkey-patches the HTTP layer to capture the raw JSON response
3. Runs a tiny query (`SELECT 1`) -- should always succeed
4. Runs a 100k-row query to force chunked download
5. Dumps all parameters from the response and reports whether `CLIENT_PREFETCH_THREADS` is safe

## Setup

```bash
git clone https://github.com/ivanbutrim/engy-prefetch-probe.git
cd engy-prefetch-probe

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Usage

```bash
# Credentials via env vars
export ENGY_USER=your_username
export ENGY_PASSWORD=your_password
python engy_prefetch_probe.py

# Or be prompted interactively
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

## Example output

```
============================================================
ENGY Prefetch-Threads Probe
============================================================
  Host:      engy-prd.internal.corp.traderepublic.com
  Account:   gm68377.eu-central-1
  Warehouse: SECURITIES_SERVICES__PIPELINES__XL
  Database:  TEAMS_PRD

[1/4] Connecting to ENGY ...
  Connected. Session ID: 01bdb...

[2/4] Running tiny query: SELECT 1 ...
  Rows returned: 1
  CLIENT_PREFETCH_THREADS in response: 0
  Total parameters returned: 7
  *** BUG CONFIRMED: value is 0 (must be > 0) ***

[3/4] Running large query (100k rows) to trigger chunking ...
  Rows fetched: 100000
  CLIENT_PREFETCH_THREADS in response: 0
  Chunks: 12
  Total rows (from metadata): 100000
  *** BUG CONFIRMED: value is 0 — this crashes JDBC 4.x ***

[4/4] All parameters from last query response:
  CLIENT_PREFETCH_THREADS                  = 0
  ...

============================================================
VERDICT: BUG PRESENT — ENGY returns CLIENT_PREFETCH_THREADS <= 0
         The PR #1034 fix is NOT active on this endpoint.
```

## Related

- Fix PR: [data_engy#1034](https://github.com/traderepublic/data_engy/pull/1034)
- Ticket: DFSD-6349
- Affected consumer: [taxes/reporting](https://github.com/traderepublic/taxes) (MiFIR extractor)