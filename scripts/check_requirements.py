#!/usr/bin/env python3
"""检查 requirements.txt 中简单的 >= 版本约束。"""

import re
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

try:
    from packaging.version import Version
except ImportError:
    from pip._vendor.packaging.version import Version


def main():
    requirements = Path(sys.argv[1] if len(sys.argv) > 1 else "requirements.txt")
    for raw_line in requirements.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)\s*>=\s*([A-Za-z0-9_.+-]+)", line)
        if not match:
            print(f"unsupported requirement: {line}", file=sys.stderr)
            return 1
        package, minimum = match.groups()
        try:
            installed = version(package)
        except PackageNotFoundError:
            print(f"missing: {package}", file=sys.stderr)
            return 1
        if Version(installed) < Version(minimum):
            print(
                f"outdated: {package} {installed} < {minimum}",
                file=sys.stderr,
            )
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
