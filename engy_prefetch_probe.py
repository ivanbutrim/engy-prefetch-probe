"""
Probe ENGY proxy to check CLIENT_PREFETCH_THREADS in query result parameters.

Reproduces the JDBC 4.x crash (DFSD-6349) from Python by connecting through
ENGY and inspecting what parameters the proxy returns for small and large
result sets.

Usage:
    # Uses ENGY_USER / ENGY_PASSWORD env vars, or prompts interactively
    python engy_prefetch_probe.py

    # Override host (e.g., beta)
    python engy_prefetch_probe.py --host engy-beta.internal.corp.traderepublic.com
"""

import argparse
import json
import logging
import os
import sys

try:
    import snowflake.connector
    from snowflake.connector.network import SnowflakeRestful
except ImportError:
    print("Install snowflake-connector-python:  pip install snowflake-connector-python")
    sys.exit(1)


logging.basicConfig(level=logging.WARNING)
logger = logging.getLogger("engy_probe")


# ---------------------------------------------------------------------------
# Monkey-patch: capture raw JSON response from query execution
# ---------------------------------------------------------------------------
_captured_responses: list[dict] = []
_original_fetch = SnowflakeRestful.fetch


def _patched_fetch(self, method, full_url, headers, data, timeout=None, **kwargs):
    """Wraps SnowflakeRestful.fetch to capture the raw response body."""
    resp = _original_fetch(self, method, full_url, headers, data, timeout=timeout, **kwargs)
    if resp and isinstance(resp, dict) and "data" in resp:
        params = resp.get("data", {}).get("parameters", [])
        _captured_responses.append({
            "url": full_url,
            "parameters": params,
            "chunk_count": resp.get("data", {}).get("chunks", None),
            "total_rows": resp.get("data", {}).get("total", None),
        })
    return resp


def extract_prefetch_threads(params: list[dict]) -> int | None:
    for p in params:
        if p.get("name") == "CLIENT_PREFETCH_THREADS":
            return p.get("value")
    return None


