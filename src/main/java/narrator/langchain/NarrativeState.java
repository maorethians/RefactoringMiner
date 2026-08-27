package narrator.langchain;

import org.refactoringminer.astDiff.graph.Node;
import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;

import java.util.*;

public class NarrativeState {
    // chapters are ordered by insertion
    private final Map<Narrator.ChapterUnit, String> chapterUnderstanding = new LinkedHashMap<>();
    private final Map<Narrator.ChapterUnit, String> chapterResult = new LinkedHashMap<>();
    public static final int THRESHOLD = 500;

    public void setUnderstanding(Narrator.ChapterUnit chapter, String understanding) {
        chapterUnderstanding.put(chapter, understanding);
    }

    public void setResult(Narrator.ChapterUnit chapter, String result) {
        chapterResult.put(chapter, result);
    }

    public List<String> getDependencyUnderstandings(Narrator.ChapterUnit chapter) {
        Set<Node> subjectSides = chapter.getSides();
        return chapterUnderstanding.entrySet().stream()
                .filter(entry -> subjectSides.stream().anyMatch(side -> entry.getKey().getMains().contains(side)))
                .map(Map.Entry::getValue).toList();
    }

    public List<String> getResults() {
        return chapterResult.values().stream().toList();
    }

    public List<String> getUnderstandings() {
        return chapterUnderstanding.values().stream().toList();
    }
}
