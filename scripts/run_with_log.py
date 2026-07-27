#!/usr/bin/env python3
"""跨平台运行主脚本，同时将输出写到终端和日志文件。"""

import subprocess
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 3:
        print("usage: run_with_log.py SCRIPT LOG_FILE", file=sys.stderr)
        return 2

    script = Path(sys.argv[1]).resolve()
    log_file = Path(sys.argv[2]).expanduser().resolve()
    log_file.parent.mkdir(parents=True, exist_ok=True)

    process = subprocess.Popen(
        [sys.executable, "-u", str(script)],
        stdin=None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )

    try:
        with log_file.open("a", encoding="utf-8") as log:
            assert process.stdout is not None
            for line in process.stdout:
                sys.stdout.write(line)
                sys.stdout.flush()
                log.write(line)
                log.flush()
        return process.wait()
    except KeyboardInterrupt:
        # 子进程与包装器共享终端，通常已同时收到 Ctrl+C；仅在仍运行时补发。
        if process.poll() is None:
            process.terminate()
        return process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
