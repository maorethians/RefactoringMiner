package narrator.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import narrator.Driver;
import narrator.graph.Edge;
import narrator.graph.Node;
import narrator.graph.cluster.Cluster;
import narrator.graph.cluster.Clusterer;
import narrator.graph.cluster.traverse.*;
import narrator.mcp.html.NarrativeHtmlGenerator;
import org.jgrapht.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class McpHandler {
    private static final Logger logger = LoggerFactory.getLogger(McpHandler.class);
    private static final CacheManager cacheManager = new CacheManager();

    private static void sendToolError(JsonObject response, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        response.add("result", JsonNull.INSTANCE);
    }

    private static void sendMethodNotFound(JsonObject response) {
        JsonObject error = new JsonObject();
        error.addProperty("code", -32601);
        error.addProperty("message", "Method not found");
        response.add("error", error);
        response.add("result", JsonNull.INSTANCE);
    }

    public JsonObject handle(JsonObject request) {
        logger.debug("Handling MCP request: {}", request);
        String method = request.get("method").getAsString();
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");

        if (request.has("id")) {
            response.add("id", request.get("id"));
        }

        switch (method) {
            case "initialize":
                handleInitialize(response);
                break;
            case "tools/list":
                handleListTools(response);
                break;
            case "tools/call":
                handleCallTool(request, response);
                break;
            default:
                sendMethodNotFound(response);
        }

        return response;
    }

    private void handleInitialize(JsonObject response) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "RefactoringMiner MCP");
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);

        response.add("result", result);
    }

    private void handleListTools(JsonObject response) {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();

        tools.add(createToolDefinition("init_narrative",
                "Prepares the narrative for a commit or pull request and returns its overview, including the total number of chapters for each grain level. It also generates an HTML page demonstrating the narrative and returns its path.\n\nParameters:\n- url: The commit or PR URL\n- mode: 'manual' (default) - asks the user to choose a GrainLevel; 'automatic' - provides metadata for the agent to decide the GrainLevel automatically.",
                "url", "mode"));
        tools.add(createToolDefinition("get_next_chapter",
                "Retrieves the next single chapter in the narrative for the specified grain level. This tool is designed for MANUAL mode. For each chapter retrieved, perform the requested task (e.g., review, search, analysis) for that specific content, then ask the user if they would like to proceed to the next chapter. When the end of the narrative is reached, provide a final comprehensive wrap-up of the task.",
                "url", "grainLevel"));
        tools.add(createToolDefinition("get_next_batch",
                "Retrieves a batch of chapters in the narrative for the specified grain level. This tool is designed for AUTOMATIC mode. Process each batch of chapters toward the requested task. IMPORTANT: You must continue calling this tool sequentially until the end of the narrative is reached; do not stop or synthesize a final result until the tool explicitly indicates that no more chapters remain.",
                "url", "grainLevel"));
        result.add("tools", tools);
        response.add("result", result);
    }

    private JsonObject createToolDefinition(String name, String description, String... paramNames) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();

        for (String paramName : paramNames) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "string");
            properties.add(paramName, prop);
            required.add(paramName);
        }

        inputSchema.add("properties", properties);
        inputSchema.add("required", required);

        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private void handleCallTool(JsonObject request, JsonObject response) {
        JsonObject params = request.getAsJsonObject("params");
        if (params == null) {
            sendToolError(response, -32602, "Missing params");
            return;
        }

        if (!params.has("name")) {
            sendToolError(response, -32602, "Missing tool name");
            return;
        }
        String toolName = params.get("name").getAsString();

        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : null;
        if (arguments == null) {
            sendToolError(response, -32602, "Missing arguments");
            return;
        }

        try {
            String resultValue = executeTool(toolName, arguments);

            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", resultValue);
            content.add(textContent);
            result.add("content", content);

            response.add("result", result);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid arguments for tool call: {}", e.getMessage());
            sendToolError(response, -32602, e.getMessage());
        } catch (Exception e) {
            logger.error("Internal error during tool call", e);
            sendToolError(response, -32603, "Internal error: " + e.getMessage());
        }
    }

    private String executeTool(String toolName, JsonObject arguments) throws Exception {
        if (!arguments.has("url")) {
            throw new IllegalArgumentException("Missing required argument: url");
        }
        String url = arguments.get("url").getAsString();

        if ("init_narrative".equals(toolName)) {
            String mode = arguments.has("mode") ? arguments.get("mode").getAsString() : "manual";
            return initNarrative(url, mode);
        } else if ("get_next_chapter".equals(toolName)) {
            if (!arguments.has("grainLevel")) {
                throw new IllegalArgumentException("Missing required argument: grainLevel");
            }
            return getNextChapter(url, arguments.get("grainLevel").getAsString());
        } else if ("get_next_batch".equals(toolName)) {
            if (!arguments.has("grainLevel")) {
                throw new IllegalArgumentException("Missing required argument: grainLevel");
            }
            return getNextBatch(url, arguments.get("grainLevel").getAsString());
        } else {
            throw new UnsupportedOperationException("Unknown tool: " + toolName);
        }
    }

    private String getHierarchyCacheKey(String url) {
        return "hierarchy:" + url;
    }

    private String getNextBatch(String url, String grainLevelStr) throws Exception {
        GrainLevel level;
        try {
            level = GrainLevel.valueOf(grainLevelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid grainLevel: " + grainLevelStr + ". Valid values are: " + java.util.Arrays.toString(GrainLevel.values()));
        }

        TraversalPattern root = cacheManager.getHierarchy(getHierarchyCacheKey(url));
        if (root == null) {
            return "No narrative initialized for this URL. Please call init_narrative first.";
        }
        Narrator narrator = root.getNarrator();
        List<Cluster> clusters = getOrComputeClusters(url);
        List<String> flatChapters;
        if (level == GrainLevel.RAW_DIFF) {
            flatChapters = getRawDiffChunks(url, narrator, clusters);
        } else {
            flatChapters = narrator.getFlatChapters(level, clusters);
        }

        int startProgress = narrator.getProgress(level);
        if (startProgress >= flatChapters.size()) {
            return "[End of Narrative] All chapters for grain level " + level + " have been read.";
        }

        // Since Narrator already produces chapters balanced to the threshold,
        // we just return the next single flat chapter.
        String content = flatChapters.get(startProgress);
        String header = String.format("[Chapter %d of %d]\n", startProgress + 1, flatChapters.size());
        narrator.incrementProgress(level);

        StringBuilder output = new StringBuilder();
        output.append(header).append(content);

        if (startProgress + 1 < flatChapters.size()) {
            output.append("\n\nReminder: Process these chapters toward the requested task and then call get_next_batch again to continue. DO NOT stop until you reach the end of the narrative.");
        } else {
            output.append("\n\n[End of Narrative] All chapters for grain level " + level + " have been read. You may now provide a final comprehensive wrap-up of the task.");
        }

        return output.toString();
    }

    private String getNextChapter(String url, String grainLevelStr) throws Exception {
        GrainLevel level;
        try {
            level = GrainLevel.valueOf(grainLevelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid grainLevel: " + grainLevelStr + ". Valid values are: " + java.util.Arrays.toString(GrainLevel.values()));
        }

        TraversalPattern root = cacheManager.getHierarchy(getHierarchyCacheKey(url));
        if (root == null) {
            return "No narrative initialized for this URL. Please call init_narrative first.";
        }
        Narrator narrator = root.getNarrator();
        List<Cluster> clusters = getOrComputeClusters(url);
        List<String> flatChapters;
        if (level == GrainLevel.RAW_DIFF) {
            flatChapters = getRawDiffChunks(url, narrator, clusters);
        } else {
            flatChapters = narrator.getFlatChapters(level, clusters);
        }

        int progress = narrator.getProgress(level);
        if (progress >= flatChapters.size()) {
            return "[End of Narrative] All chapters for grain level " + level + " have been read.";
        }

        String content = flatChapters.get(progress);
        String header = String.format("[Chapter %d of %d]\n", progress + 1, flatChapters.size());
        narrator.incrementProgress(level);

        // Update HTML page to expand only the current chapter
        // We use the progress in the flat list to sync the HTML page.
        NarrativeHtmlGenerator generator = cacheManager.getHtmlGenerator(url);
        if (generator != null) {
            try {
                generator.generateGrainLevelPage(level, clusters, progress);
            } catch (Exception e) {
                logger.error("Failed to update narrative HTML page", e);
            }
        }

        StringBuilder output = new StringBuilder();
        output.append(header).append(content);
        output.append("\n\n");

        if (progress + 1 < flatChapters.size()) {
            output.append("Reminder: Perform the requested task for this chapter and ask the user if they would like to proceed to the next chapter.");
        } else {
            output.append("Reminder: Perform the requested task for this final chapter.");
            output.append("\n\n[End of Narrative] All chapters for grain level " + level + " have been read. You may now provide a final comprehensive wrap-up of the task.");
        }

        return output.toString();
    }

    private String initNarrative(String url, String mode) throws Exception {
        TraversalPattern root = getOrComputeHierarchy(url);
        if (root == null) {
            return "No changes found to narrate.";
        }

        Narrator narrator = root.getNarrator();

        List<Cluster> clusters = getOrComputeClusters(url);
        NarrativeHtmlGenerator generator = new NarrativeHtmlGenerator(url, narrator);
        generator.generateAll(clusters);
        cacheManager.putHtmlGenerator(url, generator);

        StringBuilder summary = new StringBuilder();
        summary.append("Narrative initialized.\n\n");

        if ("automatic".equalsIgnoreCase(mode)) {
            summary.append("Mode: Automatic. Use the following metadata to choose the most appropriate GrainLevel:\n\n");
            summary.append(String.format("%-20s | %-10s | %-18s | %-18s\n", "GrainLevel", "Chapters", "Avg Chapter Lines", "Max Chapter Lines"));
            summary.append("-------------------------------------------------------------------------------------------------\n");

            for (GrainLevel level : GrainLevel.values()) {
                List<String> flatChapters;
                if (level == GrainLevel.RAW_DIFF) {
                    flatChapters = getRawDiffChunks(url, narrator, clusters);
                } else {
                    flatChapters = narrator.getFlatChapters(level, clusters);
                }
                int count = flatChapters.size();

                double totalLines = 0;
                double maxLines = 0;

                for (String content : flatChapters) {
                    int lines = content.split("\n").length;
                    totalLines += lines;
                    if (lines > maxLines) maxLines = lines;
                }

                double avgLines = count > 0 ? totalLines / count : 0;

                summary.append(String.format("%-20s | %-10d | %-10.1f | %-10.1f\n", level, count, avgLines, maxLines));
            }
            summary.append("\n\nPlease analyze the metadata and decide which GrainLevel to use for the review.");
        } else {
            summary.append("You must now guide the user to start the narration. Please follow these steps:\n");
            summary.append("1. Provide the user with the path to the narrative overview HTML page for a high-level overview: ").append(generator.getOverviewPath()).append("\n");
            summary.append("2. List the available GrainLevels and their respective chapter counts from the list below, explaining that they should choose one to start the detailed narration.\n\n");
            summary.append("Available GrainLevels and their chapter counts:\n");

            for (GrainLevel level : GrainLevel.values()) {
                int count = (level == GrainLevel.RAW_DIFF)
                        ? getRawDiffChunks(url, narrator, clusters).size()
                        : narrator.getFlatChapters(level, clusters).size();
                summary.append("- ").append(level).append(" (").append(level.getDescription()).append("): ").append(count).append(" chapters\n");
            }

            summary.append("\n3. Ask the user which GrainLevel they would like to use to proceed.");
        }

        return summary.toString();
    }


    private List<String> getRawDiffChunks(String url, Narrator narrator, List<Cluster> clusters) throws Exception {
        List<String> cached = cacheManager.getRawDiffChunks(url);
        if (cached != null) {
            return cached;
        }

        // 1. Determine number of chunks from FILE level
        List<String> fileChapters = narrator.getFlatChapters(GrainLevel.FILE, clusters);
        int numChunks = fileChapters.size();
        if (numChunks == 0) {
            return Collections.emptyList();
        }

        // 2. Fetch raw diff
        String rawDiffUrl = url;
        if ((url.contains("/pull/") || url.contains("/pr/") || url.contains("/commit/")) && !url.endsWith(".diff")) {
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

        // 3. Split into balanced chunks (by line)
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

        cacheManager.putRawDiffChunks(url, chunks);
        return chunks;
    }

    private List<Cluster> getOrComputeClusters(String url) throws Exception {
        List<Cluster> cached = cacheManager.getClusters(url);
        if (cached != null) {
            return cached;
        }

        Graph<Node, Edge> graph = loadGraph(url);
        List<Cluster> clusters = new Clusterer(graph).getClusters();
        cacheManager.putClusters(url, clusters);
        return clusters;
    }

    private TraversalPattern getOrComputeHierarchy(String url) throws Exception {
        String cacheKey = getHierarchyCacheKey(url);
        TraversalPattern cached = cacheManager.getHierarchy(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<Cluster> clusters = getOrComputeClusters(url);
        List<TraversalPattern> finalHierarchy = new java.util.ArrayList<>();

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
        } else {
            return Driver.getCommitGraph(url);
        }
    }

}
