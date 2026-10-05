#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import xml.etree.ElementTree as ElementTree
from pathlib import Path

IGNORED_SUFFIXES = (".asc", ".md5", ".sha1", ".sha256", ".sha512")
POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def element_text(root: ElementTree.Element, path: str) -> str:
    element = root.find(path, POM_NAMESPACE)
    return element.text.strip() if element is not None and element.text else ""


def validate_pom(pom: Path, release_version: str) -> None:
    root = ElementTree.parse(pom).getroot()
    required = (
        "m:groupId", "m:artifactId", "m:version", "m:name", "m:description", "m:url",
        "m:licenses/m:license/m:name", "m:licenses/m:license/m:url",
        "m:developers/m:developer/m:name", "m:scm/m:url", "m:scm/m:connection",
    )
    missing = [path for path in required if not element_text(root, path)]
    if missing:
        raise SystemExit(f"{pom} is missing required Maven Central metadata: {missing}")
    if element_text(root, "m:version") != release_version:
        raise SystemExit(f"{pom} does not use release version {release_version}")
    for dependency in root.findall(".//m:dependency", POM_NAMESPACE):
        version = element_text(dependency, "m:version")
        if version == "unspecified" or "SNAPSHOT" in version:
            group = element_text(dependency, "m:groupId")
            artifact = element_text(dependency, "m:artifactId")
            raise SystemExit(f"{pom} contains an unpublishable dependency: {group}:{artifact}:{version}")


def validate_companions(pom: Path) -> None:
    root = ElementTree.parse(pom).getroot()
    packaging = element_text(root, "m:packaging") or "jar"
    if packaging == "pom":
        return
    primary = pom.parent / f"{pom.stem}.{packaging}"
    if not primary.is_file():
        raise SystemExit(f"{pom} declares a missing primary artifact: {primary.name}")
    for classifier in ("sources", "javadoc"):
        companion = pom.parent / f"{pom.stem}-{classifier}.jar"
        if not companion.is_file():
            raise SystemExit(f"{primary} requires {companion.name} for Maven Central")


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare one kmplayer Maven Central bundle tree.")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--staging", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--group", default="dev.brahmkshatriya.kmplayer")
    args = parser.parse_args()

    repository = args.repository.expanduser().resolve()
    staging = args.staging.expanduser().resolve()
    group_root = repository / Path(*args.group.split("."))
    if not group_root.is_dir():
        raise SystemExit(f"Maven group does not exist: {group_root}")
    if staging == repository or repository in staging.parents:
        raise SystemExit("Staging must not be the repository or a child of it")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    copied_files = 0
    copied_modules = 0
    for version_directory in sorted(group_root.glob(f"*/{args.version}")):
        destination = staging / version_directory.relative_to(repository)
        destination.mkdir(parents=True, exist_ok=True)
        module_files = 0
        for source in sorted(version_directory.iterdir()):
            if not source.is_file() or source.name.startswith("maven-metadata") or source.name.endswith(IGNORED_SUFFIXES):
                continue
            shutil.copy2(source, destination / source.name)
            copied_files += 1
            module_files += 1
        if module_files:
            copied_modules += 1

    if not copied_files:
        raise SystemExit(f"No {args.group}:{args.version} artifacts were found")
    poms = sorted(staging.rglob("*.pom"))
    if not poms:
        raise SystemExit("The staged repository contains no POM files")
    for version_directory in sorted(staging.glob(f"**/{args.version}")):
        if version_directory.is_dir() and not list(version_directory.glob("*.pom")):
            raise SystemExit(f"{version_directory} contains artifacts but no POM")
    for pom in poms:
        validate_pom(pom, args.version)
        validate_companions(pom)
    print(f"Staged {copied_files} files from {copied_modules} modules; validated {len(poms)} POMs")


if __name__ == "__main__":
    main()
