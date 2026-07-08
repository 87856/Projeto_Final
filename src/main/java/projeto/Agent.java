package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;


public class Agent {


    public  static final String SERVIDOR_ARENA  = "https://arena.pmonteiro.ovh";
    public  static final String SERVIDOR_LOCAL  = "http://localhost:8080";
    public  static final int    SLEEP_MS        = 400;
    public  static final int    HP_FUGA         = 60;

    // Strategy bias offsets — small adjustments layered onto existing heuristics.
    private static final int HP_FUGA_HIDE    = 80;   // flee sooner
    private static final int HP_FUGA_FARM    = 250;  // resources are always a priority
    private static final int COMBAT_MARGIN_HUNT = -10; // engage even at slight deficit


    private final Arena   arenaClient;
    private final Ollama_Client   ollamaClient;
    private final HeatMap painel;


    private final Map<String, Integer> historicoVisitas      = new HashMap<>();
    private final Set<String>          cofresFalhados        = new HashSet<>();
    private final Queue<String>        filaAcoesPlaneadas    = new LinkedList<>();
    private final Map<String, Integer> chestNavAttempts      = new HashMap<>();
    private static final int MAX_CHEST_NAV_ATTEMPTS = 25;
    private int cofresAbertos = 0;

    // Async chest unlock: background thread computes key; main loop submits when ready.
    private static final class ChestKeyResult {
        final String key, chunkText;
        ChestKeyResult(String key, String chunkText) { this.key = key; this.chunkText = chunkText; }
    }
    private final ConcurrentHashMap<String, ChestKeyResult> readyChestKeys = new ConcurrentHashMap<>();
    private final Set<String> computingChests = ConcurrentHashMap.newKeySet();
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

    // Decision-time ring buffer: ms taken by decidirAcao() per tick.
    private static final int TIMING_SAMPLES = 200;
    private final long[] tickTimes  = new long[TIMING_SAMPLES];
    private int tickTimeHead   = 0;
    private int tickTimeFilled = 0;

