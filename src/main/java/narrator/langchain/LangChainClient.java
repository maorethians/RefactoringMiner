package narrator.langchain;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import narrator.langchain.prompt.LangChainPrompt;
import narrator.langchain.prompt.ReviewPrompt;

import java.time.Duration;
import java.util.List;

public class LangChainClient {
    private final ChatLanguageModel model;
    private final LangChainPrompt prompt;

    public LangChainClient(ChatLanguageModel model) {
        this.model = model;
        this.prompt = new ReviewPrompt();
    }

    public static LangChainClient create() {
        String provider = "ollama";
        String apiKey = "";
        String modelName = "qwen2.5-coder:32b";
        String baseUrl = "http://localhost:11435";

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
                    .timeout(Duration.ofSeconds(999)) // Increase timeout for local large models
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return new LangChainClient(model);
    }

    public String generate(String prompt) {
        return model.generate(prompt);
    }

    public String processChapter(String content, List<String> understandings) {
        return generate(this.prompt.chapter(content, understandings));
    }

    public String compileResults(List<String> results, List<String> understandings) {
        String understanding = model.generate(this.prompt.understanding(understandings));
        return model.generate(this.prompt.result(results, understanding));
    }
}
