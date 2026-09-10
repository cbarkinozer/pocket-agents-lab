#!/usr/bin/env python3
"""Extract per-expert byte offset/length ranges from a merged-expert MoE GGUF.

Stage 9 groundwork for Android expert-aware storage streaming (see README's
"Near-Term TODO" item 42 and docs/results/2026-09-10-ling-q3-android-feasibility.md).
llama.cpp stores each layer's experts as one merged 3D tensor
(`blk.{N}.ffn_{gate,down,up}_exps.weight`) with the expert index as the
outermost ggml dimension, so each expert occupies one contiguous,
equal-sized byte range within that tensor. This only reads the GGUF header
and tensor-info table (via the vendored gguf-py reader's mmap, not a full
file read), so it is safe to run against a multi-gigabyte model on a normal
machine without loading the weights.

Usage:
    python tools/gguf_expert_offsets.py path/to/model.gguf [--json out.json]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

_GGUF_PY = Path(__file__).resolve().parent.parent / "third_party" / "llama.cpp" / "gguf-py"
if str(_GGUF_PY) not in sys.path:
    sys.path.insert(0, str(_GGUF_PY))

import gguf  # noqa: E402  (path must be adjusted first)

_EXPERT_TENSOR_RE = re.compile(r"^blk\.(\d+)\.ffn_(gate|down|up)_exps\.weight$")


def _expert_count(reader: "gguf.GGUFReader") -> int:
    field = reader.get_field(gguf.Keys.LLM.EXPERT_COUNT.format(arch=_arch(reader)))
    if field is None:
        raise ValueError("Model has no {arch}.expert_count key; is this actually a MoE GGUF?")
    return int(field.parts[-1][0])


def _arch(reader: "gguf.GGUFReader") -> str:
    field = reader.get_field("general.architecture")
    if field is None:
        raise ValueError("Model has no general.architecture key")
    return str(field.parts[-1].tobytes().decode("utf-8")) if hasattr(field.parts[-1], "tobytes") else str(field.parts[-1])


def extract_expert_offsets(gguf_path: str) -> dict:
    reader = gguf.GGUFReader(gguf_path)
    arch = _arch(reader)
    n_expert = _expert_count(reader)

    layers: dict[int, dict[str, dict]] = {}
    for tensor in reader.tensors:
        match = _EXPERT_TENSOR_RE.match(tensor.name)
        if not match:
            continue
        layer_idx, proj = int(match.group(1)), match.group(2)
        if tensor.n_bytes % n_expert != 0:
            raise ValueError(
                f"{tensor.name}: {tensor.n_bytes} bytes not divisible by expert_count={n_expert}; "
                "expert axis assumption does not hold for this tensor layout"
            )
        expert_bytes = tensor.n_bytes // n_expert
        experts = [
            {
                "expert_id": expert_id,
                "offset": tensor.data_offset + expert_id * expert_bytes,
                "length": expert_bytes,
            }
            for expert_id in range(n_expert)
        ]
        layers.setdefault(layer_idx, {})[proj] = {
            "tensor_name": tensor.name,
            "tensor_type": tensor.tensor_type.name,
            "shape": [int(d) for d in tensor.shape],
            "tensor_bytes": int(tensor.n_bytes),
            "tensor_data_offset": int(tensor.data_offset),
            "experts": experts,
        }

    return {
        "gguf_path": str(gguf_path),
        "architecture": arch,
        "expert_count": n_expert,
        "layer_count": len(layers),
        "layers": {str(k): v for k, v in sorted(layers.items())},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gguf_path", help="Path to a merged-expert MoE GGUF file")
    parser.add_argument("--json", help="Write the full index to this JSON file")
    parser.add_argument(
        "--layer", type=int, default=0, help="Layer to print a sample of to stdout (default: 0)"
    )
    args = parser.parse_args()

    index = extract_expert_offsets(args.gguf_path)

    print(f"architecture={index['architecture']} expert_count={index['expert_count']} "
          f"layers_with_experts={index['layer_count']}")

    sample = index["layers"].get(str(args.layer))
    if sample:
        for proj, info in sample.items():
            first, last = info["experts"][0], info["experts"][-1]
            print(
                f"  layer {args.layer} {proj}: {info['tensor_name']} "
                f"shape={info['shape']} expert_bytes={first['length']} "
                f"expert0_offset={first['offset']} expert{len(info['experts']) - 1}_offset={last['offset']}"
            )
    else:
        print(f"  (no expert tensors found for layer {args.layer})")

    if args.json:
        Path(args.json).write_text(json.dumps(index, indent=2))
        print(f"wrote full index to {args.json}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