    // Anti-backtrack: penalise the last RECENCY_DEPTH cells in exploration scoring.
    // Enabled via -Dbot.antiBacktrack=true (start.sh --no-backtrack flag).
    private static final int RECENCY_DEPTH = 50; // must cover full map height so trail persists end-to-end
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
        this(nomeRobo, codigoSala, modoLLM, true);
    }

    public Agent(String nomeRobo, String codigoSala, boolean modoLLM, boolean gui) {
        this.modoLLM     = modoLLM;
        String servidor  = System.getProperty("bot.server", SERVIDOR_ARENA);
        this.arenaClient = new Arena(servidor, nomeRobo, codigoSala);
        this.ollamaClient = new Ollama_Client();
        this.painel      = new HeatMap(nomeRobo, modoLLM, gui);
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

        // Headless / scripted mode: --name and --room skip the dialog entirely.
        // Used by multi.sh to launch N bots from a script without popups.
        String cliName = System.getProperty("bot.name");
        String cliRoom = System.getProperty("bot.room");
        boolean noGui  = "true".equalsIgnoreCase(System.getProperty("bot.noGui", "false"));
        if (cliName != null && cliRoom != null) {
            System.out.printf("[Agente] Modo scripted: nome=%s sala=%s gui=%s%n",
                    cliName, cliRoom, !noGui);
            new Agent(cliName, cliRoom, true, !noGui).iniciar();
            return;
        }

        // Load last-used name + room from ~/.arena_agent.properties.
        Path propsPath = Paths.get(System.getProperty("user.home"), ".arena_agent.properties");
        Properties lastRun = new Properties();
        try (InputStream in = Files.newInputStream(propsPath)) { lastRun.load(in); }
        catch (Exception ignored) {}

        JTextField campoNome     = new JTextField(lastRun.getProperty("nome", "Alfa"));
        JTextField campoSala     = new JTextField(lastRun.getProperty("sala", "aluno_treino_2026"));
        JCheckBox  checkLLM      = new JCheckBox("Modo Heurística Pura (Sem LLM)");
        JTextField campoServidor = new JTextField(lastRun.getProperty("servidor", SERVIDOR_ARENA));

        String[] servidores = {"Produção (arena.pmonteiro.ovh)", "Localhost (dev)"};
        JComboBox<String> comboServidor = new JComboBox<>(servidores);
        // Sync combo → text field so user can also edit the URL directly.
        comboServidor.addActionListener(e -> {
            if (comboServidor.getSelectedIndex() == 1)
                campoServidor.setText(SERVIDOR_LOCAL);
            else
                campoServidor.setText(SERVIDOR_ARENA);
        });

        Object[] campos = {
                "Identificador do Robô:",       campoNome,
                "Código da Sala (Ex: ABCD12):", campoSala,
                "Servidor:",                    comboServidor,
                "URL (editável):",              campoServidor,
                "Desempenho:",                  checkLLM
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

        String nomeRobo    = campoNome.getText().trim();
        String codigoSala  = campoSala.getText().trim();
        String servidorUrl = campoServidor.getText().trim();
        boolean semLLM     = checkLLM.isSelected();

        if (nomeRobo.isEmpty() || codigoSala.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Nome e Sala são obrigatórios!", "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Persist name + room + server for next launch.
        try (OutputStream out = Files.newOutputStream(propsPath)) {
            Properties save = new Properties();
            save.setProperty("nome", nomeRobo);
            save.setProperty("sala", codigoSala);
            save.setProperty("servidor", servidorUrl.isEmpty() ? SERVIDOR_ARENA : servidorUrl);
            save.store(out, "Arena Agent — last run");
        } catch (Exception ignored) {}

        if (!servidorUrl.isEmpty()) System.setProperty("bot.server", servidorUrl);
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


        try {
            JsonObject respostaRegisto = arenaClient.registar();
            System.out.println("[Agente] Registo resposta: " + respostaRegisto);
            if (respostaRegisto == null) {
                System.err.println("[Agente] ERRO: registo recusado pelo servidor (resposta nula). A encerrar.");
                return;
            }
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

        // Load + vectorise the manual in the background so the game loop starts
        // immediately after registration. The arena drops bots that take too long
        // to send their first perceive. RAG is gracefully unavailable until ready.
        if (modoLLM && !config.llmDisabled) {
            Thread manualThread = new Thread(this::carregarManual, "manual-loader");
            manualThread.setDaemon(true);
            manualThread.start();
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


                long t0 = System.currentTimeMillis();
                String acaoEscolhida = decidirAcao(percepcao);
                tickTimes[tickTimeHead] = System.currentTimeMillis() - t0;
                tickTimeHead = (tickTimeHead + 1) % TIMING_SAMPLES;
                if (tickTimeFilled < TIMING_SAMPLES) tickTimeFilled++;

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

        // 2. Chest enigma unlock — async: background thread finds key; main loop submits it.
        String enigma = arenaClient.extrairEnigmaCofre(percepcao);
        String chaveCofreAtual = xAtual + "," + yAtual;
        if (enigma != null && !cofresFalhados.contains(chaveCofreAtual)) {
            ChestKeyResult ready = readyChestKeys.remove(chaveCofreAtual);
            if (ready != null) {
                return submeterChaveCofre(ready, chaveCofreAtual);
            }
            if (!modoLLM || baseDocumentos.isEmpty()) {
                cofresFalhados.add(chaveCofreAtual);
            } else if (!computingChests.contains(chaveCofreAtual)) {
                computingChests.add(chaveCofreAtual);
                final String enigmaFinal = enigma;
                estadoRAG = "A pesquisar chave (async)...";
                Thread t = new Thread(() -> {
                    try {
                        Vetores chunk = ollamaClient.encontrarChunkMaisRelevante(enigmaFinal, baseDocumentos);
                        if (chunk == null) { cofresFalhados.add(chaveCofreAtual); return; }
                        String key = ollamaClient.extrairChaveRAG(enigmaFinal, chunk.getTexto());
                        if (key != null) readyChestKeys.put(chaveCofreAtual, new ChestKeyResult(key, chunk.getTexto()));
                        else cofresFalhados.add(chaveCofreAtual);
                    } catch (Exception e) {
                        System.err.println("[Agente] Erro async cofre: " + e.getMessage());
                        cofresFalhados.add(chaveCofreAtual);
                    } finally {
                        computingChests.remove(chaveCofreAtual);
                    }
                }, "chest-key-" + chaveCofreAtual);
                t.setDaemon(true);
                t.start();
            } else {
                estadoRAG = "A computar chave...";
            }
            // Explore while key is being computed; navegarParaCofre will bring us back.
            return explorarPorCalor(percepcao);
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

    private String submeterChaveCofre(ChestKeyResult result, String chave) {
        try {
            estadoRAG = "A submeter chave: " + result.key;
            System.out.println("[Agente] Cofre " + chave + " — a submeter chave: " + result.key);
            JsonObject resposta = arenaClient.desbloquearCofre(result.key, result.chunkText, result.key);
            if (resposta != null) {
                String status = resposta.has("status") ? resposta.get("status").getAsString() : "";
                if ("sucesso".equals(status)) {
                    cofresAbertos++;
                    estadoRAG = "✓ Cofre aberto! +100HP";
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
            estadoRAG = "Erro ao submeter: " + e.getMessage();
            System.err.println("[Agente] Erro ao submeter chave: " + e.getMessage());
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

                // Give up on unreachable chests after MAX_CHEST_NAV_ATTEMPTS ticks targeting them.
                int attempts = chestNavAttempts.getOrDefault(chaveCofre, 0);
                if (attempts > MAX_CHEST_NAV_ATTEMPTS) {
                    cofresFalhados.add(chaveCofre);
                    System.err.println("[Agente] Cofre " + chaveCofre + " inacessível após " + attempts + " tentativas. A ignorar.");
                    continue;
                }

                int dist = Math.abs(cx - xAtual) + Math.abs(cy - yAtual);
                if (dist < melhorDist) {
                    melhorDist = dist;
                    alvoX = cx;
                    alvoY = cy;
                }
            }

            if (alvoX != -1) {
                String key = alvoX + "," + alvoY;
                chestNavAttempts.merge(key, 1, Integer::sum);
                return moverEmDirecao(alvoX, alvoY);
            }
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

        // For skipCombat / fleeFromAny modes, treat rival positions as walls so the
        // bot never accidentally walks into an opponent during exploration.
        if (config.skipCombat || config.fleeFromAny) {
            if (percepcao != null && percepcao.has("outros_robots")) {
                try {
                    for (JsonElement e : percepcao.get("outros_robots").getAsJsonArray()) {
                        JsonObject r = e.getAsJsonObject();
                        paredes.add(r.get("x").getAsInt() + "," + r.get("y").getAsInt());
                    }
                } catch (Exception ignored) {}
            }
        }


        // Collect candidates with equal minimum heat, then pick randomly among them.
        // Random tie-breaking prevents the deterministic lawnmower pattern.
        int menorCalor = Integer.MAX_VALUE;
        List<String> melhores = new ArrayList<>(4);

        for (int i = 0; i < candidatos.length; i++) {
            int cx = candidatos[i][0];
            int cy = candidatos[i][1];
            String chave = cx + "," + cy;

            if (paredes.contains(chave)) continue;

            int calor = historicoVisitas.getOrDefault(chave, 0);
            if (antiBacktrack && isNearRecentPath(cx, cy)) calor += 25;
            if (calor < menorCalor) {
                menorCalor = calor;
                melhores.clear();
                melhores.add(acoes[i]);
            } else if (calor == menorCalor) {
                melhores.add(acoes[i]);
            }
        }

        String melhorAcao = melhores.isEmpty()
                ? acoes[new Random().nextInt(acoes.length)]                // all walls — random
                : melhores.get(new Random().nextInt(melhores.size()));     // random tie-break

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
        String goalStr = strategyState != null ? strategyState.currentGoal().name() : "—";
        List<RivalProfile> rivals = rivalTracker != null
                ? rivalTracker.snapshotForPlanner() : Collections.emptyList();
        painel.atualizar(percepcao, historicoVisitas, hpAtual, xAtual, yAtual,
                ultimaAcao, estadoRAG, goalStr, rivals);

        // — fast tactical —
        String fastReason = "(no suggestion yet)";
        String fastAction = "—";
        long   fastTick   = -1;
        if (fastTactical != null) {
            FastTacticalClient.TacticalSuggestion sug = fastTactical.getSuggestion();
            if (sug != null) {
                if (!sug.reason.isBlank()) fastReason = sug.reason;
                fastAction = sug.action;
                fastTick   = sug.tick;
            }
        }
        // — planner —
        long plannerTick      = -1;
        int  rivalsClassified = 0;
        int  rivalsTracked    = rivalTracker != null ? rivalTracker.snapshotForPlanner().size() : 0;
        if (strategyState != null) {
            StrategyState.Strategy st = strategyState.get();
            plannerTick      = st.stampTick;
            rivalsClassified = st.rivals != null ? st.rivals.size() : 0;
        }
        // — RAG —
        int ragChunks = baseDocumentos != null ? baseDocumentos.size() : 0;

        // — Chests —
        int cofresTotal = 0;
        if (percepcao != null && percepcao.has("cofres_no_mundo")) {
            try { cofresTotal = percepcao.get("cofres_no_mundo").getAsJsonArray().size(); }
            catch (Exception ignored) {}
        }

        long[] s = timingStats();
        painel.atualizarTelemetria(
                config.name, antiBacktrack,
                modoLLM && !config.llmDisabled, config.runPlanner,
                tickCounter, s[0], s[1], s[2], s[3],
                goalStr, fastReason, fastAction, fastTick,
                plannerTick, rivalsTracked, rivalsClassified, ragChunks,
                cofresTotal, cofresAbertos, cofresFalhados.size(), estadoRAG);
    }

    private long[] timingStats() {
        if (tickTimeFilled == 0) return new long[]{0, 0, 0, 0};
        long[] copy = Arrays.copyOf(tickTimes, tickTimeFilled);
        Arrays.sort(copy);
        long sum = 0;
        for (long v : copy) sum += v;
        int p99idx = Math.max(0, (int)(copy.length * 0.99) - 1);
        return new long[]{
            sum / copy.length,     // avg
            copy[0],               // min
            copy[copy.length - 1], // max
            copy[p99idx]           // p99 (worst 1%)
        };
    }
}
