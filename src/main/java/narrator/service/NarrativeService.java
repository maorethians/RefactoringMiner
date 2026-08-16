package narrator.service;

import narrator.Driver;
import narrator.mcp.html.NarrativeHtmlGenerator;
import org.jgrapht.Graph;
import org.refactoringminer.astDiff.graph.Edge;
import org.refactoringminer.astDiff.graph.Node;
import org.refactoringminer.astDiff.graph.cluster.Cluster;
import org.refactoringminer.astDiff.graph.cluster.Clusterer;
import org.refactoringminer.astDiff.graph.cluster.traverse.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NarrativeService {
    private static final Logger logger = LoggerFactory.getLogger(NarrativeService.class);
    private final CacheManager cacheManager = new CacheManager();

    public TraversalPattern initializeNarrative(String url) throws Exception {
        return getOrComputeHierarchy(url);
    }

    public void generateNarrativeHtml(String url) throws Exception {
        List<Cluster> clusters = getOrComputeClusters(url);
        TraversalPattern root = cacheManager.getHierarchy(getHierarchyCacheKey(url));
        if (root == null) {
            return;
        }
        Narrator narrator = root.getNarrator();
        NarrativeHtmlGenerator generator = new NarrativeHtmlGenerator(url, narrator);
        generator.generateAll(clusters);
        cacheManager.putHtmlGenerator(url, generator);
    }

    public List<Narrator.ChapterUnit> getFlatChapters(String url, GrainLevel level) throws Exception {
        TraversalPattern root = cacheManager.getHierarchy(getHierarchyCacheKey(url));
        if (root == null) {
            throw new IllegalStateException("No narrative initialized for this URL: " + url);
        }

        Narrator narrator = root.getNarrator();
        if (level == GrainLevel.RAW_DIFF) {
            return getRawDiffChunks(url, narrator);
        } else {
            return narrator.getFlatChapters(level);
        }
    }

    public void updateHtmlPage(String url, GrainLevel level, int progress) {
        NarrativeHtmlGenerator generator = cacheManager.getHtmlGenerator(url);
        if (generator != null) {
            try {
                generator.generateGrainLevelPage(level, progress);
            } catch (Exception e) {
                logger.error("Failed to update narrative HTML page", e);
            }
        }
    }

    public List<Cluster> getOrComputeClusters(String url) throws Exception {
        List<Cluster> cached = cacheManager.getClusters(url);
        if (cached != null) {
            return cached;
        }

        Graph<Node, Edge> graph = loadGraph(url);
        List<Cluster> clusters = new Clusterer(graph).getClusters();
        cacheManager.putClusters(url, clusters);
        return clusters;
    }

    public TraversalPattern getOrComputeHierarchy(String url) throws Exception {
        String cacheKey = getHierarchyCacheKey(url);
        TraversalPattern cached = cacheManager.getHierarchy(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<Cluster> clusters = getOrComputeClusters(url);
        List<TraversalPattern> finalHierarchy = new ArrayList<>();

        for (Cluster cluster : clusters) {
            finalHierarchy.add(new TraversalEngine(cluster).get());
        }

        TraversalPattern root;
        if (finalHierarchy.size() > 1) {
            root = new TraversalComponent(finalHierarchy, ReasonType.CONTEXT);
        } else if (finalHierarchy.size() == 1) {
            root = finalHierarchy.get(0);
        } else {
            return null;
        }

        cacheManager.putHierarchy(cacheKey, root);
        return root;
    }

    private Graph<Node, Edge> loadGraph(String url) throws Exception {
        if (url.contains("/pull/") || url.contains("/pr/")) {
            return Driver.getPullRequestGraph(url);
        } else if (url.contains("/compare/")) {
            return Driver.getCompareGraph(url);
        } else {
            return Driver.getCommitGraph(url);
        }
    }

    private List<Narrator.ChapterUnit> getRawDiffChunks(String url, Narrator narrator) throws Exception {
        List<Narrator.ChapterUnit> cached = cacheManager.getRawDiffChunks(url);
        if (cached != null) {
            return cached;
        }

        List<Narrator.ChapterUnit> fileChapters = narrator.getFlatChapters(GrainLevel.FILE);
        int numChunks = fileChapters.size();
        if (numChunks == 0) {
            return Collections.emptyList();
        }

        String rawDiffUrl = url;
        if ((url.contains("/pull/") || url.contains("/pr/") || url.contains("/commit/") || url.contains("/compare/")) && !url.endsWith(".diff")) {
            rawDiffUrl = url + ".diff";
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rawDiffUrl))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Failed to fetch raw diff from " + rawDiffUrl + ". Status code: " + response.statusCode());
        }
        String diffContent = response.body();

        String[] lines = diffContent.split("\n");
        int totalLines = lines.length;
        List<String> chunks = new ArrayList<>();

        int baseSize = totalLines / numChunks;
        int remainder = totalLines % numChunks;
        int currentLine = 0;

        for (int i = 0; i < numChunks; i++) {
            int chunkSize = baseSize + (i < remainder ? 1 : 0);
            StringBuilder chunkBuilder = new StringBuilder();
            for (int j = 0; j < chunkSize && currentLine < totalLines; j++) {
                chunkBuilder.append(lines[currentLine++]).append("\n");
            }
            chunks.add(chunkBuilder.toString());
        }

        List<Narrator.ChapterUnit> units = chunks.stream().map(chunk -> {
            Narrator.ChapterUnit chu = new Narrator.ChapterUnit();
            chu.append(chunk);
            return chu;
        }).toList();

        cacheManager.putRawDiffChunks(url, units);
        return units;
    }

    private String getHierarchyCacheKey(String url) {
        return "hierarchy:" + url;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }
}
