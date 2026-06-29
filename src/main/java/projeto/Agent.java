package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;


public class Agent {


    public  static final String SERVIDOR_ARENA  = "https://arena.pmonteiro.ovh";
    public  static final int    SLEEP_MS        = 400;
    public  static final int    HP_FUGA         = 60;

    // Strategy bias offsets — small adjustments layered onto existing heuristics.
    private static final int HP_FUGA_HIDE    = 80;   // flee sooner
    private static final int HP_FUGA_FARM    = 250;  // resources are always a priority
    private static final int COMBAT_MARGIN_HUNT = -10; // engage even at slight deficit


    private final Arena   arenaClient;
    private final Ollama_Client   ollamaClient;
    private final HeatMap painel;


    private final Map<String, Integer> historicoVisitas = new HashMap<>();
    private final Set<String>          cofresFalhados   = new HashSet<>();
    private final Queue<String>        filaAcoesPlaneadas = new LinkedList<>();
    private List<Vetores>    baseDocumentos   = new ArrayList<>();

    // ---- Two-tier LLM brain (null unless modoLLM && ollama up) -----------
    private RivalTracker rivalTracker;
    private StrategyState strategyState;
    private FastTacticalClient fastTactical;
    private PlannerClient planner;
    private final AtomicReference<TickSnapshot> tickSnapshotRef = new AtomicReference<>();
    private long tickCounter = 0;

    // Mode configuration (set once at construction from -Dbot.mode system property).
    private final BotConfig config;

