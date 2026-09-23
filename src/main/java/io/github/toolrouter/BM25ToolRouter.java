package io.github.toolrouter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;

/** Lucene BM25 retriever with configurable field weights and snapshot-safe rebuilds. */
public final class BM25ToolRouter implements ToolRouter, AutoCloseable {
    private final InMemoryToolRegistry registry;
    private final Map<String, Float> boosts;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private Directory directory;
    private DirectoryReader reader;
    private long indexedVersion = -1;
    private boolean closed;

    public BM25ToolRouter(InMemoryToolRegistry registry) { this(registry, 1f, 1f, 1f); }

    public BM25ToolRouter(InMemoryToolRegistry registry, float nameBoost, float tagsBoost, float descriptionBoost) {
        if (nameBoost <= 0 || tagsBoost <= 0 || descriptionBoost <= 0) throw new IllegalArgumentException("boosts must be positive");
        this.registry = registry;
        this.boosts = Map.of("name", nameBoost, "tags", tagsBoost, "description", descriptionBoost);
    }

    private void refresh(InMemoryToolRegistry.Snapshot snapshot) {
        lock.writeLock().lock();
        try {
            if (closed) throw new IllegalStateException("router is closed");
            if (indexedVersion == snapshot.version()) return;
            Directory next = new RAMDirectory();
            try (IndexWriter writer = new IndexWriter(next, new IndexWriterConfig(analyzer))) {
                for (ToolDefinition tool : snapshot.tools().values()) {
                    Document doc = new Document();
                    doc.add(new StringField("id", tool.name(), Field.Store.YES));
                    doc.add(new TextField("name", tool.name().replace('_', ' '), Field.Store.NO));
                    doc.add(new TextField("tags", String.join(" ", tool.tags()), Field.Store.NO));
                    doc.add(new TextField("description", tool.description(), Field.Store.NO));
                    writer.addDocument(doc);
                }
            }
            DirectoryReader nextReader = DirectoryReader.open(next);
            DirectoryReader oldReader = reader;
            Directory oldDirectory = directory;
            reader = nextReader;
            directory = next;
            indexedVersion = snapshot.version();
            if (oldReader != null) oldReader.close();
            if (oldDirectory != null) oldDirectory.close();
        } catch (IOException e) { throw new IllegalStateException("Lucene index failed", e); }
        finally { lock.writeLock().unlock(); }
    }

    @Override public List<RouteResult> route(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        InMemoryToolRegistry.Snapshot snapshot = registry.snapshot();
        while (true) {
            refresh(snapshot);
            lock.readLock().lock();
            if (indexedVersion == snapshot.version()) break;
            lock.readLock().unlock();
        }
        try {
            if (snapshot.tools().isEmpty()) return List.of();
            MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[]{"name", "tags", "description"}, analyzer, boosts);
            parser.setDefaultOperator(MultiFieldQueryParser.Operator.OR);
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            ScoreDoc[] hits = searcher.search(parser.parse(MultiFieldQueryParser.escape(query)), Math.min(topK, snapshot.tools().size())).scoreDocs;
            List<RouteResult> results = new ArrayList<>(hits.length);
            for (ScoreDoc hit : hits) {
                ToolDefinition tool = snapshot.tools().get(searcher.doc(hit.doc).get("id"));
                if (tool != null) results.add(new RouteResult(tool, hit.score, results.size() + 1));
            }
            return List.copyOf(results);
        } catch (IOException | ParseException e) { throw new IllegalStateException("Lucene search failed", e); }
        finally { lock.readLock().unlock(); }
    }

    @Override public void close() {
        lock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            if (reader != null) reader.close();
            if (directory != null) directory.close();
            analyzer.close();
            reader = null;
            directory = null;
            indexedVersion = -1;
        } catch (IOException e) { throw new IllegalStateException(e); }
        finally { lock.writeLock().unlock(); }
    }
}
