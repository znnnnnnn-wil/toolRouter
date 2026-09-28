# Tool Router

Retrieve relevant tools before an LLM has to inspect every tool schema.

Tool Router is a Java 21 library for **candidate retrieval**. It stores tool definitions in memory and offers Lucene BM25, embedding cosine search, and RRF fusion. It does not execute tools or choose the final tool for an agent.

## Why

As a tool catalog grows, sending every schema to an LLM increases prompt size and adds confusing near matches. A retriever can narrow the catalog to Top-K candidates. The agent still makes the final decision because it may need conversation context, argument validation, authorization, and execution policy that a relevance score does not contain.

## Architecture

The Maven reactor has a library module (tool-router) and a separate tooling module (tool-router-tooling) for the example and benchmark. The library API is organized into `model`, `registry`, `embedding`, and `routing` packages. See [architecture](docs/architecture.md) for the data flow and design details.

## Quick start

Requires Java 21 and Maven.

```bash
mvn clean install
mvn -q -f tool-router-tooling/pom.xml exec:java "-Dexec.mainClass=io.github.toolrouter.Example"
```

```java
import io.github.toolrouter.model.ToolDefinition;
import io.github.toolrouter.model.RouteResponse;
import io.github.toolrouter.registry.InMemoryToolRegistry;
import io.github.toolrouter.routing.BM25ToolRouter;

InMemoryToolRegistry registry = new InMemoryToolRegistry();
registry.register(new ToolDefinition(
    "refund_order", "Return money for a paid order",
    List.of("order", "refund", "payment"), null));

try (BM25ToolRouter router = new BM25ToolRouter(registry)) {
    RouteResponse response = router.routeWithHint(
        "I want a refund for yesterday's order", 5);
    response.candidates().forEach(candidate ->
        System.out.println(candidate.rank() + ". " + candidate.tool().name()));
}
```

The registry supports `register`, `update`, `remove`, `get`, `list`, and `size`. Names are unique. Mutations become visible on subsequent route calls.

The CLI example accepts `--router bm25|vector|hybrid` plus query words. Vector and hybrid modes use environment variables:

| Variable | Purpose | Default |
|---|---|---|
| `DASHSCOPE_API_KEY` | API credential, read only from the environment | Required for vector/hybrid |
| `EMBEDDING_BASE_URL` | OpenAI-compatible base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `EMBEDDING_MODEL` | Embedding model | `text-embedding-v4` |
| `EMBEDDING_DIMENSIONS` | Vector dimension | `1024` |

The default URL remains supported, but Alibaba Cloud recommends a workspace-specific base URL. Use the endpoint for your region and workspace. The [official compatible API documentation](https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai) specifies the URL and request format; the [synchronous API reference](https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api/) lists model dimensions and the 10-text batch limit for V4. No credential is stored in the repository.

## Routing strategies

**BM25:** Lucene indexes name, tags, and description as separate fields. Default weights are 1/1/1, avoiding an unevaluated field preference. Positive weights can be set in the constructor. The index is refreshed from a versioned registry snapshot after a mutation.

**Embedding:** `EmbeddingProvider` is provider-agnostic and batch-oriented. `OpenAiCompatibleEmbeddingProvider` uses Java `HttpClient`, accepts base URL, API key, model, dimensions and batch size, and checks response dimensions and indices. Its default request timeout is 60 seconds, with up to two bounded retries for rate limits and transient server errors; an extended constructor makes these values configurable. For Alibaba Cloud V4 it sends `model`, `input`, `dimensions`, and `encoding_format=float` to `/embeddings` in chunks of at most 10. `VectorToolRouter` reuses vectors when a tool's retrieval text is unchanged, scores cosine similarity, and keeps Top-K in a heap.

**Hybrid:** `HybridToolRouter` independently retrieves candidates from BM25 and embedding search, then computes `sum(1 / (rrfK + rank))` per tool. The default `rrfK` is 60 and candidate pool is 50; both are configurable. RRF combines ranks because BM25 and cosine scores have different scales. Fusion is optional: the [benchmark](docs/benchmark.md) shows that equal-rank fusion can hurt when one retriever is substantially weaker on the query set.

**Score-gap hint:** `routeWithHint` returns the relative gap between the top two scores. It retrieves two scores internally even when only one candidate is requested. It recommends fallback for fewer than two available candidates, a nonpositive top score, or a gap below 0.1. This is a within-query heuristic, **not a calibrated probability or correctness estimate**. The threshold is a conservative example; callers should validate fallback policy on their own data. A fallback can expose more schemas to the agent.

## Benchmark

The reviewable dataset is packaged with the tooling module, and its optional export script lives in tool-router-tooling/benchmark/. Run instructions, methodology, and results are in [benchmark documentation](docs/benchmark.md).

## Limitations and roadmap

The benchmark is small and English-only. Embedding calls incur provider cost and latency outside the hot-vector routing numbers. Delete `.benchmark-cache/` when changing a model behind the same alias to avoid stale vectors. Registry writes trigger full snapshot copies; BM25 reindexes on change, while vector refresh scans all tools but embeds only new or changed retrieval text. The score-gap hint is not calibrated. There is no persistence of registry metadata, tool execution, or automatic fallback. A useful next step is a held-out multilingual evaluation and an incremental BM25 index update path, while keeping this a library rather than an agent platform.

## Development and license

`mvn clean verify` runs offline tests with deterministic embedding stubs and a loopback HTTP server; CI runs the same command on Java 21 without secrets or external model calls. See [development notes](docs/development.md) for the project layout and commands. Apache License 2.0: see [LICENSE](LICENSE).
