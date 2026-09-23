package narrator.langchain;

import narrator.langchain.prompt.ReviewPrompt;
import narrator.service.NarrativeService;
import org.refactoringminer.astDiff.graph.ReviewNode;
import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;
import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

            // Identifiers are produced only for chapters some later chapter actually depends on
            List<Integer> dependencies = dependencyIndices(i, chapters);
            for (Integer dependency : dependencies) {
                ensureIdentifiers(dependency, chapters, state);
            }

            List<ReviewPrompt.Identifier> dependencyIdentifiers = dependencies.stream()
                    .flatMap(dependency -> state.getIdentifiers(chapters.get(dependency)).stream()).toList();
            state.setResult(chapter, langchainClient.reviewChapter(chapter.getContent(), dependencyIdentifiers, rawDiff));
        }

        // 3. Final compilation
        List<ReviewPrompt.ReviewComment> finalResult = langchainClient.compileResults(state.getResults());
        return new NarrativeProcessResult(chapters, finalResult, state);
    }

    private static List<Integer> dependencyIndices(int index, List<Narrator.ChapterUnit> chapters) {
        Set<ReviewNode> sides = chapters.get(index).getSides();
        if (sides.isEmpty()) {
            return List.of();
        }

        List<Integer> dependencies = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            Narrator.ChapterUnit candidate = chapters.get(i);
            if (sides.stream().anyMatch(side -> candidate.getMains().contains(side))) {
                dependencies.add(i);
            }
        }

        return dependencies;
    }

    private void ensureIdentifiers(int index, List<Narrator.ChapterUnit> chapters, NarrativeState state) {
        Narrator.ChapterUnit chapter = chapters.get(index);
        if (state.hasIdentifiers(chapter)) {
            return;
        }

        state.setIdentifiers(chapter, langchainClient.generateIdentifiers(chapter.getContent()));
    }

    public record NarrativeProcessResult(List<Narrator.ChapterUnit> chapters, List<ReviewPrompt.ReviewComment> comments, NarrativeState state) {
        public String content() {
            return String.join("\n\n", comments.stream()
                    .map(comment -> String.join(", ", comment.hunkIds()) + ": " + comment.text()).toList());
        }

        public Set<ReviewNode> getNodes() {
            return chapters.stream().map(Narrator.ChapterUnit::getAnchoredNodes).flatMap(Set::stream).collect(Collectors.toSet());
        }
    }
}
