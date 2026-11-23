# Datasets

Everything in this folder except this README is git-ignored. Download with `scripts/fetch-datasets.sh` (run inside the dev container: `make shell`).

| Folder | Source | Ground truth | License |
|---|---|---|---|
| `softmatic/` | softmatic/vision-data: real German receipts, 300 dpi | file name: store, date, item count, total | CC-BY-4.0 |
| `kassenzettel-synth/` | ~1000 synthetic German receipts | generated | MIT |
| `zugferd-corpus/` | ZUGFeRD sample PDFs (eval set: 23 German full-profile invoices, CI pins commit `02d4b56`) | XML embedded in the PDF | Apache-2.0 |
| `xrechnung-testsuite/` | UBL/CII XMLs | the XML itself | Apache-2.0 |
| `cord/` | 1000 Indonesian receipts (comparison) | full labels | CC-BY-4.0 |
| `own/` | **your own receipts**: real data, manual labels | manual | **never publish, never send to the Gemini free tier** |
