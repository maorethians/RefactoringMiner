package narrator.langchain;

import narrator.langchain.prompt.ReviewPrompt;
import narrator.service.NarrativeService;
import org.refactoringminer.astDiff.graph.ReviewNode;
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
        boolean rawDiff = level == GrainLevel.RAW_DIFF;

        if (!rawDiff) {
            narrativeService.initializeNarrative(url);
        }
        List<Narrator.ChapterUnit> chapters = narrativeService.getFlatChapters(url, level);

        NarrativeState state = new NarrativeState();

        // 2. Iterative processing
        for (int i = 0; i < chapters.size(); i++) {
            System.out.println(i + 1 + "/" + chapters.size());
            Narrator.ChapterUnit chapter = chapters.get(i);

            String content = chapter.getContent();
            List<String> dependencyUnderstandings = state.getDependencyUnderstandings(chapter);
            ReviewPrompt.ParsedResponse chapterResponse = langchainClient.processChapter(content, dependencyUnderstandings, rawDiff);

            state.setUnderstanding(chapter, chapterResponse.understanding());
            state.setResult(chapter, chapterResponse.result());
        }

        // 3. Final compilation
        List<ReviewPrompt.ReviewComment> finalResult = langchainClient.compileResults(state.getResults(), state.getUnderstandings());
        return new NarrativeProcessResult(narrativeService.getReviewNodes(url, level), finalResult, state);
    }

    public record NarrativeProcessResult(List<ReviewNode> nodes, List<ReviewPrompt.ReviewComment> comments, NarrativeState state) {
        public String content() {
            return String.join("\n\n", comments.stream()
                    .map(comment -> String.join(", ", comment.hunkIds()) + ": " + comment.text()).toList());
        }
    }
}
