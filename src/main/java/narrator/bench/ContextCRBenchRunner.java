package narrator.bench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import narrator.langchain.NarrativeRunner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ContextCRBenchRunner {

    private static final String DATASET_PATH = "dataset/contextCRBench";
    private static final String RESULTS_DIR = "results/ContextCRBench";
    private static final String LOG_FILE = "scripts/ollama-proxy/ollama_proxy.log";

    public static void main(String[] args) {
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
                String id = fileName.substring(0, fileName.lastIndexOf('.'));

                try {
                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();

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

                        String rangeUrl = "https://github.com/" + repo + "/compare/" + baseCommit + "..." + submittedCommit;

                        long start = System.currentTimeMillis();
                        String output = NarrativeRunner.run(rangeUrl);
                        long end = System.currentTimeMillis();
                        long timing = end - start;

                        TokenUsage tokens = readAndPurgeLog();

                        BenchResult result = new BenchResult();
                        result.id = id;
                        result.submittedOnCommit = submittedCommit;
                        result.output = output;
                        result.timing = timing;
                        result.tokensIn = tokens.in;
                        result.tokensOut = tokens.out;

                        // Store result JSON
                        Files.write(Paths.get(RESULTS_DIR, id + ".json"), gson.toJson(result).getBytes(StandardCharsets.UTF_8));

                        // Store raw output TXT
                        Files.write(Paths.get(RESULTS_DIR, id + ".txt"), (output != null ? output : "").getBytes(StandardCharsets.UTF_8));
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

    private static TokenUsage readAndPurgeLog() throws IOException {
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

        // Purge the log file
        Files.write(logPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);

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
        String id;
        String submittedOnCommit;
        String output;
        long timing;
        long tokensIn;
        long tokensOut;
    }
}