package narrator.restapi;

import narrator.langchain.LangChainClient;

public class LangChainConfig {
    public static LangChainClient createClient() {
        String provider = "ollama";
        String apiKey = "";
        String modelName = "gemma4:31b";
        String baseUrl = "http://localhost:11435";

        return LangChainClient.create(provider, apiKey, modelName, baseUrl);
    }
}
