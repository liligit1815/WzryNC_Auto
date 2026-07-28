#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def expected_result(metadata):
    expected = metadata.get("expected_text")
    return None if expected in (None, "") else str(expected).strip()


def actual_result(metadata):
    result_type = metadata.get("result_type")
    if result_type == "time":
        return f"{int(metadata['hour']):02d}:{int(metadata['minute']):02d}"
    if result_type == "mature":
        return "mature"
    return "unrecognized"


def main():
    parser = argparse.ArgumentParser(
        description="统计人工复核后的 Android 成熟时间 OCR 准确率",
    )
    parser.add_argument("directory", nargs="?", default="android-ocr-samples/maturity")
    args = parser.parse_args()

    files = sorted(Path(args.directory).glob("*.json"))
    reviewed = []
    for path in files:
        metadata = json.loads(path.read_text(encoding="utf-8"))
        expected = expected_result(metadata)
        if expected is not None:
            reviewed.append((path.name, expected, actual_result(metadata)))

    correct = sum(expected == actual for _, expected, actual in reviewed)
    accuracy = (correct / len(reviewed) * 100) if reviewed else 0.0
    print(f"总样本: {len(files)}")
    print(f"已复核: {len(reviewed)}")
    print(f"正确: {correct}")
    print(f"准确率: {accuracy:.2f}%")
    for name, expected, actual in reviewed:
        if expected != actual:
            print(f"错误: {name} expected={expected} actual={actual}")


if __name__ == "__main__":
    main()
