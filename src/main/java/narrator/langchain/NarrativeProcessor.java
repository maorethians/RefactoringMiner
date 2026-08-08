package narrator.langchain;

import narrator.service.NarrativeService;
import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;
import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;

import java.util.List;
import java.util.Set;

public class NarrativeProcessor {
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

        // 1. Initialize and get chapters
        narrativeService.initializeNarrative(url);
        List<Narrator.ChapterUnit> chapters = narrativeService.getFlatChapters(url, level);

        NarrativeState state = new NarrativeState();

        // 2. Iterative processing
        for (int i = 0; i < chapters.size(); i++) {
            System.out.println(i + 1 + "/" + chapters.size());

            Narrator.ChapterUnit chapter = chapters.get(i);
            String content = chapter.getContent();
            Set<String> understanding = state.getUnderstanding(chapter);
            String response = langchainClient.processChapter(task, content, understanding);

            ParsedResponse parsed = parseResponse(response);

            state.setUnderstanding(chapter, parsed.understanding);
            state.setResult(chapter, parsed.result);
        }

        // 3. Final compilation
        String finalResult = langchainClient.compileResults(task, state.getResults());
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
