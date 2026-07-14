package narrator.langchain;

import narrator.graph.cluster.traverse.GrainLevel;
import narrator.service.NarrativeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class NarrativeProcessor {
    private static final Logger logger = LoggerFactory.getLogger(NarrativeProcessor.class);
    private final NarrativeService narrativeService;
    private final LangChainClient langchainClient;

    public NarrativeProcessor(NarrativeService narrativeService) {
        this.narrativeService = narrativeService;
        this.langchainClient = LangChainClient.create();
    }

    public NarrativeResponse process(NarrativeRequest request) throws Exception {
        String url = request.getUrl();
        GrainLevel level = request.getGrainLevel();
        String task = request.getTask();

        logger.info("Processing narrative for URL: {}, Level: {}, Task: {}", url, level, task);

        // 1. Initialize and get chapters
        narrativeService.initializeNarrative(url);
        List<String> chapters = narrativeService.getFlatChapters(url, level);

        NarrativeState state = NarrativeState.empty();
        List<ChapterResult> results = new ArrayList<>();

        // 2. Iterative processing
        for (int i = 0; i < chapters.size(); i++) {
            String content = chapters.get(i);
            logger.debug("Processing chapter {} of {}", i + 1, chapters.size());

            String response = langchainClient.processChapter(task, state.getUnderstanding(), content);

            // Parse the response
            ParsedResponse parsed = parseResponse(response);

            if (!"No updated understanding provided.".equals(parsed.understanding)) {
                String chapterUnderstanding = "Chapter " + (i + 1) + ": " + parsed.understanding;
                String updatedUnderstanding = state.getUnderstanding() == null
                        ? chapterUnderstanding
                        : state.getUnderstanding() + "\n\n" + chapterUnderstanding;
                state = new NarrativeState(updatedUnderstanding);
            }
            results.add(new ChapterResult(i, content, parsed.result));
        }

        // 3. Final compilation
        String finalResult = langchainClient.compileResults(task, results);
        return new NarrativeResponse(finalResult);
    }

    private ParsedResponse parseResponse(String response) {
        String understanding = "No updated understanding provided.";
        String result = "No intermediate result provided.";

        String lowerResponse = response.toLowerCase();
        int understandingIdx = lowerResponse.indexOf("understanding:");
        int resultIdx = lowerResponse.indexOf("result:");

        if (understandingIdx != -1 && resultIdx != -1) {
            if (understandingIdx < resultIdx) {
                understanding = response.substring(understandingIdx + 14, resultIdx).trim();
                result = response.substring(resultIdx + 7).trim();
            } else {
                result = response.substring(resultIdx + 7, understandingIdx).trim();
                understanding = response.substring(understandingIdx + 14).trim();
            }
        } else if (understandingIdx != -1) {
            understanding = response.substring(understandingIdx + 14).trim();
        } else if (resultIdx != -1) {
            result = response.substring(resultIdx + 7).trim();
        } else {
            // Fallback: the model might have just written the text
            result = response;
        }

        return new ParsedResponse(understanding, result);
    }

    public record ParsedResponse(String understanding, String result) {
    }
}
