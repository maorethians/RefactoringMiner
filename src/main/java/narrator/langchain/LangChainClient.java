package narrator.langchain;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;
import java.util.List;

public class LangChainClient {
    private final ChatLanguageModel model;

    public LangChainClient(ChatLanguageModel model) {
        this.model = model;
    }

    /**
     * Factory method to create a client based on the provider.
     * @param provider The LLM provider (anthropic, openai, ollama)
     * @param apiKey The API key (ignored for ollama)
     * @param modelName The model name (e.g., gemma4:31b)
     * @param baseUrl The base URL for the model server (primarily for ollama)
     */
    public static LangChainClient create(String provider, String apiKey, String modelName, String baseUrl) {
        ChatLanguageModel model;
        if ("anthropic".equalsIgnoreCase(provider)) {
            model = AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
        } else if ("openai".equalsIgnoreCase(provider)) {
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
        } else if ("ollama".equalsIgnoreCase(provider)) {
            model = OllamaChatModel.builder()
                    .baseUrl(baseUrl != null ? baseUrl : "http://localhost:11434")
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(60)) // Increase timeout for local large models
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return new LangChainClient(model);
    }

    public String generate(String prompt) {
        return model.generate(prompt);
    }

    public String processChapter(String task, String currentUnderstanding, String chapterContent) {
        String prompt = String.format(
                "Task: %s\n\n" +
                "Current understanding of the narrative so far:\n%s\n\n" +
                "New chapter content:\n%s\n\n" +
                "Please provide your response in the following format:\n" +
                "UNDERSTANDING: <updated understanding of the narrative>\n" +
                "RESULT: <intermediate result for the task on this chapter>",
                task, currentUnderstanding, chapterContent
        );
        return generate(prompt);
    }

    public String compileResults(String task, List<ChapterResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task).append("\n\n");
        sb.append("Below are the intermediate results for each chapter of the narrative:\n\n");
        for (ChapterResult res : results) {
            sb.append(String.format("Chapter %d:\n%s\n\n", res.getChapterIndex() + 1, res.getIntermediateResult()));
        }
        sb.append("\n\nPlease compile these intermediate results into a final comprehensive answer for the task.");
        return generate(sb.toString());
    }
}
