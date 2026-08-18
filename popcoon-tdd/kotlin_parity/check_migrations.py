#!/usr/bin/env python3
"""check_migrations.py — Room の移行チェーンが版番号どおり連続し、全て登録されているか検査する。

背景 (2026-08):
  Room の移行ミスは **アプリが起動不能になる** クラスの障害で、しかもこの環境では
  Room を一切実行できない (androidx が無い / instrumentation テストも動かない)。
  release ビルドはユーザーデータ保全のため破壊的フォールバックを意図的に切っているので、
  移行に穴があると「更新したら二度と起動しない」になる。

  典型的な壊し方は 2 つとも純粋に静的:
    1. `MIGRATION_x_y` を書いたが `DatabaseModule` の `addMigrations(...)` に足し忘れる
    2. version を上げたのに対応する移行が無く、チェーンが途切れる

  実際に見つけた別の欠陥 (v6 で追加した price_cache テーブルの CREATE が
  MIGRATION_5_6 に無い) は列/テーブル単位の話でこの検査の範囲外だが、
  上の 2 つは確実に塞げる。

`check_overrides.py` 等と同じ位置づけ: Room の代替ではなく、特定の回帰クラスだけを塞ぐ。
"""
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DB_FILE = ROOT / "app/src/main/java/io/github/shizukutanaka/popcoon/data/db/PopcoonDatabase.kt"
MODULE_FILE = ROOT / "app/src/main/java/io/github/shizukutanaka/popcoon/di/DatabaseModule.kt"


def strip_noncode(s: str) -> str:
    """コメントを落とす。

    DatabaseModule には案内コメント
    `// 新スキーマ導入時はここに .addMigrations(MIGRATION_x_y) を追加すること。`
    があり、素朴に `addMigrations\(...\)` を探すと **コメントの方に当たって**
    登録数 0 と誤判定した (初版で実際に起きた)。コード部分だけを見る。
    """
    s = re.sub(r"/\*.*?\*/", " ", s, flags=re.S)
    return re.sub(r"//[^\n]*", " ", s)


def main() -> int:
    db = strip_noncode(io.open(DB_FILE, encoding="utf-8").read())
    module = strip_noncode(io.open(MODULE_FILE, encoding="utf-8").read())
    errors = []

    m = re.search(r"\bversion\s*=\s*(\d+)", db)
    if not m:
        print("MIGRATION CHECK: FAILED", file=sys.stderr)
        print("  @Database の version を読み取れませんでした", file=sys.stderr)
        return 1
    version = int(m.group(1))

    # 宣言されている移行 (`val MIGRATION_x_y = object : Migration(x, y)`)
    declared = {}
    for d in re.finditer(r"val\s+MIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\(\s*(\d+)\s*,\s*(\d+)\s*\)", db):
        name_from, name_to = int(d.group(1)), int(d.group(2))
        arg_from, arg_to = int(d.group(3)), int(d.group(4))
        if (name_from, name_to) != (arg_from, arg_to):
            errors.append(
                f"MIGRATION_{name_from}_{name_to} の名前と Migration({arg_from}, {arg_to}) の"
                f"引数が食い違っている (どちらかが誤り)")
        declared[(name_from, name_to)] = f"MIGRATION_{name_from}_{name_to}"

    # addMigrations(...) に列挙されているもの
    reg = re.search(r"addMigrations\s*\((.*?)\)", module, re.S)
    registered = set()
    if reg:
        for r in re.finditer(r"MIGRATION_(\d+)_(\d+)", reg.group(1)):
            registered.add((int(r.group(1)), int(r.group(2))))
    else:
        errors.append("DatabaseModule に addMigrations(...) が見つからない")

    # 1) 宣言したのに登録していない
    for pair, name in sorted(declared.items()):
        if pair not in registered:
            errors.append(f"{name} が宣言されているが addMigrations() に登録されていない "
                          f"(登録漏れは更新時に起動不能を招く)")
    # 2) 登録したのに宣言が無い
    for pair in sorted(registered - set(declared)):
        errors.append(f"MIGRATION_{pair[0]}_{pair[1]} が addMigrations() にあるが宣言が無い")

    # 3) 1 → version が連続しているか
    for v in range(1, version):
        if (v, v + 1) not in declared:
            errors.append(f"v{v} → v{v + 1} の移行が無い (version = {version} なので "
                          f"1 から {version} まで連続している必要がある)")

    print(f"migration check: schema version {version} / "
          f"{len(declared)} migrations declared / {len(registered)} registered")
    if errors:
        print("MIGRATION CHECK: FAILED", file=sys.stderr)
        for e in errors:
            print("  " + e, file=sys.stderr)
        return 1
    print("MIGRATION CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
