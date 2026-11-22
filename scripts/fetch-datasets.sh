#!/usr/bin/env bash
# Download public eval datasets into data/. Run inside the dev container: `make shell` then `scripts/fetch-datasets.sh`.
# Own receipts go into data/own/ manually and are never published.
set -euo pipefail
cd "$(dirname "$0")/../data"

clone() { # <git url> <target dir>
  if [ -d "$2/.git" ]; then echo "= $2 already present"; else
    rm -rf "$2"; git clone --depth 1 "$1" "$2"; fi
}

clone https://github.com/ZUGFeRD/corpus.git               zugferd-corpus
clone https://github.com/itplr-kosit/xrechnung-testsuite.git xrechnung-testsuite
clone https://huggingface.co/datasets/nimalu/kassenzettel-synth kassenzettel-synth
clone https://huggingface.co/datasets/naver-clova-ix/cord-v2   cord

# softmatic/vision-data: location not confirmed (not found on GitHub; Hugging Face requires login).
# Download it manually into data/softmatic/ and keep the original file names (they carry the ground truth).
echo "NOTE: download softmatic/vision-data manually into data/softmatic/"
