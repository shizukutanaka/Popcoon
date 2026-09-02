#!/usr/bin/env python3
"""check_build_config.py — Gradle を実行せずにビルド構成の整合を検査する。

背景 (2026-08):
  この環境では Android SDK も Maven Central への egress も無く、`./gradlew` は動かせない。
  CI 有効化は人手ゲート (GitHub App に `workflows` 権限が無いことを再測定で確認済み) なので、
  **最初の CI 実行が初めての実ビルド**になる。

  「ビルド検証は実行するか諦めるかの二択」という前提を疑うと、
  **初回実行が即死する類の設定ミスは静的に決まる**。型検査 (`run_compile_core.sh`) が
  カバーするのはソースだけで、ビルド構成 (version catalog / Manifest / リソース XML /
  ワークフロー YAML) は誰も見ていなかった。初回 CI 実行は貴重なので、
  そこで初めて分かる必要のない失敗を先に潰す。

検査:
  (a) `build.gradle.kts` が参照する `libs.*` が `gradle/libs.versions.toml` に実在するか
      — タイポや catalog からの削除は「Unresolved reference: libs」で即死する
  (b) `AndroidManifest.xml` の `android:name` が指すクラスが実在するか
      — 存在しないと実行時に ClassNotFoundException (ビルドは通ってしまう)
  (c) `res/` 配下の XML が整形式か — 不正な XML は aapt が即座に落とす
  (d) `ci/android.yml` が整形式 YAML で、必須キー (name/on/jobs) を持つか
      — ワークフロー自体が壊れていると 1 ステップも走らない
"""
import io
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app"
CATALOG = ROOT / "gradle/libs.versions.toml"
MANIFEST = APP / "src/main/AndroidManifest.xml"
RES = APP / "src/main/res"
WORKFLOW = ROOT / "ci/android.yml"

errors: list[str] = []
notes: list[str] = []


def catalog_aliases() -> set[str]:
    """version catalog のエイリアスを、Gradle が公開する名前の形で集める。

    Gradle は `foo-bar-baz` を `libs.foo.bar.baz` として公開し、
    **セクションごとに名前空間が付く**:
      [libraries] alias  → libs.alias
      [plugins]   alias  → libs.plugins.alias
      [bundles]   alias  → libs.bundles.alias
    最初の実装はこの名前空間を落としており、`libs.plugins.hilt` を「未定義」と
    16 件まとめて誤報した (ツール側のバグ。ゲートが嘘をつくと本来の検出力まで疑われる)。
    """
    text = io.open(CATALOG, encoding="utf-8").read()
    prefix = {"libraries": "", "plugins": "plugins.", "bundles": "bundles."}
    aliases, section = set(), None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("["):
            section = line.strip("[]")
            continue
        if not line or line.startswith("#") or "=" not in line:
            continue
        if section in prefix:
            alias = line.split("=", 1)[0].strip().strip('"')
            aliases.add(prefix[section] + re.sub(r"[-_]", ".", alias))
    return aliases


def check_catalog_refs() -> None:
    aliases = catalog_aliases()
    if not aliases:
        errors.append(f"{CATALOG.relative_to(ROOT)}: エイリアスを 1 つも読めなかった")
        return
    used = 0
    for gradle in list(ROOT.glob("*.gradle.kts")) + list(APP.glob("*.gradle.kts")):
        code = io.open(gradle, encoding="utf-8").read()
        code = re.sub(r"//[^\n]*", " ", code)
        for m in re.finditer(r"\blibs\.((?:[A-Za-z][A-Za-z0-9]*)(?:\.[A-Za-z][A-Za-z0-9]*)*)", code):
            ref = m.group(1)
            if ref.startswith("versions."):
                continue  # libs.versions.xxx は [versions] 側
            used += 1
            # catalog 側は完全一致、または `a.b.c` が `a.b` バンドルの入れ子でないこと
            if ref not in aliases:
                line = code[:m.start()].count("\n") + 1
                errors.append(
                    f"{gradle.relative_to(ROOT)}:{line}: libs.{ref} が "
                    f"gradle/libs.versions.toml に無い")
    notes.append(f"version catalog: {used} 参照 / {len(aliases)} エイリアス定義")


