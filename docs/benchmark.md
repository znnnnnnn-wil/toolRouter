# Benchmark

`tool-router-tooling/src/main/resources/benchmark/dataset.tsv` contains 100 tools and 200 labeled queries in 20 domains, with one keyword-oriented and one natural-language hard query per tool. Similar actions within a domain serve as hard negatives. The benchmark reads this classpath resource; `python tool-router-tooling/benchmark/build_dataset.py` can export an ignored JSON copy. There is no training step or dataset-specific routing rule. This manually authored diagnostic set is small and English-only; it should not be treated as a general accuracy claim.

```bash
# Retrieval accuracy and retrieval-only scaling; add --cached-only to reuse existing real vectors without a key.
mvn -q -f tool-router-tooling/pom.xml exec:java "-Dexec.mainClass=io.github.toolrouter.Benchmark" "-Dexec.args=--scaling"
# With DASHSCOPE_API_KEY set, also measure uncached online query embedding latency.
mvn -q -f tool-router-tooling/pom.xml exec:java "-Dexec.mainClass=io.github.toolrouter.Benchmark" "-Dexec.args=--online"
```

Run `mvn clean install` from the repository root before these commands. The dataset loads from the tooling JAR, so the benchmark can also run from another working directory. The benchmark uses real `text-embedding-v4` vectors at 1024 dimensions by default. It persists vectors under ignored `.benchmark-cache/` relative to the working directory; set `BENCHMARK_CACHE_DIR` to choose another path. Cache keys include endpoint, model, dimension, and exact text; credentials are never cached. Retrieval-only runs prefetch tool **and query** vectors. `--cached-only` reuses previously saved real vectors and fails if an entry is missing. No secret or external API is needed for unit tests or CI.

Measured on Windows 11, Eclipse Temurin 21.0.12.1, Maven 3.9.9, Lucene 10.5.1, 2026-09-23. Accuracy uses all 200 queries, with 20 warmup queries per strategy. MRR is truncated at rank 5.

| Router | Recall@1 | Recall@3 | Recall@5 | MRR@5 |
|---|---:|---:|---:|---:|
| BM25 | 0.540 | 0.650 | 0.675 | 0.594 |
| Embedding | 0.675 | 0.875 | 0.940 | 0.777 |
| Hybrid RRF | 0.615 | 0.770 | 0.825 | 0.699 |

### Retrieval-only latency

**Retrieval-only latency excludes query embedding API calls and tool-index construction.** Query vectors are already cached, so the roughly 0.095 ms Embedding P50 describes cached-vector cosine scan and Top-K, **not real online Agent routing latency**. The following P50/P95 values are from a complete 200-query run using previously cached real model vectors:

| Router | P50 ms | P95 ms |
|---|---:|---:|
| BM25 | 0.433 | 1.083 |
| Embedding | 0.094 | 0.117 |
| Hybrid RRF | 0.430 | 0.883 |

The 100 hard queries alone retain the same accuracy pattern:

| Router | Recall@1 | Recall@3 | Recall@5 | MRR@5 |
|---|---:|---:|---:|---:|
| BM25 | 0.170 | 0.310 | 0.350 | 0.242 |
| Embedding | 0.500 | 0.810 | 0.890 | 0.654 |
| Hybrid RRF | 0.310 | 0.550 | 0.650 | 0.442 |

Embedding is the strongest strategy on this set. Among hard queries, Vector gets Top-1 right while Hybrid gets it wrong on 26 cases; BM25 gets Top-1 right while Hybrid gets it wrong on 3, and Hybrid alone recovers 4. **Equal-weight RRF can hurt when lexical retrieval is materially weaker than semantic retrieval on hard paraphrase queries.** The dataset and default fusion parameters were not altered to make hybrid win.

### Online / end-to-end routing latency

**Online latency includes one remote query embedding API call per route but excludes tool-index construction.** Tool embeddings are prebuilt and cached; each query bypasses the query cache and calls the existing OpenAI-compatible provider. The run used real DashScope `text-embedding-v4`, 50 queries selected with seed 42, three untimed warmup calls per strategy, and one timed route per query per strategy. Requests were sequential. The values reflect that run's network and service conditions, not a latency guarantee.

| Router | E2E P50 ms | E2E P95 ms |
|---|---:|---:|
| Embedding | 133.466 | 217.064 |
| Hybrid RRF | 146.280 | 277.055 |

### Retrieval-only scaling

The scaling run uses the same 50 seed-42 queries at each size. Each router/index and all tool/query vectors are ready before timing; 10 complete warmup rounds are discarded, then 20 rounds yield 1,000 measured route latencies per size and strategy. It uses synthetic copies of the tools only for latency, so accuracy is not reported. Values below are P50/P95 in milliseconds from the revised protocol using cached real vectors:

| Tools | BM25 | Embedding | Hybrid |
|---:|---:|---:|---:|
| 100 | 0.133 / 0.229 | 0.156 / 0.185 | 0.415 / 0.580 |
| 500 | 0.117 / 0.196 | 0.502 / 0.774 | 0.751 / 1.170 |
| 1000 | 0.129 / 0.194 | 1.003 / 1.531 | 1.411 / 2.119 |

Sub-millisecond JVM microbenchmarks are sensitive to JIT, GC and host load. BM25 is an inverted-index search whose cost also depends on matching terms, so its P50 need not rise monotonically with catalog size. The small differences across these BM25 rows should not be interpreted as a scaling advantage.
