#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Paper API 及其全部 compile 依赖下载到缓存目录，供 javac 使用。

不引入 Maven：自己解析 POM（含 dependencyManagement 里的 BOM），
递归拉取 compile / provided 作用域的依赖。只处理 jar 打包。
"""
from __future__ import annotations

import os
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

REPOS = [
    "https://repo.papermc.io/repository/maven-public",
    "https://repo1.maven.org/maven2",
    "https://hub.spigotmc.org/nexus/content/repositories/snapshots",
]


def fetch(url: str, dest: Path, timeout: int = 15) -> bool:
    if dest.is_file() and dest.stat().st_size > 0:
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".tmp")
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            data = resp.read()
    except Exception:
        return False
    if not data:
        return False
    tmp.write_bytes(data)
    os.replace(tmp, dest)
    return True


def find_in_repos(path: str, dest: Path) -> bool:
    for repo in REPOS:
        url = f"{repo.rstrip('/')}/{path}"
        if os.environ.get("IMGUP_VERBOSE"):
            print("try " + url, file=sys.stderr)
        if fetch(url, dest):
            return True
    return False


def text(node, tag: str, default: str = "") -> str:
    found = node.find(f"m:{tag}", NS)
    return found.text.strip() if found is not None and found.text else default


class Resolver:
    def __init__(self, cache: Path):
        self.cache = cache
        self.managed: dict[tuple[str, str], str] = {}
        self.seen: set[tuple[str, str, str]] = set()
        self.jars: list[Path] = []
        self.missing: list[str] = []

    def jar_path(self, group: str, artifact: str, version: str) -> Path:
        return self.cache / f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"

    def pom_path(self, group: str, artifact: str, version: str) -> Path:
        return self.cache / f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"

    def rel_path(self, group: str, artifact: str, version: str, ext: str) -> str:
        return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.{ext}"

    def resolve(self, group: str, artifact: str, version: str, depth: int = 0) -> None:
        if depth > 6:
            return
        key = (group, artifact, version)
        if key in self.seen:
            return
        self.seen.add(key)

        pom = self.pom_path(group, artifact, version)
        if not find_in_repos(self.rel_path(group, artifact, version, "pom"), pom):
            self.missing.append(f"{group}:{artifact}:{version} (pom)")
            return

        # dependencyManagement（含 BOM import）
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError as exc:
            self.missing.append(f"{group}:{artifact}:{version} (pom 解析失败 {exc})")
            return

        dm = root.find("m:dependencyManagement/m:dependencies", NS)
        if dm is not None:
            for dep in dm.findall("m:dependency", NS):
                dg = text(dep, "groupId")
                da = text(dep, "artifactId")
                dv = text(dep, "version")
                dtype = text(dep, "type", "jar")
                scope = text(dep, "scope", "compile")
                if dtype == "pom" and scope == "import" and dg and da and dv:
                    self.import_bom(dg, da, dv, depth + 1)
                    continue
                if dg and da and dv:
                    self.managed.setdefault((dg, da), dv)

        # 本 POM 的依赖
        deps = root.find("m:dependencies", NS)
        if deps is not None:
            for dep in deps.findall("m:dependency", NS):
                dg = text(dep, "groupId")
                da = text(dep, "artifactId")
                dv = text(dep, "version") or self.managed.get((dg, da), "")
                scope = text(dep, "scope", "compile")
                dtype = text(dep, "type", "jar")
                optional = text(dep, "optional", "false").lower() == "true"
                if not dg or not da or not dv:
                    continue
                if scope in ("test", "runtime") or optional:
                    continue
                if dtype != "jar":
                    continue
                self.resolve(dg, da, dv, depth + 1)

        jar = self.jar_path(group, artifact, version)
        rel = self.rel_path(group, artifact, version, "jar")
        if find_in_repos(rel, jar):
            if jar not in self.jars:
                self.jars.append(jar)
        else:
            self.missing.append(f"{group}:{artifact}:{version} (jar)")

    def import_bom(self, group: str, artifact: str, version: str, depth: int) -> None:
        pom = self.pom_path(group, artifact, version)
        if not find_in_repos(self.rel_path(group, artifact, version, "pom"), pom):
            self.missing.append(f"{group}:{artifact}:{version} (bom)")
            return
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError:
            return
        dm = root.find("m:dependencyManagement/m:dependencies", NS)
        if dm is None:
            return
        for dep in dm.findall("m:dependency", NS):
            dg = text(dep, "groupId")
            da = text(dep, "artifactId")
            dv = text(dep, "version")
            dtype = text(dep, "type", "jar")
            scope = text(dep, "scope", "compile")
            if dtype == "pom" and scope == "import" and dg and da and dv:
                self.import_bom(dg, da, dv, depth + 1)
                continue
            if dg and da and dv:
                self.managed.setdefault((dg, da), dv)


def main() -> int:
    cache = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".build-cache/deps")
    group = sys.argv[2] if len(sys.argv) > 2 else "io.papermc.paper"
    artifact = sys.argv[3] if len(sys.argv) > 3 else "paper-api"
    version = sys.argv[4] if len(sys.argv) > 4 else "26.3.build.194-stable"

    cache.mkdir(parents=True, exist_ok=True)
    resolver = Resolver(cache)
    resolver.resolve(group, artifact, version)

    sep = ";" if os.name == "nt" else ":"
    print(sep.join(str(p) for p in resolver.jars))
    if resolver.missing:
        print("缺失（可能不影响编译）：", file=sys.stderr)
        for item in resolver.missing:
            print("  " + item, file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
