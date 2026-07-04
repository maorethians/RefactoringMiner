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
import java.util.List;

public class McpHandler {
    private static final Logger logger = LoggerFactory.getLogger(McpHandler.class);
    private static final CacheManager cacheManager = new CacheManager();
    private final int THRESHOLD = 200;

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

    private List<List<NarrativeElement>> createBalancedSplits(List<NarrativeElement> elements) {
        int totalLines = elements.stream().mapToInt(NarrativeElement::lineCount).sum();
        if (totalLines <= THRESHOLD) {
            return List.of(elements);
        }

        for (int n = 2; n <= elements.size(); n++) {
            List<List<NarrativeElement>> splits = splitIntoN(elements, n);
            boolean anyBelow = splits.stream().anyMatch(s -> s.stream().mapToInt(NarrativeElement::lineCount).sum() <= THRESHOLD);
            if (anyBelow) {
                return splits;
            }
        }
        return List.of(elements);
    }

    private List<List<NarrativeElement>> splitIntoN(List<NarrativeElement> elements, int n) {
        List<List<NarrativeElement>> splits = new ArrayList<>();
        int totalLines = elements.stream().mapToInt(NarrativeElement::lineCount).sum();
        double target = (double) totalLines / n;

        int currentStart = 0;
        for (int i = 0; i < n - 1; i++) {
            int bestEnd = currentStart;
            double minDiff = Double.MAX_VALUE;
            double currentSum = 0;

            for (int j = currentStart; j < elements.size(); j++) {
                currentSum += elements.get(j).lineCount();
                double diff = Math.abs(currentSum - target);
                if (diff < minDiff) {
                    minDiff = diff;
                    bestEnd = j + 1;
                } else {
                    break;
                }
            }
            splits.add(new ArrayList<>(elements.subList(currentStart, bestEnd)));
            currentStart = bestEnd;
        }
        splits.add(new ArrayList<>(elements.subList(currentStart, elements.size())));
        return splits;
    }

    private String buildSplitContent(List<NarrativeElement> elements, int chapterIdx, int totalChapters, int splitIdx, int totalSplits) {
        StringBuilder sb = new StringBuilder();
        if (totalSplits > 1) {
            sb.append(String.format("[Chapter %d of %d - Split %d of %d]\n\n",
                    chapterIdx, totalChapters, splitIdx, totalSplits));
        } else {
            sb.append(String.format("[Chapter %d of %d]\n", chapterIdx, totalChapters));
        }
        sb.append(String.join("\n", elements.stream().map(NarrativeElement::content).toList()));
        return sb.toString();
    }

    private int getChapterLines(List<TraversalPattern> chapters, int index, List<Cluster> clusters, GrainLevel level) {
        TraversalPattern chapter = chapters.get(index);
        Cluster cluster = findClusterForNode(chapter.getLead(), clusters);
        if (cluster == null) return 0;
        List<TraversalPattern> filterPatterns = index > 0 ? chapters.subList(0, index) : java.util.Collections.emptyList();
        String content = chapter.extended(cluster.getGraph(), level, filterPatterns);
        return content.split("\n").length;
    }