def check_manifest() -> None:
    try:
        tree = ET.parse(MANIFEST)
    except ET.ParseError as e:
        errors.append(f"{MANIFEST.relative_to(ROOT)}: XML が壊れている — {e}")
        return
    ANDROID = "{http://schemas.android.com/apk/res/android}name"
    pkg = "io.github.shizukutanaka.popcoon"
    src = APP / "src/main/java" / pkg.replace(".", "/")
    checked = 0
    for el in tree.iter():
        name = el.get(ANDROID)
        if not name or el.tag not in ("activity", "application", "receiver", "service", "provider"):
            continue
        fqn = pkg + name if name.startswith(".") else name
        if not fqn.startswith(pkg):
            continue  # フレームワーク/ライブラリのクラスは対象外
        rel = fqn[len(pkg) + 1:].replace(".", "/") + ".kt"
        checked += 1
        if not (src / rel).exists():
            # ファイル名 != クラス名のこともあるので、宣言でも探す
            cls = fqn.rsplit(".", 1)[1]
            hit = any(re.search(rf"\bclass\s+{re.escape(cls)}\b", io.open(f, encoding="utf-8").read())
                      for f in src.rglob("*.kt"))
            if not hit:
                errors.append(
                    f"{MANIFEST.relative_to(ROOT)}: <{el.tag}> の {fqn} に対応するクラスが無い "
                    f"(実行時に ClassNotFoundException — ビルドは通ってしまう)")
    notes.append(f"Manifest: {checked} 個の自プロジェクトクラス参照を確認")


def check_res_xml() -> None:
    n = 0
    for f in sorted(RES.rglob("*.xml")):
        n += 1
        try:
            ET.parse(f)
        except ET.ParseError as e:
            errors.append(f"{f.relative_to(ROOT)}: XML が壊れている — {e}")
    notes.append(f"リソース XML: {n} ファイルが整形式")


def check_workflow() -> None:
    if not WORKFLOW.exists():
        errors.append(f"{WORKFLOW.relative_to(ROOT)} が無い")
        return
    text = io.open(WORKFLOW, encoding="utf-8").read()
    try:
        import yaml  # type: ignore
        doc = yaml.safe_load(text)
    except ImportError:
        notes.append("ci/android.yml: PyYAML 不在のため構造検査のみ (整形式チェックは省略)")
        doc = None
    except Exception as e:  # noqa: BLE001
        errors.append(f"ci/android.yml: YAML として読めない — {e}")
        return
    if doc is not None:
        # `on:` は YAML 1.1 で真偽値 True に解釈される (GitHub Actions の既知の罠)
        keys = {str(k) for k in doc}
        for required in ("name", "jobs"):
            if required not in keys:
                errors.append(f"ci/android.yml: 必須キー '{required}' が無い")
        if "on" not in keys and "True" not in keys:
            errors.append("ci/android.yml: トリガー 'on' が無い")
        jobs = doc.get("jobs") or {}
        for jid, job in jobs.items():
            if not isinstance(job, dict) or "runs-on" not in job:
                errors.append(f"ci/android.yml: job '{jid}' に runs-on が無い")
        notes.append(f"ci/android.yml: 整形式 / job {len(jobs)} 個")


def main() -> int:
    check_catalog_refs()
    check_manifest()
    check_res_xml()
    check_workflow()
    for n in notes:
        print(f"build-config check: {n}")
    if errors:
        print("BUILD CONFIG CHECK: FAILED", file=sys.stderr)
        for e in errors:
            print("  " + e, file=sys.stderr)
        print("  初回 CI 実行はこの環境で唯一の実ビルドになる。", file=sys.stderr)
        print("  そこで初めて分かる必要のない失敗は先に潰すこと。", file=sys.stderr)
        return 1
    print("BUILD CONFIG CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
