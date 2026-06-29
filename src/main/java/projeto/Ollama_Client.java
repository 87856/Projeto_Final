package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


public class Ollama_Client {

    public  static final String URL_OLLAMA          = "http://localhost:11434";
    public  static final String MODELO_EMBEDDINGS   = "nomic-embed-text";
    public  static final String MODELO_LLM          = "qwen2.5:7b";  // chest RAG key extractor
    public  static final String MODELO_FAST         = "qwen2.5:1.5b"; // fast tactical (tier 1)
    public  static final String MODELO_PLANNER      = "qwen2.5:7b";  // strategy planner (tier 2)

    private final HttpClient httpClient;

    public Ollama_Client() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public double[] gerarEmbedding(String texto) {
        try {
            JsonObject corpo = new JsonObject();
            corpo.addProperty("model", MODELO_EMBEDDINGS);
            corpo.addProperty("prompt", texto);

            HttpRequest pedido = HttpRequest.newBuilder()
                    .uri(URI.create(URL_OLLAMA + "/api/embeddings"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corpo.toString()))
                    .build();

            HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());

            if (resposta.statusCode() != 200) {
                System.err.println("[OllamaClient] Erro embeddings: HTTP " + resposta.statusCode());
                return null;
            }

            JsonObject json = JsonParser.parseString(resposta.body()).getAsJsonObject();
            JsonArray vetorJson = json.getAsJsonArray("embedding");

            double[] vetor = new double[vetorJson.size()];
            for (int i = 0; i < vetorJson.size(); i++) {
                vetor[i] = vetorJson.get(i).getAsDouble();
            }

            return vetor;

        } catch (Exception e) {
            System.err.println("[OllamaClient] Exceção ao gerar embedding: " + e.getMessage());
            return null;
        }
    }


    public List<Vetores> processarManual(String textoManual) {
        List<Vetores> documentos = new ArrayList<>();

        if (textoManual == null || textoManual.isBlank()) {
            System.err.println("[OllamaClient] Manual vazio ou nulo.");
            return documentos;
        }


        String[] chunks = textoManual.split("\\n");

        System.out.println("[OllamaClient] A vetorizar " + chunks.length + " chunks do manual...");

        for (int i = 0; i < chunks.length; i++) {
            String chunk = chunks[i].trim();
            if (chunk.length() < 10) continue;

            double[] vetor = gerarEmbedding(chunk);
            if (vetor != null) {
                documentos.add(new Vetores(chunk, vetor));
                System.out.printf("[OllamaClient] Chunk %d/%d vetorizado.%n", i + 1, chunks.length);
            }


            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        System.out.println("[OllamaClient] Manual processado: " + documentos.size() + " chunks em memória.");
        return documentos;
    }


    public Vetores encontrarChunkMaisRelevante(String enigma, List<Vetores> documentos) {
        if (documentos == null || documentos.isEmpty()) {
            System.err.println("[OllamaClient] Base de documentos vazia.");
            return null;
        }

        double[] vetorEnigma = gerarEmbedding(enigma);
        if (vetorEnigma == null) {
            System.err.println("[OllamaClient] Não foi possível vetorizar o enigma.");
            return null;
        }

        Vetores maisRelevante = null;
        double maiorSimilaridade = -1.0;

        for (Vetores doc : documentos) {
            double similaridade = doc.calcularSimilaridade(vetorEnigma);
            if (similaridade > maiorSimilaridade) {
                maiorSimilaridade = similaridade;
                maisRelevante = doc;
            }
        }

        System.out.printf("[OllamaClient] Chunk mais relevante encontrado (similaridade: %.4f)%n", maiorSimilaridade);
        return maisRelevante;
    }

    /**
     * Generic JSON-mode generation. Sends a single non-streamed request.
     *
     * <p>When {@code asJson} is true the request includes {@code "format": {}}
     * which Ollama interprets as "respond with valid JSON". The chest-RAG path
     * <b>must</b> pass {@code false} because its prompt asks for a single bare
     * keyword — with {@code format:{}} enforced the model often wraps the
     * answer in JSON like {@code {"answer":"KEY"}} and the regex post-processing
     * would extract the JSON key instead of the value, breaking the pipeline.
     *
     * @param model     local Ollama model tag (e.g. "llama3.2:1b")
     * @param prompt    full prompt (system + user + assistant header)
     * @param temperature  low = deterministic; ~0.1 is a sane default for structured output
     * @param numPredict   upper bound on generated tokens
     * @param asJson   true => request {@code "format":{}} (forces JSON output);
     *                 false => no format flag (raw text output)
     * @return raw text from the model (already trimmed), or null on failure
     */
    public String generateJson(String model, String prompt, double temperature,
                               int numPredict, boolean asJson) {
        try {
            JsonObject opcoes = new JsonObject();
            opcoes.addProperty("temperature", temperature);
            opcoes.addProperty("num_predict", numPredict);

            JsonObject corpo = new JsonObject();
            corpo.addProperty("model", model);
            corpo.addProperty("prompt", prompt);
            corpo.addProperty("stream", false);
            if (asJson) corpo.add("format", new JsonObject()); // {} => "any JSON"
            corpo.add("options", opcoes);

            HttpRequest pedido = HttpRequest.newBuilder()
                    .uri(URI.create(URL_OLLAMA + "/api/generate"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(corpo.toString()))
                    .build();

            HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());

            if (resposta.statusCode() != 200) {
                System.err.println("[OllamaClient] Erro LLM (" + model + "): HTTP " + resposta.statusCode());
                return null;
            }

            JsonObject json = JsonParser.parseString(resposta.body()).getAsJsonObject();
            if (!json.has("response")) return null;
            return json.get("response").getAsString().trim();

        } catch (Exception e) {
            System.err.println("[OllamaClient] Exceção em generateJson(" + model + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Extrai a chave de desbloqueio usando o LLM com Prompt Engineering (ChatML).
     * Usa temperatura 0.0 para outputs determinísticos.
     *
     * @param enigma         O texto do desafio do cofre
     * @param contextoManual O parágrafo mais relevante do manual (recuperado por RAG)
     * @return A chave alfanumérica extraída, ou null em caso de falha
     */
    public String extrairChaveRAG(String enigma, String contextoManual) {
        try {
            // Prompt Engineering com formato ChatML
            String promptChatML = "<|im_start|>system\n" +
                    "És um extrator de dados técnicos. A tua ÚNICA função é ler um excerto de manual " +
                    "e uma avaria, e responder EXCLUSIVAMENTE com a palavra-chave de desbloqueio. " +
                    "Não escrevas frases. Não expliques. Não uses pontuação. " +
                    "Responde APENAS com a palavra-chave alfanumérica, em maiúsculas.\n" +
                    "<|im_end|>\n" +
                    "<|im_start|>user\n" +
                    "EXCERTO DO MANUAL:\n" + contextoManual + "\n\n" +
                    "AVARIA/ENIGMA: " + enigma + "\n" +
                    "Qual é a palavra-chave de desbloqueio?\n" +
                    "<|im_end|>\n" +
                    "<|im_start|>assistant\n";

            // Reutiliza generateJson para manter o caminho de erro centralizado.
            // Como o RAG prompt NÃO exige JSON estruturado (espera uma palavra
            // crua), passamos asJson=false para não forçar o modo JSON do Ollama
            // — caso contrário o modelo embrulha a resposta e o regex apanha
            // apenas a chave JSON, nunca o valor.
            String chaveRaw = generateJson(MODELO_LLM, promptChatML, 0.0, 20, false);
            if (chaveRaw == null) return null;

            String chave = chaveRaw.split("\\s+")[0].toUpperCase().replaceAll("[^A-Z0-9\\-]", "");

            System.out.println("[OllamaClient] Chave extraída pelo LLM: '" + chave + "'");
            return chave.isEmpty() ? null : chave;

        } catch (Exception e) {
            System.err.println("[OllamaClient] Exceção ao extrair chave RAG: " + e.getMessage());
            return null;
        }
    }


    public boolean VerifyAvailability() {
        try {
            HttpRequest pedido = HttpRequest.newBuilder()
                    .uri(URI.create(URL_OLLAMA))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());
            return resposta.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
