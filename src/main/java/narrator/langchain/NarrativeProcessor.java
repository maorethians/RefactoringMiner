package narrator.langchain;

import narrator.langchain.prompt.ReviewPrompt;
import narrator.service.NarrativeService;
import org.refactoringminer.astDiff.graph.cluster.Cluster;
import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;
import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;

import java.util.List;

public class NarrativeProcessor {
    private final NarrativeService narrativeService;
    private final LangChainClient langchainClient;

    public NarrativeProcessor(NarrativeService narrativeService) {
        this.narrativeService = narrativeService;
        this.langchainClient = LangChainClient.create();
    }

    public NarrativeProcessResult process(NarrativeRequest request) throws Exception {
        String url = request.getUrl();
        GrainLevel level = request.getGrainLevel();

        // 1. Initialize and get chapters
        narrativeService.initializeNarrative(url);
        List<Narrator.ChapterUnit> chapters = narrativeService.getFlatChapters(url, level);

        NarrativeState state = new NarrativeState();

        // 2. Iterative processing
        for (int i = 0; i < chapters.size(); i++) {
            Narrator.ChapterUnit chapter = chapters.get(i);

            String content = chapter.getContent();
            List<String> understandings = state.getDependencyUnderstandings(chapter);

            ReviewPrompt.ParsedResponse parsedChapterResult = null;
            while (parsedChapterResult == null) {
                System.out.println(i + 1 + "/" + chapters.size());
                String chapterResult = langchainClient.processChapter(content, understandings);
                parsedChapterResult = ReviewPrompt.parseChapter(chapterResult);
            }

            state.setUnderstanding(chapter, parsedChapterResult.understanding());
            state.setResult(chapter, parsedChapterResult.result());
        }

        // 3. Final compilation
        List<ReviewPrompt.ReviewComment> parsedFinalResult = null;
        while (parsedFinalResult == null) {
            System.out.println("Synthesizing final result.");
            String finalResult = langchainClient.compileResults(state.getResults(), chapters.stream().map(state::getUnderstanding).toList());
            parsedFinalResult = ReviewPrompt.parseResult(finalResult);
        }
        return new NarrativeProcessResult(narrativeService.getOrComputeClusters(url), parsedFinalResult);
    }

    public record NarrativeProcessResult(List<Cluster> clusters, List<ReviewPrompt.ReviewComment> comments) {
        public String content() {
            return String.join("\n\n", comments.stream()
                    .map(comment -> String.join(", ", comment.hunkIds()) + ": " + comment.text()).toList());
        }
    }
}
