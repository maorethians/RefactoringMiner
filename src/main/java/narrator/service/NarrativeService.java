package narrator.service;

import narrator.Driver;
import narrator.mcp.html.NarrativeHtmlGenerator;
import org.jgrapht.Graph;
import org.refactoringminer.astDiff.graph.Edge;
import org.refactoringminer.astDiff.graph.Node;
import org.refactoringminer.astDiff.graph.RawNode;
import org.refactoringminer.astDiff.graph.ReviewNode;
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
import java.util.Set;
import java.util.stream.Collectors;

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
        if (level == GrainLevel.RAW_DIFF) {
            return getRawDiffChapters(url);
        }

        TraversalPattern root = cacheManager.getHierarchy(getHierarchyCacheKey(url));
        if (root == null) {
            throw new IllegalStateException("No narrative initialized for this URL: " + url);
        }

        return root.getNarrator().getFlatChapters(level);
    }

    public List<ReviewNode> getReviewNodes(String url, GrainLevel level) throws Exception {
        if (level == GrainLevel.RAW_DIFF) {
            return new ArrayList<>(getOrComputeRawNodes(url));
        }

        return getOrComputeClusters(url).stream()
                .flatMap(cluster -> cluster.getGraph().vertexSet().stream())
                .collect(Collectors.toList());
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

    public List<RawNode> getOrComputeRawNodes(String url) throws Exception {
        List<RawNode> cached = cacheManager.getRawNodes(url);
        if (cached != null) {
            return cached;
        }

        List<RawNode> rawNodes = RawNode.parse(fetchRawDiff(url));
        cacheManager.putRawNodes(url, rawNodes);
        return rawNodes;
    }

    private List<Narrator.ChapterUnit> getRawDiffChapters(String url) throws Exception {
        List<Narrator.ChapterUnit> cached = cacheManager.getRawDiffChapters(url);
        if (cached != null) {
            return cached;
        }

        List<RawNode> rawNodes = getOrComputeRawNodes(url);
        if (rawNodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> prompts = rawNodes.stream().map(RawNode::prompt).toList();
        List<Narrator.ChapterUnit> chapters = new ArrayList<>();
        for (List<Integer> split : Splitter.createBalancedSplits(prompts)) {
            Narrator.ChapterUnit chapter = new Narrator.ChapterUnit();
            for (Integer index : split) {
                chapter.append(prompts.get(index));
                chapter.addMains(Set.of(rawNodes.get(index)));
            }
            chapters.add(chapter);
        }

        cacheManager.putRawDiffChapters(url, chapters);
        return chapters;
    }

    private String fetchRawDiff(String url) throws Exception {
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

        return response.body();
    }

    private String getHierarchyCacheKey(String url) {
        return "hierarchy:" + url;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }
}
