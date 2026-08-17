package narrator.langchain;

import org.refactoringminer.astDiff.graph.Node;
import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class NarrativeState {
    private final Map<Narrator.ChapterUnit, String> chapterUnderstanding = new HashMap<>();
    private final Map<Narrator.ChapterUnit, String> chapterResult = new HashMap<>();

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

    public String getUnderstanding(Narrator.ChapterUnit chapter) {
        return chapterUnderstanding.get(chapter);
    }

    public List<String> getResults() {
        return chapterResult.values().stream().toList();
    }
}
