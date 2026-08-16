package narrator.bench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import narrator.langchain.NarrativeRunner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

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
                        purgeLog();

                        // Check if result already exists for this commit
                        String resultFileName = String.format("%s_%s_%s_%s.json", org, repoName, prNumber, submittedCommit);
                        if (Files.exists(Paths.get(RESULTS_DIR, resultFileName))) {
                            System.out.println("Skipping existing result: " + resultFileName);
                            continue;
                        }

                        String rangeUrl = "https://github.com/" + repo + "/compare/" + baseCommit + "..." + submittedCommit;

                        long start = System.currentTimeMillis();
                        String output = NarrativeRunner.run(rangeUrl);
                        long end = System.currentTimeMillis();
                        long timing = end - start;

                        TokenUsage tokens = readLog();

                        BenchResult result = new BenchResult();
                        result.org = org;
                        result.repo = repoName;
                        result.prNumber = prNumber;
                        result.commit = submittedCommit;
                        result.output = output;
                        result.timing = timing;
                        result.tokensIn = tokens.in;
                        result.tokensOut = tokens.out;

                        // Store result JSON
                        Files.write(Paths.get(RESULTS_DIR, resultFileName), gson.toJson(result).getBytes(StandardCharsets.UTF_8));

                        // Store raw output TXT
                        String outputFileName = String.format("%s_%s_%s_%s.txt", org, repoName, prNumber, submittedCommit);
                        Files.write(Paths.get(RESULTS_DIR, outputFileName), (output != null ? output : "").getBytes(StandardCharsets.UTF_8));
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
        String output;
        long timing;
        long tokensIn;
        long tokensOut;
    }
}