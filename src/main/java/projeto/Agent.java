package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.util.*;


public class Agent {


    private static final String SERVIDOR_ARENA  = "https://arena.pmonteiro.ovh";
    private static final int    SLEEP_MS        = 400;
    private static final int    HP_FUGA         = 60;


    private final Arena   arenaClient;
    private final Ollama_Client   ollamaClient;
    private final HeatMap painel;


    private final Map<String, Integer> historicoVisitas = new HashMap<>();
    private final Set<String>          cofresFalhados   = new HashSet<>();
    private final Queue<String>        filaAcoesPlaneadas = new LinkedList<>();
    private List<Vetores>    baseDocumentos   = new ArrayList<>();

    private int xAtual = 0;
    private int yAtual = 0;
    private int hpAtual = 200;
    private boolean modoLLM;
    private String ultimaAcao = "—";
    private String estadoRAG  = "Manual não carregado";

    public Agent(String nomeRobo, String codigoSala, boolean modoLLM) {
        this.modoLLM     = modoLLM;
        this.arenaClient = new Arena(SERVIDOR_ARENA, nomeRobo, codigoSala);
        this.ollamaClient = new Ollama_Client();
        this.painel      = new HeatMap(nomeRobo, modoLLM);
    }


    public static void main(String[] args) {

        JTextField campoNome  = new JTextField("Alfa");
        JTextField campoSala  = new JTextField("aluno_treino_2026");
        JCheckBox  checkLLM   = new JCheckBox("Modo Heurística Pura (Sem LLM)");

        String[] servidores   = {"Produção (arena.pmonteiro.ovh)", "Localhost (dev)"};
        JComboBox<String> comboServidor = new JComboBox<>(servidores);

        Object[] campos = {
                "Identificador do Robô:",     campoNome,
                "Código da Sala (Ex: ABCD12):", campoSala,
                "Servidor Alvo:",             comboServidor,
                "Desempenho:",                checkLLM
        };

        int resultado = JOptionPane.showConfirmDialog(
                null, campos,
                "Configuração do Agente - Arena SaaS 2026",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (resultado != JOptionPane.OK_OPTION) {
            System.out.println("Configuração cancelada. A encerrar.");
            return;
        }

        String nomeRobo   = campoNome.getText().trim();
        String codigoSala = campoSala.getText().trim();
        boolean semLLM    = checkLLM.isSelected();

        if (nomeRobo.isEmpty() || codigoSala.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Nome e Sala são obrigatórios!", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Agent agente = new Agent(nomeRobo, codigoSala, !semLLM);
        agente.iniciar();
    }


    public void iniciar() {
        System.out.println("[Agente] A iniciar...");


        if (modoLLM) {
            if (!ollamaClient.VerifyAvailability()) {
                System.err.println("[Agente] AVISO: Ollama não responde em localhost:11434. A mudar para modo heurístico.");
                modoLLM = false;
            }
        }


        // After registar()
        try {
            JsonObject respostaRegisto = arenaClient.registar();
            System.out.println("[Agente] Registo resposta completa: " + respostaRegisto);
        } catch (Exception e) {
            System.err.println("[Agente] Falha no registo: " + e.getMessage());
            e.printStackTrace();
            return;
        }
// Force one panel update immediately after registar to confirm panel works
        painel.atualizar(null, historicoVisitas, hpAtual, 0, 0, "INIT", "testando painel");


        if (modoLLM) {
            carregarManual();
        }


        System.out.println("[Agente] A entrar no ciclo principal...");
        while (true) {
            try {

                JsonObject percepcao = arenaClient.percecionar();

                if (percepcao == null) {
                    System.err.println("[Agente] Perceção nula, a aguardar...");
                    Thread.sleep(SLEEP_MS);
                    continue;
                }


                if (!arenaClient.jogoIniciado(percepcao)) {
                    System.out.println("[Agente] Jogo ainda não iniciado. Em espera (Lobby)...");
                    Thread.sleep(2000);
                    continue;
                }

                if (arenaClient.jogoTerminado(percepcao)) {
                    System.out.println("[Agente] Jogo terminado. A encerrar motores.");
                    estadoRAG = "JOGO TERMINADO";
                    atualizarPainel(percepcao);
                    break;
                }


                xAtual  = arenaClient.extrairX(percepcao);
                yAtual  = arenaClient.extrairY(percepcao);
                hpAtual = arenaClient.extrairHP(percepcao);


                registarVisita(xAtual, yAtual);

                System.out.printf("[Agente] Pos: (%d,%d) | HP: %d%n", xAtual, yAtual, hpAtual);


                String acaoEscolhida = decidirAcao(percepcao);


                executarAcao(acaoEscolhida, percepcao);


                atualizarPainel(percepcao);


                Thread.sleep(SLEEP_MS);

            } catch (InterruptedException e) {
                System.err.println("[Agente] Thread interrompida.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[Agente] Erro no ciclo: " + e.getMessage());
                try { Thread.sleep(SLEEP_MS); } catch (InterruptedException ignored) {}
            }
        }
    }


    private String decidirAcao(JsonObject percepcao) {

        if (!filaAcoesPlaneadas.isEmpty()) {
            return filaAcoesPlaneadas.poll();
        }


        String enigma = arenaClient.extrairEnigmaCofre(percepcao);
        String chaveCofreAtual = xAtual + "," + yAtual;

        if (enigma != null && !cofresFalhados.contains(chaveCofreAtual)) {
            return tentarDesbloquearCofre(enigma, chaveCofreAtual);
        }


        String acaoCombate = avaliarCombate(percepcao);
        if (acaoCombate != null) return acaoCombate;


        if (hpAtual < HP_FUGA) {
            String acaoRecurso = navegarParaRecurso(percepcao);
            if (acaoRecurso != null) return acaoRecurso;
        }


        String acaoCofre = navegarParaCofre(percepcao);
        if (acaoCofre != null) return acaoCofre;


        return explorarPorCalor(percepcao);
    }


    private void executarAcao(String acao, JsonObject percepcao) {
        try {
            JsonObject resposta = arenaClient.executarAcao(acao);
            ultimaAcao = acao;

            if (resposta != null) {
                String status = resposta.has("status") ? resposta.get("status").getAsString() : "desconhecido";
                System.out.println("[Agente] Ação '" + acao + "' → status: " + status);

                if ("bloqueado".equals(status)) {
                    System.err.println("[Agente] BLOQUEADO pelo Anti-Flood! A aguardar 5s...");
                    Thread.sleep(5000);
                } else if ("eliminado".equals(status)) {
                    System.err.println("[Agente] ELIMINADO da arena!");
                    estadoRAG = "ELIMINADO";
                }
            }
        } catch (Exception e) {
            System.err.println("[Agente] Erro ao executar ação: " + e.getMessage());
        }
    }


    private void carregarManual() {
        try {
            estadoRAG = "A descarregar manual...";
            String manual = arenaClient.descarregarManual();
            if (manual == null) {
                estadoRAG = "Falha ao descarregar manual";
                return;
            }
            estadoRAG = "A vetorizar manual...";
            baseDocumentos = ollamaClient.processarManual(manual);
            estadoRAG = "Manual pronto (" + baseDocumentos.size() + " chunks)";
            System.out.println("[Agente] " + estadoRAG);
        } catch (Exception e) {
            estadoRAG = "Erro RAG: " + e.getMessage();
            System.err.println("[Agente] " + estadoRAG);
        }
    }

    private String tentarDesbloquearCofre(String enigma, String chave) {
        System.out.println("[Agente] Cofre detetado! Enigma: " + enigma);

        if (!modoLLM || baseDocumentos.isEmpty()) {
            System.err.println("[Agente] LLM desativado ou manual não carregado. A ignorar cofre.");
            cofresFalhados.add(chave);
            return explorarPorCalor(null);
        }

        try {
            estadoRAG = "A pesquisar manual...";
            Vetores chunkRelevante = ollamaClient.encontrarChunkMaisRelevante(enigma, baseDocumentos);

            if (chunkRelevante == null) {
                estadoRAG = "Chunk não encontrado";
                cofresFalhados.add(chave);
                return explorarPorCalor(null);
            }

            estadoRAG = "A extrair chave com LLM...";
            String chaveExtraida = ollamaClient.extrairChaveRAG(enigma, chunkRelevante.getTexto());

            if (chaveExtraida == null) {
                estadoRAG = "LLM não gerou chave";
                cofresFalhados.add(chave);
                return explorarPorCalor(null);
            }


            estadoRAG = "A submeter chave: " + chaveExtraida;
            JsonObject resultado = arenaClient.desbloquearCofre(chaveExtraida);

            if (resultado != null) {
                String status = resultado.has("status") ? resultado.get("status").getAsString() : "";
                if ("sucesso".equals(status)) {
                    estadoRAG = "✓ Cofre aberto! +" + 100 + "HP";
                    System.out.println("[Agente] COFRE ABERTO! +100 HP");

                    filaAcoesPlaneadas.add("MOVER_NORTE");
                    filaAcoesPlaneadas.add("MOVER_NORTE");
                } else {
                    estadoRAG = "✗ Chave errada (-10 HP)";
                    System.err.println("[Agente] Chave errada. Penalização -10 HP.");
                    cofresFalhados.add(chave);
                }
            }

        } catch (Exception e) {
            estadoRAG = "Erro RAG: " + e.getMessage();
            System.err.println("[Agente] Erro no pipeline RAG: " + e.getMessage());
            cofresFalhados.add(chave);
        }

        return explorarPorCalor(null);
    }




    private String avaliarCombate(JsonObject percepcao) {
        if (percepcao == null || !percepcao.has("outros_robots")) return null;

        try {
            JsonArray rivais = percepcao.getAsJsonArray("outros_robots");
            for (JsonElement elem : rivais) {
                JsonObject rival = elem.getAsJsonObject();
                int rx = rival.get("x").getAsInt();
                int ry = rival.get("y").getAsInt();
                int rhp = rival.has("hp") ? rival.get("hp").getAsInt() : 100;

                double distancia = Math.abs(rx - xAtual) + Math.abs(ry - yAtual);

                if (distancia <= 2) {
                    if (hpAtual > rhp + 20) {

                        System.out.println("[Agente] ATAQUE! Rival a " + distancia + " blocos, HP rival: " + rhp);
                        return moverEmDirecao(rx, ry);
                    } else {

                        System.out.println("[Agente] FUGA! HP próprio: " + hpAtual + " vs rival: " + rhp);
                        return moverOpostoA(rx, ry);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Agente] Erro ao avaliar combate: " + e.getMessage());
        }
        return null;
    }

    private String navegarParaRecurso(JsonObject percepcao) {
        if (percepcao == null || !percepcao.has("recursos_no_mundo")) return null;

        try {
            JsonArray recursos = percepcao.getAsJsonArray("recursos_no_mundo");
            int melhorDist = Integer.MAX_VALUE;
            int alvoX = -1, alvoY = -1;

            for (JsonElement elem : recursos) {
                JsonObject rec = elem.getAsJsonObject();
                int rx = rec.get("x").getAsInt();
                int ry = rec.get("y").getAsInt();
                int dist = Math.abs(rx - xAtual) + Math.abs(ry - yAtual);
                if (dist < melhorDist) {
                    melhorDist = dist;
                    alvoX = rx;
                    alvoY = ry;
                }
            }

            if (alvoX != -1) return moverEmDirecao(alvoX, alvoY);
        } catch (Exception e) {
            System.err.println("[Agente] Erro ao navegar para recurso: " + e.getMessage());
        }
        return null;
    }


    private String navegarParaCofre(JsonObject percepcao) {
        if (percepcao == null || !percepcao.has("cofres_no_mundo")) return null;

        try {
            JsonArray cofres = percepcao.getAsJsonArray("cofres_no_mundo");
            int melhorDist = Integer.MAX_VALUE;
            int alvoX = -1, alvoY = -1;

            for (JsonElement elem : cofres) {
                JsonObject cofre = elem.getAsJsonObject();
                int cx = cofre.get("x").getAsInt();
                int cy = cofre.get("y").getAsInt();
                String chaveCofre = cx + "," + cy;

                if (cofresFalhados.contains(chaveCofre)) continue;

                int dist = Math.abs(cx - xAtual) + Math.abs(cy - yAtual);
                if (dist < melhorDist) {
                    melhorDist = dist;
                    alvoX = cx;
                    alvoY = cy;
                }
            }

            if (alvoX != -1) return moverEmDirecao(alvoX, alvoY);
        } catch (Exception e) {
            System.err.println("[Agente] Erro ao navegar para cofre: " + e.getMessage());
        }
        return null;
    }


    private String explorarPorCalor(JsonObject percepcao) {
        // Calcular as 4 posições candidatas
        int[][] candidatos = {
                {xAtual, yAtual - 1},
                {xAtual, yAtual + 1},
                {xAtual + 1, yAtual},
                {xAtual - 1, yAtual}
        };
        String[] acoes = {"MOVER_NORTE", "MOVER_SUL", "MOVER_ESTE", "MOVER_OESTE"};


        Set<String> paredes = new HashSet<>();
        if (percepcao != null && percepcao.has("objetos_fixos")) {
            try {
                JsonArray fixos = percepcao.getAsJsonArray("objetos_fixos");
                for (JsonElement elem : fixos) {
                    JsonObject obj = elem.getAsJsonObject();
                    paredes.add(obj.get("x").getAsInt() + "," + obj.get("y").getAsInt());
                }
            } catch (Exception ignored) {}
        }


        String melhorAcao = acoes[new Random().nextInt(acoes.length)];
        int menorCalor = Integer.MAX_VALUE;

        for (int i = 0; i < candidatos.length; i++) {
            int cx = candidatos[i][0];
            int cy = candidatos[i][1];
            String chave = cx + "," + cy;

            if (paredes.contains(chave)) continue;

            int calor = historicoVisitas.getOrDefault(chave, 0);
            if (calor < menorCalor) {
                menorCalor = calor;
                melhorAcao = acoes[i];
            }
        }

        return melhorAcao;
    }




    private String moverEmDirecao(int alvoX, int alvoY) {
        int dx = alvoX - xAtual;
        int dy = alvoY - yAtual;

        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx > 0 ? "MOVER_ESTE" : "MOVER_OESTE";
        } else {
            return dy < 0 ? "MOVER_NORTE" : "MOVER_SUL";
        }
    }


    private String moverOpostoA(int ameacaX, int ameacaY) {
        int dx = xAtual - ameacaX;
        int dy = yAtual - ameacaY;

        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx > 0 ? "MOVER_ESTE" : "MOVER_OESTE";
        } else {
            return dy > 0 ? "MOVER_SUL" : "MOVER_NORTE";
        }
    }


    private void registarVisita(int x, int y) {
        String chave = x + "," + y;
        historicoVisitas.merge(chave, 1, Integer::sum);
    }


    private void atualizarPainel(JsonObject percepcao) {
        painel.atualizar(percepcao, historicoVisitas, hpAtual, xAtual, yAtual, ultimaAcao, estadoRAG);
    }
}