    // Anti-backtrack: penalise the last RECENCY_DEPTH cells in exploration scoring.
    // Enabled via -Dbot.antiBacktrack=true (start.sh --no-backtrack flag).
    private static final int RECENCY_DEPTH = 8;
    private final boolean antiBacktrack =
            "true".equalsIgnoreCase(System.getProperty("bot.antiBacktrack", "false"));
    private final ArrayDeque<String> recentPath = new ArrayDeque<>(RECENCY_DEPTH + 1);

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
        this.config      = BotConfig.fromMode(System.getProperty("bot.mode", "opportunist"));
        System.out.println("[Agente] Modo: " + config.name
                + (antiBacktrack ? " | anti-backtrack ON (depth=" + RECENCY_DEPTH + ")" : ""));
    }


    public static void main(String[] args) {

        // Headless smoke test (no JOptionPane, just proves classloading + wiring).
        if (args.length > 0 && "--smoke".equals(args[0])) {
            System.out.println("[Smoke] Running headless wiring check...");
            try {
                Agent a = new Agent("SmokeBot", "SMOKE", /*modoLLM*/true);
                // Force-disable LLM so we never try to talk to Ollama in CI/sandbox.
                a.modoLLM = false;
                System.out.println("[Smoke] constructor OK; threads would be skipped.");
                // TickSnapshot construction sanity:
                TickSnapshot s = new TickSnapshot(1L, 0, 0, 200, null,
                        StrategyState.Goal.OPPORTUNIST,
                        Collections.emptyList());
                a.tickSnapshotRef.set(s);
                System.out.println("[Smoke] tickSnapshotRef OK: " + s.goal + " hp=" + s.hp);
            } catch (Throwable t) {
                System.err.println("[Smoke] FAIL: " + t);
                System.exit(2);
            }
            System.out.println("[Smoke] PASS");
            System.exit(0);
        }

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

        // Two-tier LLM init ONLY when modoLLM survived availability check AND mode allows it.
        if (modoLLM && !config.llmDisabled) {
            inicializarCamadasLLM();
        }

        if (modoLLM && !config.llmDisabled) {
            carregarManual();
        }


        System.out.println("[Agente] A entrar no ciclo principal...");
        while (true) {
            tickCounter++;
            try {

                JsonObject percepcao = arenaClient.percecionar();

                if (percepcao == null) {
                    System.err.println("[Agente] Perceção nula, a aguardar...");
                    publicarTickSnapshot(null); // hand a snapshot to clients so they can drain
                    Thread.sleep(SLEEP_MS);
                    continue;
                }


                if (!arenaClient.jogoIniciado(percepcao)) {
                    System.out.println("[Agente] Jogo ainda não iniciado. Em espera (Lobby)...");
                    publicarTickSnapshot(null);
                    Thread.sleep(2000);
                    continue;
                }

                if (arenaClient.jogoTerminado(percepcao)) {
                    System.out.println("[Agente] Jogo terminado. A encerrar motores.");
                    estadoRAG = "JOGO TERMINADO";
                    atualizarPainel(percepcao);
                    pararCamadasLLM();
                    break;
                }


                xAtual  = arenaClient.extrairX(percepcao);
                yAtual  = arenaClient.extrairY(percepcao);
                hpAtual = arenaClient.extrairHP(percepcao);


                registarVisita(xAtual, yAtual);

                System.out.printf("[Agente] Pos: (%d,%d) | HP: %d%n", xAtual, yAtual, hpAtual);


                // Feed tracker BEFORE publishing snapshot so clients see fresh rival state.
                if (rivalTracker != null) {
                    rivalTracker.updateFromPerception(percepcao, xAtual, yAtual);
                }
                publicarTickSnapshot(percepcao);


                String acaoEscolhida = decidirAcao(percepcao);


                executarAcao(acaoEscolhida, percepcao);


                atualizarPainel(percepcao);


                Thread.sleep(SLEEP_MS);

            } catch (InterruptedException e) {
                System.err.println("[Agente] Thread interrompida.");
                pararCamadasLLM();
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[Agente] Erro no ciclo: " + e.getMessage());
                try { Thread.sleep(SLEEP_MS); } catch (InterruptedException ignored) {}
            }
        }
        // Final safety: stop threads when the loop exits for any reason.
        pararCamadasLLM();
    }


    private void inicializarCamadasLLM() {
        rivalTracker  = new RivalTracker();
        strategyState = new StrategyState();

        // Seed the strategy with the mode's locked goal (planner won't override it when locked).
        if (config.initialGoal != StrategyState.Goal.OPPORTUNIST || config.lockGoal) {
            strategyState.set(new StrategyState.Strategy(
                    config.initialGoal,
                    new HashMap<>(), new HashMap<>(),
                    Integer.MIN_VALUE, Integer.MIN_VALUE, 0L, false));
        }

        fastTactical = new FastTacticalClient(ollamaClient, tickSnapshotRef);
        fastTactical.start();

        if (config.runPlanner) {
            planner = new PlannerClient(ollamaClient, rivalTracker, strategyState, tickSnapshotRef);
            planner.start();
        }

        System.out.println("[Agente] Camadas LLM iniciadas"
                + (config.runPlanner ? " (FastTactical + Planner)" : " (FastTactical only — planner desligado)")
                + " | lockGoal=" + config.lockGoal);
    }

    private void pararCamadasLLM() {
        if (fastTactical != null) { fastTactical.stop(); fastTactical = null; }
        if (planner != null)     { planner.stop();     planner = null; }
    }

    private void publicarTickSnapshot(JsonObject percepcao) {
        if (tickSnapshotRef == null) return;
        StrategyState.Goal goal = strategyState != null
                ? strategyState.currentGoal() : StrategyState.Goal.OPPORTUNIST;
        List<RivalProfile> copy = rivalTracker != null
                ? rivalTracker.snapshotForPlanner() : Collections.emptyList();
        tickSnapshotRef.set(new TickSnapshot(
                tickCounter, xAtual, yAtual, hpAtual, percepcao, goal, copy));
    }


    private String decidirAcao(JsonObject percepcao) {

        // 1. Planned action queue (always highest priority).
        if (!filaAcoesPlaneadas.isEmpty()) return filaAcoesPlaneadas.poll();

        // 2. Chest enigma unlock — always attempted regardless of mode
        //    (if the bot is standing on a chest, open it).
        String enigma = arenaClient.extrairEnigmaCofre(percepcao);
        String chaveCofreAtual = xAtual + "," + yAtual;
        if (enigma != null && !cofresFalhados.contains(chaveCofreAtual)) {
            return tentarDesbloquearCofre(enigma, chaveCofreAtual);
        }

        // 3. Fast tactical LLM override — only for combat-capable modes.
        //    skipCombat modes should not receive attack suggestions from the model.
        if (!config.skipCombat && fastTactical != null) {
            FastTacticalClient.TacticalSuggestion sug = fastTactical.getSuggestionRef().get();
            boolean triggerStillActive =
                    hpAtual < config.hpFuga
                    || (rivalTracker != null
                            && rivalTracker.nearestRivalWithin(5, xAtual, yAtual) != null);
            if (sug != null
                    && sug.isFresh(tickCounter)
                    && sug.selfX == xAtual
                    && sug.selfY == yAtual
                    && triggerStillActive) {
                return sug.action;
            }
        }

        // 4. Chest-first navigation (hoarder, treasure, rich).
        //    Runs before combat so chests are the top priority after the LLM.
        if (config.chestFirst) {
            String acaoCofre = navegarParaCofre(percepcao);
            if (acaoCofre != null) return acaoCofre;
        }

        // 5. Combat (skipped for pacifist/resource modes).
        if (!config.skipCombat) {
            String acaoCombate = avaliarCombate(percepcao);
            if (acaoCombate != null) return acaoCombate;
        }

        // 6. Resource navigation (skipped for chest-only / explorer-low-hp modes).
        if (!config.skipResources) {
            int hpThreshold;
            if (config.lockGoal) {
                // Locked modes have explicit hpFuga; no goal-based bias needed.
                hpThreshold = config.hpFuga;
            } else {
                StrategyState.Goal goal = strategyState != null
                        ? strategyState.currentGoal() : StrategyState.Goal.OPPORTUNIST;
                if (goal == StrategyState.Goal.HIDE)      hpThreshold = HP_FUGA_HIDE;
                else if (goal == StrategyState.Goal.FARM) hpThreshold = HP_FUGA_FARM;
                else                                       hpThreshold = config.hpFuga;
            }
            if (hpAtual < hpThreshold) {
                String acaoRecurso = navegarParaRecurso(percepcao);
                if (acaoRecurso != null) return acaoRecurso;
            }
        }

        // 7. Normal chest navigation (when not already handled in step 4).
        if (!config.skipChests && !config.chestFirst) {
            String acaoCofre = navegarParaCofre(percepcao);
            if (acaoCofre != null) return acaoCofre;
        }

        // 8. Heatmap exploration (always the final fallback).
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
                    filaAcoesPlaneadas.clear();
                    historicoVisitas.merge(xAtual + "," + yAtual, 5, Integer::sum);  // penalize current cell heavily
                    System.err.println("[Agente] BLOQUEADO! A aguardar 1s...");
                    Thread.sleep(1000);
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
            JsonObject resultado = arenaClient.desbloquearCofre(chaveExtraida, chunkRelevante.getTexto(), chaveExtraida);

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
        // config.skipCombat already checked by caller; this guard is a safety net.
        if (config.skipCombat) return null;
        if (percepcao == null || !percepcao.has("outros_robots")) return null;

        try {
            JsonElement rivaisElem = percepcao.get("outros_robots");
            if (!rivaisElem.isJsonArray()) return null;
            JsonArray rivais = rivaisElem.getAsJsonArray();
            if (rivais.size() == 0) return null;

            // Ghost: flee from the nearest visible rival regardless of distance.
            if (config.fleeFromAny) {
                JsonObject nearest = findNearest(rivais);
                if (nearest != null) {
                    System.out.println("[Agente] FUGA (ghost) de rival em ("
                            + nearest.get("x").getAsInt() + "," + nearest.get("y").getAsInt() + ")");
                    return moverOpostoA(nearest.get("x").getAsInt(), nearest.get("y").getAsInt());
                }
                return null;
            }

            // Resolve current goal for non-locked modes (planner may have changed it).
            StrategyState.Goal goal = config.lockGoal
                    ? config.initialGoal
                    : (strategyState != null ? strategyState.currentGoal() : StrategyState.Goal.OPPORTUNIST);
            boolean hiding  = goal == StrategyState.Goal.HIDE;
            boolean hunting = goal == StrategyState.Goal.HUNT;

            // Collect rivals within attack range.
            List<JsonObject> candidates = new ArrayList<>();
            for (JsonElement elem : rivais) {
                JsonObject r = elem.getAsJsonObject();
                int dist = Math.abs(r.get("x").getAsInt() - xAtual)
                         + Math.abs(r.get("y").getAsInt() - yAtual);
                if (dist <= config.attackDistMax) candidates.add(r);
            }
            if (candidates.isEmpty()) return null;

            // Pick target: weakest (lowest HP) or first in list.
            JsonObject target = config.targetWeakest
                    ? Collections.min(candidates,
                            Comparator.comparingInt(r -> r.has("hp") ? r.get("hp").getAsInt() : 100))
                    : candidates.get(0);

            int rx  = target.get("x").getAsInt();
            int ry  = target.get("y").getAsInt();
            int rhp = target.has("hp") ? target.get("hp").getAsInt() : 100;
            int dist = Math.abs(rx - xAtual) + Math.abs(ry - yAtual);

            // HIDE goal — never engage.
            if (hiding) {
                System.out.println("[Agente] FUGA (HIDE)! HP próprio: " + hpAtual + " vs rival: " + rhp);
                return moverOpostoA(rx, ry);
            }

            // Assassin / bully: additional HP-advantage gate before attacking.
            if (config.requiredHpAdvantage > 0 && hpAtual < rhp + config.requiredHpAdvantage) {
                System.out.println("[Agente] FUGA (vantagem insuficiente)! "
                        + hpAtual + " vs " + rhp + " — precisa +" + config.requiredHpAdvantage);
                return moverOpostoA(rx, ry);
            }

            // Effective margin: locked modes use config value; others adapt to planner goal.
            int margin = config.lockGoal ? config.combatMarginDefault
                    : (hunting ? COMBAT_MARGIN_HUNT : config.combatMarginDefault);

            if (hpAtual > rhp + margin) {
                System.out.printf("[Agente] ATAQUE%s! dist=%d HP rival=%d%n",
                        hunting ? " (HUNT)" : (config.targetWeakest ? " (BULLY)" : ""), dist, rhp);
                return moverEmDirecao(rx, ry);
            } else {
                System.out.println("[Agente] FUGA! HP próprio: " + hpAtual + " vs rival: " + rhp);
                return moverOpostoA(rx, ry);
            }

        } catch (Exception e) {
            System.err.println("[Agente] Erro ao avaliar combate: " + e.getMessage());
        }
        return null;
    }

    private JsonObject findNearest(JsonArray rivais) {
        JsonObject best = null;
        int bestDist = Integer.MAX_VALUE;
        for (JsonElement e : rivais) {
            try {
                JsonObject r = e.getAsJsonObject();
                int dist = Math.abs(r.get("x").getAsInt() - xAtual)
                         + Math.abs(r.get("y").getAsInt() - yAtual);
                if (dist < bestDist) { bestDist = dist; best = r; }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private String navegarParaRecurso(JsonObject percepcao) {
        if (percepcao == null || !percepcao.has("recursos_no_mundo")) return null;

        try {
            JsonElement elem = percepcao.get("recursos_no_mundo");
            if (!elem.isJsonArray()) return null;
            JsonArray recursos = elem.getAsJsonArray();
            int melhorDist = Integer.MAX_VALUE;
            int alvoX = -1, alvoY = -1;

            for (JsonElement e : recursos) {
                JsonObject rec = e.getAsJsonObject();
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
            JsonElement elem = percepcao.get("cofres_no_mundo");
            if (!elem.isJsonArray()) return null;
            JsonArray cofres = elem.getAsJsonArray();
            int melhorDist = Integer.MAX_VALUE;
            int alvoX = -1, alvoY = -1;

            for (JsonElement e : cofres) {
                JsonObject cofre = e.getAsJsonObject();
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
                JsonElement fixosElem = percepcao.get("objetos_fixos");
                if (fixosElem.isJsonArray()) {
                    JsonArray fixos = fixosElem.getAsJsonArray();
                    for (JsonElement elem : fixos) {
                        JsonObject obj = elem.getAsJsonObject();
                        paredes.add(obj.get("x").getAsInt() + "," + obj.get("y").getAsInt());
                    }
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
            // Penalise cells within the 3x3 neighbourhood of any recent position.
            if (antiBacktrack && isNearRecentPath(cx, cy)) calor += 25;
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


    private boolean isNearRecentPath(int cx, int cy) {
        for (String pos : recentPath) {
            int comma = pos.indexOf(',');
            int rx = Integer.parseInt(pos.substring(0, comma));
            int ry = Integer.parseInt(pos.substring(comma + 1));
            if (Math.abs(cx - rx) <= 1 && Math.abs(cy - ry) <= 1) return true;
        }
        return false;
    }

    private void registarVisita(int x, int y) {
        String chave = x + "," + y;
        historicoVisitas.merge(chave, 1, Integer::sum);
        if (antiBacktrack) {
            recentPath.addFirst(chave);
            while (recentPath.size() > RECENCY_DEPTH) recentPath.removeLast();
        }
    }


    private void atualizarPainel(JsonObject percepcao) {
        // Status bar shows current goal + rival classifier map.
        String goalStr = strategyState != null ? strategyState.currentGoal().name() : "—";
        List<RivalProfile> rivals = rivalTracker != null
                ? rivalTracker.snapshotForPlanner() : Collections.emptyList();
        painel.atualizar(percepcao, historicoVisitas, hpAtual, xAtual, yAtual,
                ultimaAcao, estadoRAG, goalStr, rivals);
    }
}
