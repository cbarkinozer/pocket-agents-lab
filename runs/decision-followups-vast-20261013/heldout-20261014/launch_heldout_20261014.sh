#!/usr/bin/env bash
set -euo pipefail
cd /workspace
for attempt in $(seq 1 120); do
  bytes=$(stat -c %s heldout-inputs-20261014.tar.gz 2>/dev/null || echo 0)
  if [ "$bytes" = 260620163 ]; then break; fi
  sleep 10
done
echo 'c6c28b6263b18b2b635c4780286d4dbb662bef055fe880bb87e4de3233af1940  heldout-inputs-20261014.tar.gz' | sha256sum -c -
tar -xzf heldout-inputs-20261014.tar.gz
cd reflex-decision
export HF_HOME=/workspace/reflex-decision/cache/huggingface
export WANDB_DISABLED=true
.venv/bin/python -m pip freeze > evaluation-environment-freeze.txt
bash run_heldout_evals.sh runs/combined-balanced-scratch-120step-s3407/adapter
printf 'EVALUATIONS_COMPLETE\n'
