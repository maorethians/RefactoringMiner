package narrator.bench;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import narrator.langchain.NarrativeProcessor;
import narrator.langchain.NarrativeRunner;
import narrator.langchain.prompt.ReviewPrompt;
import org.refactoringminer.astDiff.graph.Node;
import org.refactoringminer.astDiff.graph.NodeType;
import org.refactoringminer.astDiff.graph.cluster.Cluster;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class Benchmark {

    private static final String DATASET_PATH = "dataset/contextCRBench";
    private static final String DETAILED_PRS_PATH = "dataset/detailedPRs";
    private static final String MIN_CREATED_AT = "2024-03-01T00:00:00Z";
    private static final String RESULTS_DIR = "results/ContextCRBench";
    private static final String LOG_FILE = "scripts/ollama-proxy/ollama_proxy.log";

    public static void main(String[] args) {
        runBenchmark();
    }

    private static void runBenchmark() {
        try {
            File resultsDir = new File(RESULTS_DIR);
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }

            File datasetDir = new File(DATASET_PATH);
            File[] files = datasetDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) {
                System.err.println("Dataset directory not found or empty: " + DATASET_PATH);
                return;
            }

            Gson gson = new Gson();

            for (File file : files) {
                String fileName = file.getName();

                try {
                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();

                    // Extract org, repo, and prNumber from fileName (format: org_repo_pr.json)
                    String nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
                    String[] parts = nameWithoutExt.split("_");
                    if (parts.length < 3) {
                        System.err.println("Invalid filename format: " + fileName);
                        continue;
                    }
                    String org = parts[0];
                    String repoName = parts[1];
                    String prNumber = parts[2];

                    // Filter by creation date using detailed PR
                    String detailedPrFileName = String.format("%s_%s_%s.json", org, repoName, prNumber);
                    File detailedPrFile = new File(DETAILED_PRS_PATH, detailedPrFileName);
                    if (detailedPrFile.exists()) {
                        String detailedContent = new String(Files.readAllBytes(detailedPrFile.toPath()), StandardCharsets.UTF_8);
                        JsonObject detailedPrJson = JsonParser.parseString(detailedContent).getAsJsonObject();
                        String createdAt = detailedPrJson.get("created_at").getAsString();
                        if (createdAt.compareTo(MIN_CREATED_AT) < 0) {
                            System.out.println("Skipping PR created before " + MIN_CREATED_AT + ": " + detailedPrFileName);
                            continue;
                        }
                    } else {
                        System.err.println("Detailed PR not found: " + detailedPrFileName);
                    }

                    // Group review comments by submitted_on_commit
                    Map<String, List<JsonObject>> commitGroups = new HashMap<>();
                    if (json.has("reviews")) {
                        json.getAsJsonArray("reviews").forEach(element -> {
                            JsonObject review = element.getAsJsonObject();
                            if (review.has("submitted_on_commit")) {
                                String sha = review.get("submitted_on_commit").getAsString();
                                commitGroups.computeIfAbsent(sha, k -> new ArrayList<>()).add(review);
                            }
                        });
                    }

                    String repo = json.get("full_name").getAsString();
                    String baseCommit = json.get("base_commit").getAsString();
                    for (Map.Entry<String, List<JsonObject>> entry : commitGroups.entrySet()) {
                        String submittedCommit = entry.getKey();

                        // Check if result already exists for this commit
                        String resultFileName = String.format("%s_%s_%s_%s.json", org, repoName, prNumber, submittedCommit);
                        if (Files.exists(Paths.get(RESULTS_DIR, resultFileName))) {
                            System.out.println("Skipping existing result: " + resultFileName);
                            continue;
                        }

                        String rangeUrl = "https://github.com/" + repo + "/compare/" + baseCommit + "..." + submittedCommit;
                        System.out.println(rangeUrl);

                        Trial bestTrial = null;
                        for (int trialIndex = 0; trialIndex < 3; trialIndex++) {
                            purgeLog();

                            long start = System.currentTimeMillis();
                            NarrativeProcessor.NarrativeProcessResult narrativeResult = NarrativeRunner.run(rangeUrl);
                            long end = System.currentTimeMillis();
                            long timing = end - start;

                            Set<GeneratedCommentNodes> generatedCommentsNodes = new HashSet<>();
                            for (ReviewPrompt.ReviewComment generatedComment : narrativeResult.comments()) {
                                List<Node> commentNodes = generatedComment.hunkIds().stream()
                                        .map(promptId -> findNode(narrativeResult.clusters(), promptId)).toList();
                                if (commentNodes.stream().anyMatch(Objects::isNull)) {
                                    throw new Exception("Hallucinated id detected");
                                }

                                Set<Node> validNodes = commentNodes.stream().filter(Objects::nonNull).collect(Collectors.toSet());
                                if (validNodes.isEmpty()) {
                                    throw new Exception("No valid nodes found");
                                }

                                generatedCommentsNodes.add(new GeneratedCommentNodes(generatedComment.text(), validNodes));
                            }

                            Map<JsonObject, Set<GeneratedCommentNodes>> groundTruthGeneratedComments = new HashMap<>();
                            for (JsonObject groundTruthComment : entry.getValue()) {
                                String path = groundTruthComment.get("path").getAsString();
                                String side = groundTruthComment.get("side").getAsString();
                                int line = groundTruthComment.get("original_line").getAsInt();
                                Integer startLine = groundTruthComment.get("original_start_line").isJsonNull() ?
                                        null : groundTruthComment.get("original_start_line").getAsInt();
                                Set<Node> overlappingNodes = findNodes(narrativeResult.clusters(), path, side, line, startLine);

                                groundTruthGeneratedComments.put(groundTruthComment, generatedCommentsNodes.stream()
                                        .filter(generatedCommentNodes -> generatedCommentNodes.nodes().stream().anyMatch(overlappingNodes::contains))
                                        .collect(Collectors.toSet()));
                            }
                            long coveredGroundTruth = groundTruthGeneratedComments.values().stream().filter(gc -> !gc.isEmpty()).count();
                            long uncoveredGroundTruth = groundTruthGeneratedComments.values().stream().filter(Set::isEmpty).count();

                            double recall = (double) coveredGroundTruth / groundTruthGeneratedComments.size();
                            System.out.println("Trial " + (trialIndex + 1) + " recall: " + recall);

                            Set<Node> allHunkNodes = narrativeResult.clusters().stream()
                                    .map(cluster -> cluster.getGraph().vertexSet().stream().filter(Node::isBase).collect(Collectors.toSet()))
                                    .flatMap(Set::stream).collect(Collectors.toSet());
                            Set<Node> coveredHunkNodes = generatedCommentsNodes.stream()
                                    .map(generatedCommentNodes -> generatedCommentNodes.nodes.stream().filter(Node::isBase).collect(Collectors.toSet()))
                                    .flatMap(Set::stream).collect(Collectors.toSet());
                            Set<Node> uncoveredHunkNodes = allHunkNodes.stream().filter(hunkNode -> !coveredHunkNodes.contains(hunkNode))
                                    .collect(Collectors.toSet());

                            double coverageMetric = (double) coveredHunkNodes.size() / allHunkNodes.size();

                            TokenUsage tokens = readLog();

                            BenchResult result = new BenchResult();
                            result.org = org;
                            result.repo = repoName;
                            result.prNumber = prNumber;
                            result.commit = submittedCommit;
                            result.generatedCommentsNodes = generatedCommentsNodes.stream().map(GeneratedCommentNodes::stringify).toList();
                            result.groundTruthGeneratedCommentsNodes = groundTruthGeneratedComments.entrySet().stream()
                                    .map(e -> new GroundTruthGeneratedCommentsNodes(e.getKey(), e.getValue().stream().map(GeneratedCommentNodes::stringify).toList())).toList();
                            result.uncoveredGroundTruth = uncoveredGroundTruth;
                            result.coveredGroundTruth = coveredGroundTruth;
                            result.uncoveredHunkNodes = uncoveredHunkNodes.size();
                            result.coveredHunkNodes = coveredHunkNodes.size();
                            result.timing = timing;
                            result.tokensIn = tokens.in;
                            result.tokensOut = tokens.out;

                            Trial trial = new Trial(result, recall, coverageMetric, narrativeResult.content());
                            if (bestTrial == null || recall > bestTrial.recall
                                    || (recall == bestTrial.recall && coverageMetric < bestTrial.coverageMetric)) {
                                bestTrial = trial;
                            }
                        }

                        // Store result JSON
                        Files.write(Paths.get(RESULTS_DIR, resultFileName), gson.toJson(bestTrial.result).getBytes(StandardCharsets.UTF_8));

                        // Store raw output TXT
                        String outputFileName = String.format("%s_%s_%s_%s.txt", org, repoName, prNumber, submittedCommit);
                        Files.write(Paths.get(RESULTS_DIR, outputFileName), bestTrial.content.getBytes(StandardCharsets.UTF_8));
                    }

                } catch (Exception e) {
                    System.err.println("Error processing " + fileName + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            System.out.println("Benchmarking complete.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void purgeLog() throws IOException {
        Path logPath = Paths.get(LOG_FILE);
        if (Files.exists(logPath)) {
            Files.write(logPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static TokenUsage readLog() throws IOException {
        Path logPath = Paths.get(LOG_FILE);
        if (!Files.exists(logPath)) {
            return new TokenUsage(0, 0);
        }

        List<String> lines = Files.readAllLines(logPath);
        if (lines.isEmpty()) {
            return new TokenUsage(0, 0);
        }

        String lastLine = lines.get(lines.size() - 1);
        String[] parts = lastLine.trim().split("\\s+");
        long in = 0, out = 0;
        if (parts.length >= 2) {
            try {
                in = Long.parseLong(parts[0]);
                out = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                System.err.println("Failed to parse tokens from log line: " + lastLine);
            }
        }

        return new TokenUsage(in, out);
    }

    @Nullable
    private static Node findNode(List<Cluster> clusters, String promptId) {
        return clusters.stream()
                .map(cluster -> cluster.findNode(promptId)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static Set<Node> findNodes(List<Cluster> clusters, String side, String path, int line, @Nullable Integer startLine) {
        return clusters.stream()
                .map(cluster -> cluster.findNodes(path, side, line, startLine)).flatMap(Set::stream)
                .filter(node -> !node.getNodeType().equals(NodeType.LOCATION_CONTEXT))
                .collect(Collectors.toSet());
    }

    private static class Trial {
        BenchResult result;
        double recall;
        double coverageMetric;
        String content;

        Trial(BenchResult result, double recall, double coverageMetric, String content) {
            this.result = result;
            this.recall = recall;
            this.coverageMetric = coverageMetric;
            this.content = content;
        }
    }

    private static class TokenUsage {
        long in;
        long out;

        TokenUsage(long in, long out) {
            this.in = in;
            this.out = out;
        }
    }

    private static class BenchResult {
        String org;
        String repo;
        String prNumber;
        String commit;
        List<StringifiedGeneratedCommentNodes> generatedCommentsNodes;
        List<GroundTruthGeneratedCommentsNodes> groundTruthGeneratedCommentsNodes;
        long coveredGroundTruth;
        long uncoveredGroundTruth;
        long coveredHunkNodes;
        long uncoveredHunkNodes;
        long timing;
        long tokensIn;
        long tokensOut;
    }

    private record GroundTruthGeneratedCommentsNodes(JsonObject groundTruth, List<StringifiedGeneratedCommentNodes> generatedCommentsNodes) {}

    private record GeneratedCommentNodes(String comment, Set<Node> nodes) {
        public StringifiedGeneratedCommentNodes stringify() {
            JsonArray stringifiedNodes = new JsonArray();
            for (Node node : nodes) {
                stringifiedNodes.add(node.stringify());
            }

            return new StringifiedGeneratedCommentNodes(comment, stringifiedNodes);
        }
    }
    private record StringifiedGeneratedCommentNodes(String comment, JsonArray nodes) {}
}