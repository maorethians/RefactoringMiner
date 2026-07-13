package narrator.langchain;

import narrator.graph.cluster.traverse.GrainLevel;
import narrator.service.NarrativeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class NarrativeProcessor {
    private static final Logger logger = LoggerFactory.getLogger(NarrativeProcessor.class);
    private final NarrativeService narrativeService;
    private final LangChainClient langchainClient;

    public NarrativeProcessor(NarrativeService narrativeService, LangChainClient langchainClient) {
        this.narrativeService = narrativeService;
        this.langchainClient = langchainClient;
    }

    public NarrativeResponse process(NarrativeRequest request, BiConsumer<Integer, ParsedResponse> progressListener) throws Exception {
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

            state = new NarrativeState(parsed.understanding);
            results.add(new ChapterResult(i, content, parsed.result));

            if (progressListener != null) {
                progressListener.accept(i + 1, parsed);
            }
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

    public static class ParsedResponse {
        public final String understanding;
        public final String result;

        public ParsedResponse(String understanding, String result) {
            this.understanding = understanding;
            this.result = result;
        }
    }
}
