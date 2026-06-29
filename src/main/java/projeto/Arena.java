package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


public class Arena {

    private final String servidorBase;
    private final String nomeRobo;
    private final String codigoSala;
    private final HttpClient httpClient;

    public Arena(String servidorBase, String nomeRobo, String codigoSala) {
        this.servidorBase = servidorBase;
        this.nomeRobo = nomeRobo;
        this.codigoSala = codigoSala;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }


    public JsonObject registar() throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(URI.create(servidorBase + "/arena/" + codigoSala + "/register?robot_id=" + nomeRobo))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());
        String corpo = resposta.body();
        System.out.println("[Arena] Registo: HTTP " + resposta.statusCode() + " | " + corpo);
        if (resposta.statusCode() != 200) return null;
        return JsonParser.parseString(corpo).getAsJsonObject();
    }

    public JsonObject percecionar() throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(URI.create(servidorBase + "/arena/" + codigoSala + "/perceive/" + nomeRobo))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());
        String corpo = resposta.body();
        if (resposta.statusCode() != 200) {
            System.err.println("[Arena] Erro ao percecionar: HTTP " + resposta.statusCode() + " | " + corpo.substring(0, Math.min(200, corpo.length())));
            return null;
        }
        return JsonParser.parseString(corpo).getAsJsonObject();
    }

    public JsonObject executarAcao(String acao) throws Exception {
        JsonObject corpo = new JsonObject();
        corpo.addProperty("room_id", codigoSala);
        corpo.addProperty("robot_id", nomeRobo);
        corpo.addProperty("action", acao);
        return enviarPost("/arena/action", corpo);
    }

    public JsonObject desbloquearCofre(String chave, String ragChunk, String llmRaw) throws Exception {
        String url = servidorBase + "/arena/" + codigoSala + "/unlock"
                + "?robot_id=" + nomeRobo
                + "&code=" + java.net.URLEncoder.encode(chave, "UTF-8")
                + "&rag_chunk=" + java.net.URLEncoder.encode(ragChunk != null ? ragChunk : "", "UTF-8")
                + "&llm_raw=" + java.net.URLEncoder.encode(llmRaw != null ? llmRaw : "", "UTF-8");

        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());
        String respostaCorpo = resposta.body();
        if (resposta.statusCode() != 200) {
            System.err.println("[Arena] Erro unlock: HTTP " + resposta.statusCode() + " | " + respostaCorpo);
            return null;
        }
        return JsonParser.parseString(respostaCorpo).getAsJsonObject();
    }

    public String descarregarManual() throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(URI.create(servidorBase + "/arena/" + codigoSala + "/download_manual"))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) {
            System.err.println("[Arena] Erro manual: HTTP " + resposta.statusCode());
            return null;
        }
        System.out.println("[Arena] Manual descarregado (" + resposta.body().length() + " chars).");
        return resposta.body();
    }


    public int extrairX(JsonObject percepcao) {
        try {
            return percepcao.getAsJsonObject("o_meu_estado").get("x").getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }


    public int extrairY(JsonObject percepcao) {
        try {
            return percepcao.getAsJsonObject("o_meu_estado").get("y").getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }


    public int extrairHP(JsonObject percepcao) {
        try {
            return percepcao.getAsJsonObject("o_meu_estado").get("energia").getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }


    public boolean jogoIniciado(JsonObject percepcao) {
        try {
            return percepcao.get("game_started").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }


    public boolean jogoTerminado(JsonObject percepcao) {
        try {
            return percepcao.get("game_over").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }


    public String extrairEnigmaCofre(JsonObject percepcao) {
        try {
            JsonElement cofresElem = percepcao.get("cofres_no_mundo");  // CHANGED
            if (cofresElem == null || !cofresElem.isJsonArray()) return null;  // ADD THIS
            JsonArray cofres = cofresElem.getAsJsonArray();  // CHANGED
            int x = extrairX(percepcao);
            int y = extrairY(percepcao);

            for (JsonElement elem : cofres) {
                JsonObject cofre = elem.getAsJsonObject();
                if (cofre.get("x").getAsInt() == x && cofre.get("y").getAsInt() == y) {
                    if (cofre.has("terminal_desafio")) {
                        return cofre.get("terminal_desafio").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Arena] Erro ao extrair enigma: " + e.getMessage());
        }
        return null;
    }



    private JsonObject enviarPost(String endpoint, JsonObject corpo) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(URI.create(servidorBase + endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo.toString()))
                .build();

        HttpResponse<String> resposta = httpClient.send(pedido, HttpResponse.BodyHandlers.ofString());
        String respostaCorpo = resposta.body();

        if (resposta.statusCode() != 200) {
            System.err.println("[Arena] Erro em POST " + endpoint + ": HTTP " + resposta.statusCode() + " | " + respostaCorpo);
            return null;
        }

        return JsonParser.parseString(respostaCorpo).getAsJsonObject();
    }
}