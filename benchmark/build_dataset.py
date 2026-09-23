"""Materialize explicit query labels from the reviewable TSV source."""
import csv
import json
from pathlib import Path

root = Path(__file__).parent
tools = []
queries = []
with (root / "dataset.tsv").open(encoding="utf-8", newline="") as source:
    for row in csv.DictReader(source, delimiter="|"):
        tools.append({"name": row["name"], "description": row["description"],
                      "tags": row["tags"].split(), "inputSchema": None})
        for difficulty in ("easy", "hard"):
            queries.append({"query": row[difficulty], "expectedTool": row["name"],
                            "category": row["category"], "difficulty": difficulty})
(root / "dataset.json").write_text(
    json.dumps({"tools": tools, "queries": queries}, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8")
print(f"{len(tools)} tools, {len(queries)} queries")
