package narrator;

import com.google.gson.*;
import narrator.Driver;
import narrator.graph.Edge;
import narrator.graph.Node;
import narrator.graph.cluster.Cluster;
import narrator.graph.cluster.Clusterer;
import narrator.graph.cluster.traverse.TraversalEngine;
import narrator.graph.cluster.traverse.TraversalPattern;
import narrator.json.Stringifier;
import org.jgrapht.Graph;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.refactoringminer.astDiff.models.ProjectASTDiff;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestNarratorOracle {

    private static final String ORACLE_DATA_PATH = "src/test/resources/narrator/oracle/data.json";
    private static final String EXPECTED_DIR = "src/test/resources/narrator/oracle/expected/";
    private static final String RM_CACHE_DIR = "src/test/resources/oracle/commits";

    static Stream<TestCase> testCases() throws IOException {
        String content = Files.readString(Path.of(ORACLE_DATA_PATH));
        Gson gson = new Gson();
        List<TestCase> cases = gson.fromJson(content, new com.google.gson.reflect.TypeToken<List<TestCase>>(){}.getType());
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("testCases")
    public void testNarratorPipeline(TestCase testCase) throws Exception {
        // 1. Setup RefactoringMiner to use local cache
        GitHistoryRefactoringMinerImpl rm = new GitHistoryRefactoringMinerImpl();

        // Use diffAtCommitWithGitHubAPI which implements the cache-first logic
        ProjectASTDiff projectASTDiff = rm.diffAtCommitWithGitHubAPI(testCase.repository, testCase.sha1, new File(RM_CACHE_DIR));

        if (projectASTDiff == null) {
            Assertions.fail("Failed to produce ProjectASTDiff for " + testCase.sha1);
        }

        // 2. Narrator Graph Generation
        Graph<Node, Edge> graph = Driver.getGraph(projectASTDiff);

        // 3. Cluster Generation
        Clusterer clusterer = new Clusterer(graph);
        List<Cluster> clusters = clusterer.getClusters();

        // --- Test Clusters JSON ---
        JsonArray actualClusters = new JsonArray();
        clusters.forEach(cluster -> actualClusters.add(Stringifier.graph(cluster.getGraph())));

        String expectedClustersPath = EXPECTED_DIR + testCase.repoName + "-" + testCase.sha1 + "-clusters.json";
        verifyJsonArray(actualClusters, expectedClustersPath, "Clusters");

        // --- Test Hierarchy JSON ---
        List<TraversalPattern> patterns = clusters.stream()
                .map(TraversalEngine::new)
                .map(TraversalEngine::get)
                .filter(Objects::nonNull)
                .toList();

        JsonObject actualHierarchy = Stringifier.hierarchy(patterns);
        String expectedHierarchyPath = EXPECTED_DIR + testCase.repoName + "-" + testCase.sha1 + "-hierarchy.json";
        verifyJsonObject(actualHierarchy, expectedHierarchyPath, "Hierarchy");
    }


    private void verifyJsonArray(JsonArray actual, String expectedPath, String label) throws IOException {
        if (!Files.exists(Path.of(expectedPath))) {
            // Optional: write actual to disk if expected is missing (generator mode)
            // Files.writeString(Path.of(expectedPath), actual.toString());
            // throw new FileNotFoundException("Expected file not found: " + expectedPath);
            Assertions.fail(label + " expected file missing: " + expectedPath);
        }

        JsonArray expected = new Gson().fromJson(Files.readString(Path.of(expectedPath)), JsonArray.class);

        Assertions.assertEquals(expected.size(), actual.size(), label + " size mismatch");

        // Semantic comparison of each graph in the array
        for (int i = 0; i < expected.size(); i++) {
            verifyJsonObject(actual.get(i).getAsJsonObject(), expected.get(i).getAsJsonObject(), label + " Graph " + i);
        }
    }

    private void verifyJsonObject(JsonObject actual, JsonObject expected, String label) {
        // Compare Nodes
        JsonArray actualNodes = actual.getAsJsonArray("nodes");
        JsonArray expectedNodes = expected.getAsJsonArray("nodes");
        verifyNodes(actualNodes, expectedNodes, label);

        // Compare Edges
        JsonArray actualEdges = actual.getAsJsonArray("edges");
        JsonArray expectedEdges = expected.getAsJsonArray("edges");
        verifyEdges(actualEdges, expectedEdges, label);
    }

    private void verifyNodes(JsonArray actual, JsonArray expected) {
        Assertions.assertEquals(expected.size(), actual.size(), "Node count mismatch");

        Set<String> actualNodeStrings = new HashSet<>();
        actual.forEach(n -> actualNodeStrings.add(normalizeNode(n.getAsJsonObject())));

        Set<String> expectedNodeStrings = new HashSet<>();
        expected.forEach(n -> expectedNodeStrings.add(normalizeNode(n.getAsJsonObject())));

        Assertions.assertEquals(expectedNodeStrings, actualNodeStrings, "Nodes content mismatch");
    }

    private void verifyEdges(JsonArray actual, JsonArray expected) {
        Assertions.assertEquals(expected.size(), actual.size(), "Edge count mismatch");

        Set<String> actualEdgeStrings = new HashSet<>();
        actual.forEach(e -> actualEdgeStrings.add(normalizeEdge(e.getAsJsonObject())));

        Set<String> expectedEdgeStrings = new HashSet<>();
        expected.forEach(e -> expectedEdgeStrings.add(normalizeEdge(e.getAsJsonObject())));

        Assertions.assertEquals(expectedEdgeStrings, actualEdgeStrings, "Edges content mismatch");
    }

    private String normalizeNode(JsonObject node) {
        // id, nodeType, and aggregatorIds (sorted)
        String id = node.get("id").getAsString();
        String type = node.get("nodeType").getAsString();
        JsonArray aggs = node.getAsJsonArray("aggregatorIds");
        List<String> aggList = new ArrayList<>();
        aggs.forEach(a -> aggList.add(a.getAsString()));
        Collections.sort(aggList);
        return id + "|" + type + "|" + aggList;
    }

    private String normalizeEdge(JsonObject edge) {
        return edge.get("sourceId").getAsString() + "->" +
               edge.get("targetId").getAsString() + ":" +
               edge.get("type").getAsString();
    }

    public static class TestCase {
        public String repoName;
        public String repository;
        public String sha1;
    }

}
