package narrator.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import narrator.graph.cluster.Cluster;
import narrator.graph.cluster.traverse.Leaf;
import narrator.graph.cluster.traverse.TraversalPattern;
import narrator.graph.cluster.traverse.Narrator;
import narrator.mcp.html.NarrativeHtmlGenerator;

public class CacheManager {
    private final Map<String, List<Cluster>> clustersCache = new ConcurrentHashMap<>();
    private final Map<String, TraversalPattern> hierarchyCache = new ConcurrentHashMap<>();

    private final Map<String, NarrativeHtmlGenerator> htmlGeneratorsCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> rawDiffsCache = new ConcurrentHashMap<>();

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

    public List<String> getRawDiffChunks(String url) {
        return rawDiffsCache.get(url);
    }

    public void putRawDiffChunks(String url, List<String> chunks) {
        rawDiffsCache.put(url, chunks);
    }

    public void clear() {
        clustersCache.clear();
        hierarchyCache.clear();
    }
}
