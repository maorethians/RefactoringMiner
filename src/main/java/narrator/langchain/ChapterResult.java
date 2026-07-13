package narrator.langchain;

public class ChapterResult {
    private final int chapterIndex;
    private final String content;
    private final String intermediateResult;

    public ChapterResult(int chapterIndex, String content, String intermediateResult) {
        this.chapterIndex = chapterIndex;
        this.content = content;
        this.intermediateResult = intermediateResult;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public String getContent() {
        return content;
    }

    public String getIntermediateResult() {
        return intermediateResult;
    }
}
