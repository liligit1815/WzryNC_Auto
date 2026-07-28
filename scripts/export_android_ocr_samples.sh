#!/usr/bin/env bash
set -euo pipefail

device="${1:-${WZRY_DEVICE:-}}"
output_dir="${2:-android-ocr-samples}"
adb_bin="${WZRY_ADB:-adb}"

if [[ -z "$device" ]]; then
    mapfile -t devices < <("$adb_bin" devices | awk 'NR > 1 && $2 == "device" {print $1}')
    if [[ ${#devices[@]} -ne 1 ]]; then
        echo "请传入设备序列号，或设置 WZRY_DEVICE（当前在线设备数：${#devices[@]}）" >&2
        exit 2
    fi
    device="${devices[0]}"
fi

mkdir -p "$output_dir"
if ! "$adb_bin" -s "$device" exec-out \
    run-as com.lili.wzryfarm \
    tar -C files/ocr_samples -cf - maturity \
    | tar -C "$output_dir" -xf -; then
    echo "导出失败：请确认已安装 debug APK，并至少执行过一次“测试 OCR”" >&2
    exit 1
fi

sample_count="$(find "$output_dir/maturity" -type f -name '*.json' 2>/dev/null | wc -l)"
echo "已导出 $sample_count 个 OCR 样本到 $output_dir/maturity"
