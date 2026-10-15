#!/usr/bin/env python3
"""Room schema 迁移主机侧演练工具（多源合并 D3，清单 1.2）。

从 Room 导出的 schema JSON 重建 v{from} 数据库结构并预置种子数据，
应用 migration SQL 后与 v{to} schema JSON 重建的结构逐项比对：
表集合、列（名/类型/NOT NULL/默认值/主键位）、索引（唯一性+列）、外键、视图 SQL、行数保全。

用法：
  python3 tools/db_migration_check.py --from 90 --to 91
  python3 tools/db_migration_check.py --from 91 --to 92 --sql tools/db/migrations/migration_91_92.sql

退出码：0=PASS，1=FAIL。仅做结构层验证，实库升级仍须按 SOP 在设备上执行。
"""

import argparse
import json
import os
import re
import sqlite3
import sys

SCHEMA_DIR = os.path.join("app", "schemas", "io.legado.app.data.AppDatabase")
IGNORE_TABLES = {"room_master_table", "android_metadata", "sqlite_sequence"}


def load_schema(version):
    path = os.path.join(SCHEMA_DIR, f"{version}.json")
    if not os.path.exists(path):
        print(f"[FAIL] schema 文件不存在: {path}（需先跑 assembleDebug 让 KSP 导出）")
        sys.exit(1)
    with open(path, encoding="utf-8") as f:
        return json.load(f)["database"]