def run_probe(host: str, account: str, user: str, password: str,
              warehouse: str, database: str, role: str | None):
    print(f"\n{'=' * 60}")
    print(f"ENGY Prefetch-Threads Probe")
    print(f"{'=' * 60}")
    print(f"  Host:      {host}")
    print(f"  Account:   {account}")
    print(f"  Warehouse: {warehouse}")
    print(f"  Database:  {database}")

    SnowflakeRestful.fetch = _patched_fetch

    try:
        conn_params = dict(
            host=host,
            account=account,
            user=user,
            password=password,
            database=database,
            warehouse=warehouse,
            login_timeout=30,
            network_timeout=60,
            session_parameters={"JDBC_QUERY_RESULT_FORMAT": "JSON"},
        )
        if role:
            conn_params["role"] = role

        print(f"\n[1/4] Connecting to ENGY ...")
        conn = snowflake.connector.connect(**conn_params)
        print(f"  Connected. Session ID: {conn.session_id}")

        # ------------------------------------------------------------------
        # Test 1: Tiny query (inline result, no chunks)
        # ------------------------------------------------------------------
        print(f"\n[2/4] Running tiny query: SELECT 1 ...")
        _captured_responses.clear()
        cur = conn.cursor()
        cur.execute("SELECT 1 AS probe")
        rows = cur.fetchall()
        cur.close()

        print(f"  Rows returned: {len(rows)}")
        if _captured_responses:
            resp = _captured_responses[-1]
            val = extract_prefetch_threads(resp["parameters"])
            print(f"  CLIENT_PREFETCH_THREADS in response: {val}")
            print(f"  Total parameters returned: {len(resp['parameters'])}")
            if val is not None and val <= 0:
                print(f"  *** BUG CONFIRMED: value is {val} (must be > 0) ***")
            elif val is None:
                print(f"  Parameter not present in response (driver will use default=4)")
            else:
                print(f"  OK: value is positive ({val})")
        else:
            print("  (no raw response captured — auth-only flow?)")

        # ------------------------------------------------------------------
        # Test 2: Large query that forces chunked download
        # ------------------------------------------------------------------
        large_query = """
            SELECT
                seq4() AS id,
                RANDSTR(100, RANDOM()) AS payload
            FROM TABLE(GENERATOR(ROWCOUNT => 100000))
        """
        print(f"\n[3/4] Running large query (100k rows) to trigger chunking ...")
        _captured_responses.clear()
        cur = conn.cursor()
        try:
            cur.execute(large_query)
            row_count = 0
            while True:
                batch = cur.fetchmany(10000)
                if not batch:
                    break
                row_count += len(batch)
            print(f"  Rows fetched: {row_count}")
        except Exception as e:
            print(f"  QUERY FAILED: {e}")
        finally:
            cur.close()

        if _captured_responses:
            resp = _captured_responses[-1]
            val = extract_prefetch_threads(resp["parameters"])
            chunks = resp.get("chunk_count")
            total = resp.get("total_rows")
            print(f"  CLIENT_PREFETCH_THREADS in response: {val}")
            print(f"  Chunks: {chunks}")
            print(f"  Total rows (from metadata): {total}")
            if val is not None and val <= 0:
                print(f"  *** BUG CONFIRMED: value is {val} — this crashes JDBC 4.x ***")
            elif val is None:
                print(f"  Parameter absent (driver uses default=4, safe)")
            else:
                print(f"  OK: value is positive ({val})")

        # ------------------------------------------------------------------
        # Test 3: Dump all parameters for inspection
        # ------------------------------------------------------------------
        print(f"\n[4/4] All parameters from last query response:")
        if _captured_responses:
            for p in sorted(_captured_responses[-1]["parameters"], key=lambda x: x.get("name", "")):
                print(f"  {p.get('name'):40s} = {p.get('value')}")
        else:
            print("  (none captured)")

        conn.close()

    finally:
        SnowflakeRestful.fetch = _original_fetch

    # ------------------------------------------------------------------
    # Verdict
    # ------------------------------------------------------------------
    print(f"\n{'=' * 60}")
    all_values = [
        extract_prefetch_threads(r["parameters"])
        for r in _captured_responses
        if r["parameters"]
    ]
    bad = [v for v in all_values if v is not None and v <= 0]
    if bad:
        print("VERDICT: BUG PRESENT — ENGY returns CLIENT_PREFETCH_THREADS <= 0")
        print("         The PR #1034 fix is NOT active on this endpoint.")
        return 1
    else:
        print("VERDICT: CLIENT_PREFETCH_THREADS looks OK on this endpoint.")
        if any(v is None for v in all_values):
            print("         (parameter was absent — driver uses safe default)")
        return 0


def main():
    parser = argparse.ArgumentParser(description="Probe ENGY for CLIENT_PREFETCH_THREADS bug")
    parser.add_argument("--host", default="engy-prd.internal.corp.traderepublic.com")
    parser.add_argument("--account", default="gm68377.eu-central-1")
    parser.add_argument("--warehouse", default="SECURITIES_SERVICES__PIPELINES__XL")
    parser.add_argument("--database", default="TEAMS_PRD")
    parser.add_argument("--role", default=None)
    parser.add_argument("--user", default=os.environ.get("ENGY_USER") or os.environ.get("SNOWFLAKE_USER"))
    parser.add_argument("--password", default=os.environ.get("ENGY_PASSWORD") or os.environ.get("SNOWFLAKE_PASSWORD"))
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
        logger.setLevel(logging.DEBUG)

    user = args.user
    password = args.password
    if not user:
        user = input("ENGY user: ").strip()
    if not password:
        import getpass
        password = getpass.getpass("ENGY password: ")

    sys.exit(run_probe(
        host=args.host,
        account=args.account,
        user=user,
        password=password,
        warehouse=args.warehouse,
        database=args.database,
        role=args.role,
    ))


if __name__ == "__main__":
    main()