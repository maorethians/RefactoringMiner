package narrator.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import narrator.mcp.html.NarrativeHtmlGenerator;
import org.refactoringminer.astDiff.graph.RawNode;
import org.refactoringminer.astDiff.graph.cluster.Cluster;
import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;
import org.refactoringminer.astDiff.graph.cluster.traverse.TraversalPattern;

public class CacheManager {
    private final Map<String, List<Cluster>> clustersCache = new ConcurrentHashMap<>();
    private final Map<String, TraversalPattern> hierarchyCache = new ConcurrentHashMap<>();

    private final Map<String, NarrativeHtmlGenerator> htmlGeneratorsCache = new ConcurrentHashMap<>();
    private final Map<String, List<RawNode>> rawNodesCache = new ConcurrentHashMap<>();
    private final Map<String, List<Narrator.ChapterUnit>> rawDiffChaptersCache = new ConcurrentHashMap<>();

    public List<Cluster> getClusters(String url) {
        return clustersCache.get(url);
    }

    public void putClusters(String url, List<Cluster> clusters) {
        clustersCache.put(url, clusters);
    }

    public TraversalPattern getHierarchy(String url) {
        return hierarchyCache.get(url);
    }


    public void putHierarchy(String url, TraversalPattern hierarchy) {
        hierarchyCache.put(url, hierarchy);
    }



    public NarrativeHtmlGenerator getHtmlGenerator(String url) {
        return htmlGeneratorsCache.get(url);
    }

    public void putHtmlGenerator(String url, NarrativeHtmlGenerator generator) {
        htmlGeneratorsCache.put(url, generator);
    }

    public List<RawNode> getRawNodes(String url) {
        return rawNodesCache.get(url);
    }

    public void putRawNodes(String url, List<RawNode> rawNodes) {
        rawNodesCache.put(url, rawNodes);
    }

    public List<Narrator.ChapterUnit> getRawDiffChapters(String url) {
        return rawDiffChaptersCache.get(url);
    }

    public void putRawDiffChapters(String url, List<Narrator.ChapterUnit> chapters) {
        rawDiffChaptersCache.put(url, chapters);
    }

    public void clear() {
        clustersCache.clear();
        hierarchyCache.clear();
    }
}
