import sqlite3
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\e2e-artifacts\omnillm.db"
c = sqlite3.connect(path)
print("TABLES:")
for (name,) in c.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
    print(" ", name)
print("\nSCHEMAS (settings-related):")
for name, sql in c.execute("SELECT name, sql FROM sqlite_master WHERE type='table'"):
    low = (name or "").lower()
    if any(k in low for k in ("set", "policy", "config", "kv", "pref")):
        print(name)
        print(sql)
        print("---")
print("\nSEARCH content for exploratory:")
for (name,) in c.execute("SELECT name FROM sqlite_master WHERE type='table'"):
    try:
        cols = [r[1] for r in c.execute(f"PRAGMA table_info({name})")]
        for col in cols:
            try:
                rows = c.execute(
                    f"SELECT * FROM {name} WHERE CAST({col} AS TEXT) LIKE '%exploratory%' LIMIT 5"
                ).fetchall()
                if rows:
                    print(name, col, rows)
            except Exception:
                pass
    except Exception as e:
        print("err", name, e)
