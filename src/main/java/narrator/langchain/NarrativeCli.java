package narrator.langchain;

import narrator.graph.cluster.traverse.GrainLevel;
import narrator.restapi.LangChainConfig;
import narrator.service.NarrativeService;

public class NarrativeCli {
    public static void main(String[] args) {
        // =========================================================================
        // CONFIGURATION: Change these values before running
        // =========================================================================
        String url = "https://github.com/maorethians/RefactoringMiner/commit/ad2d52bbd591c50bb233b83b906a9dcc8bbfc2ba";
        GrainLevel level = GrainLevel.FILE;
        String task = "Review this commit for potential bugs and efficiency improvements";
        // =========================================================================

        try {
            System.out.println("Initializing Narrative Processing...");
            System.out.println("URL: " + url);
            System.out.println("Level: " + level);
            System.out.println("Task: " + task);
            System.out.println("--------------------------------------------------");

            NarrativeService narrativeService = new NarrativeService();
            LangChainClient client = LangChainConfig.createClient();
            NarrativeProcessor processor = new NarrativeProcessor(narrativeService, client);

            NarrativeRequest request = new NarrativeRequest(url, level, task);

            NarrativeResponse response = processor.process(request, (chapterIdx, parsed) -> {
                System.out.printf("[Chapter %d] Understanding updated. Result: %s%n",
                        chapterIdx,
                        parsed.result.substring(0, Math.min(parsed.result.length(), 100)) + (parsed.result.length() > 100 ? "..." : ""));
            });

            System.out.println("\n--------------------------------------------------");
            System.out.println("FINAL RESULT:");
            System.out.println("--------------------------------------------------");
            System.out.println(response.getFinalResult());

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