    private Cluster findClusterForNode(Node node, List<Cluster> clusters) {
        if (clusters.isEmpty()) {
            return null;
        }

        for (Cluster cluster : clusters) {
            if (cluster.getGraph().vertexSet().contains(node)) {
                return cluster;
            }
        }

        return null;
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
        List<TraversalPattern> chapters = narrator.getNarrative(level);
        if (chapters == null) {
            return "Narrative state lost. Please call init_narrative again.";
        }

        int startProgress = narrator.getProgress(level);
        if (startProgress >= chapters.size()) {
            return "[End of Narrative] All chapters for grain level " + level + " have been read.";
        }

        List<Cluster> clusters = getOrComputeClusters(url);

        // 1. Handle resuming a split chapter
        if (narrator.getSubChapterProgress(level) > 0) {
            TraversalPattern currentChapter = chapters.get(startProgress);
            Cluster cluster = findClusterForNode(currentChapter.getLead(), clusters);
            if (cluster == null) {
                narrator.setSubChapterProgress(level, 0);
                narrator.incrementProgress(level);
                return getNextBatch(url, grainLevelStr);
            }

            List<TraversalPattern> filterPatterns = startProgress > 0 ? chapters.subList(0, startProgress) : java.util.Collections.emptyList();
            List<NarrativeElement> elements = (currentChapter instanceof AggregatorPattern agg)
                    ? agg.getElements(cluster.getGraph(), filterPatterns)
                    : java.util.Collections.emptyList();

            List<List<NarrativeElement>> splits = createBalancedSplits(elements);
            int splitIdx = narrator.getSubChapterProgress(level);

            if (splitIdx > splits.size()) {
                narrator.setSubChapterProgress(level, 0);
                narrator.incrementProgress(level);
                return getNextBatch(url, grainLevelStr);
            }

            String content = buildSplitContent(splits.get(splitIdx - 1), startProgress + 1, chapters.size(), splitIdx, splits.size());
            narrator.setSubChapterProgress(level, splitIdx + 1);
            if (splitIdx >= splits.size()) {
                narrator.setSubChapterProgress(level, 0);
                narrator.incrementProgress(level);
            }

            StringBuilder output = new StringBuilder();
            output.append(content);
            if (startProgress + 1 < chapters.size() || narrator.getSubChapterProgress(level) > 0) {
                output.append("\n\nReminder: Process these chapters toward the requested task and then call get_next_batch again to continue. DO NOT stop until you reach the end of the narrative.");
            } else {
                output.append("\n\n[End of Narrative] All chapters for grain level " + level + " have been read. You may now provide a final comprehensive wrap-up of the task.");
            }
            return output.toString();
        }

        // 2. Start building a new batch
        int firstChapterLines = getChapterLines(chapters, startProgress, clusters, level);
        TraversalPattern firstChapter = chapters.get(startProgress);

        if (firstChapterLines > THRESHOLD && firstChapter instanceof AggregatorPattern) {
            Cluster cluster = findClusterForNode(firstChapter.getLead(), clusters);
            List<TraversalPattern> filterPatterns = startProgress > 0 ? chapters.subList(0, startProgress) : java.util.Collections.emptyList();
            List<NarrativeElement> elements = ((AggregatorPattern) firstChapter).getElements(cluster.getGraph(), filterPatterns);
            List<List<NarrativeElement>> splits = createBalancedSplits(elements);

            narrator.setSubChapterProgress(level, 1);
            String content = buildSplitContent(splits.get(0), startProgress + 1, chapters.size(), 1, splits.size());

            if (splits.size() == 1) {
                narrator.setSubChapterProgress(level, 0);
                narrator.incrementProgress(level);
            } else {
                narrator.setSubChapterProgress(level, 2);
            }

            StringBuilder output = new StringBuilder();
            output.append(content);
            if (startProgress + 1 < chapters.size() || narrator.getSubChapterProgress(level) > 0) {
                output.append("\n\nReminder: Process these chapters toward the requested task and then call get_next_batch again to continue. DO NOT stop until you reach the end of the narrative.");
            } else {
                output.append("\n\n[End of Narrative] All chapters for grain level " + level + " have been read. You may now provide a final comprehensive wrap-up of the task.");
            }
            return output.toString();
        }

        // Regular batching for smaller chapters
        int endProgress = startProgress + 1;
        int totalLinesInBatch = firstChapterLines;

        while (endProgress < chapters.size()) {
            int nextChapterLines = getChapterLines(chapters, endProgress, clusters, level);
            if (totalLinesInBatch + nextChapterLines > THRESHOLD) {
                break;
            }
            totalLinesInBatch += nextChapterLines;
            endProgress++;
        }

        StringBuilder output = new StringBuilder();

        List<String> chaptersString = new ArrayList<>();
        for (int i = startProgress; i < endProgress; i++) {
            TraversalPattern chapterPattern = chapters.get(i);
            Cluster cluster = findClusterForNode(chapterPattern.getLead(), clusters);
            if (cluster == null) {
                chaptersString.add(String.format("[Chapter %d]: Error: Could not find associated cluster.\n\n", i + 1));
                continue;
            }

            StringBuilder chapterString = new StringBuilder();
            List<TraversalPattern> filterPatterns = i > 0 ? chapters.subList(0, i) : java.util.Collections.emptyList();
            String content = chapterPattern.extended(cluster.getGraph(), level, filterPatterns);
            chapterString.append(String.format("[Chapter %d of %d]\n", i + 1, chapters.size()));
            chapterString.append(content);

            chaptersString.add(chapterString.toString());
        }
        output.append(String.join("\n\n", chaptersString));

        int chaptersRead = endProgress - startProgress;
        for (int i = 0; i < chaptersRead; i++) {
            narrator.incrementProgress(level);
        }

        if (endProgress < chapters.size()) {
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

        int progress = narrator.getProgress(level);
        List<TraversalPattern> chapters = narrator.getNarrative(level);
        if (chapters == null) {
            return "Narrative state lost. Please call init_narrative again.";
        }

        if (progress >= chapters.size()) {
            return "[End of Narrative] All chapters for grain level " + level + " have been read.";
        }

        TraversalPattern chapterPattern = chapters.get(progress);
        narrator.incrementProgress(level);

        List<Cluster> clusters = getOrComputeClusters(url);
        if (clusters.isEmpty()) {
            return "Error: No clusters available to provide context for the chapter.";
        }

        Cluster cluster = findClusterForNode(chapterPattern.getLead(), clusters);

        if (cluster == null) {
            return "Error: Could not find associated cluster for the current chapter.";
        }

        List<TraversalPattern> filterPatterns = progress > 0 ? chapters.subList(0, progress) : java.util.Collections.emptyList();
        String content = chapterPattern.extended(cluster.getGraph(), level, filterPatterns);
        int currentChapter = progress + 1;
        int totalChapters = chapters.size();

        // Update HTML page to expand only the current chapter
        NarrativeHtmlGenerator generator = cacheManager.getHtmlGenerator(url);
        if (generator != null) {
            try {
                generator.generateGrainLevelPage(level, clusters, progress);
            } catch (Exception e) {
                logger.error("Failed to update narrative HTML page", e);
            }
        }

        StringBuilder output = new StringBuilder();
        output.append("[Chapter ").append(currentChapter).append(" of ").append(totalChapters).append(" - GrainLevel: ").append(level).append("]\n\n");
        output.append(content);
        output.append("\n\n");

        if (currentChapter < totalChapters) {
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
                List<TraversalPattern> chapters = narrator.getNarrative(level);
                int count = chapters.size();

                double totalLines = 0;
                double maxLines = 0;

                for (int i = 0; i < chapters.size(); i++) {
                    TraversalPattern chapter = chapters.get(i);
                    Cluster cluster = findClusterForNode(chapter.getLead(), clusters);
                    if (cluster != null) {
                        List<TraversalPattern> filterPatterns = i > 0 ? chapters.subList(0, i) : java.util.Collections.emptyList();
                        String content = chapter.extended(cluster.getGraph(), level, filterPatterns);
                        int lines = content.split("\n").length;

                        totalLines += lines;
                        if (lines > maxLines) maxLines = lines;
                    }
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
                int count = narrator.getNarrative(level).size();
                summary.append("- ").append(level).append(" (").append(level.getDescription()).append("): ").append(count).append(" chapters\n");
            }

            summary.append("\n3. Ask the user which GrainLevel they would like to use to proceed.");
        }

        return summary.toString();
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