def build_db(schema):
    """按 schema JSON 的 createSql 原样建库，返回 conn 与建表语句数。"""
    conn = sqlite3.connect(":memory:")
    for q in schema.get("setupQueries", []):
        conn.execute(q)
    for entity in schema["entities"]:
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for idx in entity.get("indices", []):
            conn.execute(idx["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for view in schema.get("views", []):
        conn.execute(view["createSql"].replace("${VIEW_NAME}", view["viewName"]))
    conn.commit()
    return conn


def seed_rows(conn, schema):
    """每表尽力预置 1 行，返回 {table: seeded_count}。失败表跳过并记录。"""
    dummy = {"TEXT": "'x'", "INTEGER": "1", "REAL": "1.0", "BLOB": "x'00'"}
    seeded = {}
    for entity in schema["entities"]:
        cols, vals = [], []
        for f in entity["fields"]:
            cols.append(f'"{f["columnName"]}"')
            if f.get("defaultValue") is not None:
                vals.append(f["defaultValue"])
            else:
                vals.append(dummy.get(f["affinity"], "NULL"))
        sql = f'INSERT INTO "{entity["tableName"]}" ({", ".join(cols)}) VALUES ({", ".join(vals)})'
        try:
            conn.execute(sql)
            seeded[entity["tableName"]] = 1
        except sqlite3.Error:
            seeded[entity["tableName"]] = 0
    conn.commit()
    return seeded


def table_columns(conn, table):
    return {
        r[1]: (r[2].upper(), r[3], norm_default(r[4]), r[5])
        for r in conn.execute(f'PRAGMA table_info("{table}")')
    }


def table_indices(conn, table):
    out = {}
    for r in conn.execute(f'PRAGMA index_list("{table}")'):
        name, unique, origin = r[1], r[2], r[3]
        if origin != "c" or name.startswith("sqlite_autoindex"):
            continue
        cols = [x[2] for x in conn.execute(f'PRAGMA index_info("{name}")')]
        out[name] = (bool(unique), tuple(cols))
    return out


def table_fks(conn, table):
    rows = conn.execute(f'PRAGMA foreign_key_list("{table}")').fetchall()
    return sorted((r[2], r[3], r[4], r[5], r[6]) for r in rows)


def norm_default(v):
    if v is None:
        return None
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] == "'":
        v = v[1:-1]
    return v


def norm_sql(sql):
    return re.sub(r"\s+", " ", sql or "").strip()


def all_tables(conn):
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall()
    return {r[0] for r in rows} - IGNORE_TABLES


def all_views(conn):
    rows = conn.execute("SELECT name, sql FROM sqlite_master WHERE type='view'").fetchall()
    return {r[0]: norm_sql(r[1]) for r in rows}


def compare(conn_actual, schema_to):
    """actual 库 vs to.json 重建库，返回问题列表。"""
    problems = []
    conn_expect = build_db(schema_to)

    ta, te = all_tables(conn_actual), all_tables(conn_expect)
    for t in sorted(te - ta):
        problems.append(f"缺表: {t}")
    for t in sorted(ta - te):
        problems.append(f"多表: {t}（迁移后残留?）")

    for t in sorted(ta & te):
        ca, ce = table_columns(conn_actual, t), table_columns(conn_expect, t)
        for c in sorted(set(ce) - set(ca)):
            problems.append(f"表 {t} 缺列: {c}")
        for c in sorted(set(ca) - set(ce)):
            problems.append(f"表 {t} 多列: {c}")
        for c in sorted(set(ca) & set(ce)):
            if ca[c] != ce[c]:
                problems.append(f"表 {t} 列 {c} 定义不一致: actual={ca[c]} expect={ce[c]}")
        ia, ie = table_indices(conn_actual, t), table_indices(conn_expect, t)
        for n in sorted(set(ie) - set(ia)):
            problems.append(f"表 {t} 缺索引: {n}")
        for n in sorted(set(ia) - set(ie)):
            problems.append(f"表 {t} 多索引: {n}")
        for n in sorted(set(ia) & set(ie)):
            if ia[n] != ie[n]:
                problems.append(f"表 {t} 索引 {n} 不一致: actual={ia[n]} expect={ie[n]}")
        fa, fe = table_fks(conn_actual, t), table_fks(conn_expect, t)
        if fa != fe:
            problems.append(f"表 {t} 外键不一致: actual={fa} expect={fe}")

    va, ve = all_views(conn_actual), all_views(conn_expect)
    for v in sorted(set(ve) - set(va)):
        problems.append(f"缺视图: {v}")
    for v in sorted(set(va) - set(ve)):
        problems.append(f"多视图: {v}")
    for v in sorted(set(va) & set(ve)):
        if va[v] != ve[v]:
            problems.append(f"视图 {v} SQL 不一致")

    return problems


def check_rows(conn_actual, seeded, schema_from, schema_to):
    """迁移前后行数保全（可种子化的表）。"""
    problems = []
    before = all_tables(conn_actual)
    tables_from = {e["tableName"] for e in schema_from["entities"]}
    tables_to = {e["tableName"] for e in schema_to["entities"]}
    for t, n in seeded.items():
        if n == 0 or t not in before or t not in tables_from or t not in tables_to:
            continue
        cnt = conn_actual.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
        if cnt < n:
            problems.append(f"表 {t} 数据丢失: 迁移后 {cnt} 行 < 迁移前 {n} 行")
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="v_from", type=int, required=True)
    ap.add_argument("--to", dest="v_to", type=int, required=True)
    ap.add_argument("--sql", help="迁移 SQL 快照文件（tools/db/migrations/），空迁移可省略")
    args = ap.parse_args()

    schema_from = load_schema(args.v_from)
    schema_to = load_schema(args.v_to)

    conn = build_db(schema_from)
    seeded = seed_rows(conn, schema_from)

    if args.sql:
        if not os.path.exists(args.sql):
            print(f"[FAIL] 迁移 SQL 不存在: {args.sql}")
            sys.exit(1)
        with open(args.sql, encoding="utf-8") as f:
            conn.executescript(f.read())
        print(f"已应用迁移 SQL: {args.sql}")

    problems = compare(conn, schema_to)
    problems += check_rows(conn, seeded, schema_from, schema_to)

    print(f"演练 v{args.v_from} -> v{args.v_to}: 种子 {sum(seeded.values())}/{len(seeded)} 表")
    if problems:
        print(f"[FAIL] {len(problems)} 处不一致:")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("[PASS] 结构与行数保全比对一致")


if __name__ == "__main__":
    main()
