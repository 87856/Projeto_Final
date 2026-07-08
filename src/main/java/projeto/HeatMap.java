package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class HeatMap extends JPanel {


    private static final int TAMANHO_CELULA = 28;
    private static final int OFFSET_X = 20;
    private static final int OFFSET_Y = 60;
    private static final int LARGURA_GRELHA = 30;
    private static final int ALTURA_GRELHA = 25;

    /** Per-rival colour ramp (tier-2 overlay). */
    private static final Color COLOR_RIVAL_AGGRESSIVE = new Color(255, 40, 40);
    private static final Color COLOR_RIVAL_DEFENSIVE  = new Color(50, 110, 220);
    private static final Color COLOR_RIVAL_PASSIVE    = new Color(160, 160, 160);
    private static final Color COLOR_RIVAL_UNKNOWN    = Color.RED;


    private volatile JsonObject ultimaPercepcao = null;
    private volatile Map<String, Integer> historicoVisitas = null;
    private volatile int maxVisitas = 1;


    private volatile int hpAtual = 200;
    private volatile int xAtual = 0;
    private volatile int yAtual = 0;
    private volatile String ultimaAcao = "—";
    private volatile String estadoRAG = "Aguardando...";

    // NEW — tier-2 overlays
    private volatile String goal = "—";
    private volatile List<RivalProfile> rivalProfiles = Collections.emptyList();


    private JFrame    janela;
    private JLabel    labelStatus;
    private JTextArea telPanel;
    private boolean   guiEnabled;
    private String    nomeRobo;


    public HeatMap(String nomeRobo, boolean modoLLM) {
        this(nomeRobo, modoLLM, true);
    }

    public HeatMap(String nomeRobo, boolean modoLLM, boolean guiEnabled) {
        this.nomeRobo   = nomeRobo;
        this.guiEnabled = guiEnabled;
        if (!guiEnabled) return; // headless: skip all Swing construction
        setPreferredSize(new Dimension(
                LARGURA_GRELHA * TAMANHO_CELULA + OFFSET_X * 2,
                ALTURA_GRELHA * TAMANHO_CELULA + OFFSET_Y + 100
        ));
        setBackground(Color.BLACK);

        janela = new JFrame("Telemetry Monitor - " + nomeRobo + (modoLLM ? " (LLM MODE)" : " (HEURISTIC MODE)"));
        janela.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        janela.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {

                janela.setExtendedState(JFrame.ICONIFIED);
            }
        });

        labelStatus = new JLabel("HP: 200 | Pos: (0,0) | Ação: — | RAG: Aguardando | Goal: —");
        labelStatus.setForeground(Color.CYAN);
        labelStatus.setFont(new Font("Monospaced", Font.PLAIN, 12));
        labelStatus.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        telPanel = new JTextArea("Aguardando dados...");
        telPanel.setBackground(new Color(8, 10, 18));
        telPanel.setForeground(new Color(160, 210, 255));
        telPanel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        telPanel.setEditable(false);
        telPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane telScroll = new JScrollPane(telPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        telScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(40, 60, 90)));
        telScroll.setPreferredSize(new Dimension(230, 0));
        telScroll.getViewport().setBackground(new Color(8, 10, 18));

        janela.setLayout(new BorderLayout());
        janela.add(this, BorderLayout.CENTER);
        janela.add(labelStatus, BorderLayout.SOUTH);
        janela.add(telScroll, BorderLayout.EAST);
        janela.pack();
        janela.setLocationRelativeTo(null);
        janela.setVisible(true);
    }


    /**
     * Legacy signature kept alive — invoked by the early
     * {@code painel.atualizar(null, ...)} call in {@link Agent#iniciar}
     * right after {@code arenaClient.registar()} (before any rival state exists).
     * Delegates to the rich overload with neutral defaults.
     */
    public void atualizar(JsonObject percepcao, Map<String, Integer> historico,
                          int hp, int x, int y, String acao, String estadoRag) {
        if (!guiEnabled) return;
        atualizar(percepcao, historico, hp, x, y, acao, estadoRag, "—", Collections.emptyList());
    }

    /**
     * Rich overload: status bar shows the current strategy goal, and rival
     * squares are coloured by their planner-derived classification.
     *
     * @param goal          current planner goal (e.g. "HUNT", "OPPORTUNIST")
     * @param rivalProfiles current snapshot of {@link RivalProfile}s (may be empty)
     */
    public void atualizar(JsonObject percepcao, Map<String, Integer> historico,
                          int hp, int x, int y, String acao, String estadoRag,
                          String goal, List<RivalProfile> rivalProfiles) {
        if (!guiEnabled) return;
        this.ultimaPercepcao = percepcao;
        this.historicoVisitas = historico;
        this.hpAtual = hp;
        this.xAtual = x;
        this.yAtual = y;
        this.ultimaAcao = acao;
        this.estadoRAG = estadoRag;
        this.goal = (goal != null) ? goal : "—";
        this.rivalProfiles = (rivalProfiles != null)
                ? rivalProfiles : Collections.emptyList();


        if (historico != null && !historico.isEmpty()) {
            this.maxVisitas = historico.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        }


        labelStatus.setText(String.format(
                "HP: %d | Pos: (%d,%d) | Ação: %s | RAG: %s | Goal: %s",
                hp, x, y, acao, estadoRag, this.goal));


        SwingUtilities.invokeLater(this::repaint);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        DrawTitle(g2d);
        DrawGrid(g2d);
        DrawHeatMap(g2d);
        DrawFixedObjects(g2d);
        DrawResources(g2d);
        DrawChests(g2d);
        DrawOtherAgents(g2d);
        DrawAgent(g2d);
        DrawLegend(g2d);
    }

    private void DrawTitle(Graphics2D g) {
        g.setColor(new Color(0, 200, 255));
        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        g.drawString("[ ARENA 3D RAG - RADAR TELEMETRICO ]", OFFSET_X, 22);


        int larguraBarra = 200;
        int hpPercent = Math.min(100, (hpAtual * 100) / 250);
        Color corHP = hpAtual > 100 ? new Color(0, 220, 80) : hpAtual > 50 ? Color.ORANGE : Color.RED;
        g.setColor(new Color(40, 40, 40));
        g.fillRect(OFFSET_X, 30, larguraBarra, 14);
        g.setColor(corHP);
        g.fillRect(OFFSET_X, 30, (larguraBarra * hpPercent) / 100, 14);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        g.drawString("HP: " + hpAtual + "/250", OFFSET_X + 4, 43);

        // Goal chip (tier-2)
        g.setColor(new Color(20, 20, 50));
        g.fillRect(OFFSET_X + larguraBarra + 12, 30, 110, 14);
        g.setColor(Color.YELLOW);
        g.drawString("Goal: " + (goal != null ? goal : "—"),
                OFFSET_X + larguraBarra + 16, 43);
    }

    private void DrawGrid(Graphics2D g) {
        g.setColor(new Color(30, 30, 30));
        for (int col = 0; col < LARGURA_GRELHA; col++) {
            for (int linha = 0; linha < ALTURA_GRELHA; linha++) {
                int px = OFFSET_X + col * TAMANHO_CELULA;
                int py = OFFSET_Y + linha * TAMANHO_CELULA;
                g.drawRect(px, py, TAMANHO_CELULA, TAMANHO_CELULA);
            }
        }
    }

    private void DrawHeatMap(Graphics2D g) {
        if (historicoVisitas == null) return;
        // Snapshot to local: the main loop can swap the volatile reference
        // mid-iteration otherwise.
        Map<String, Integer> localHistorico = historicoVisitas;

        for (Map.Entry<String, Integer> entrada : localHistorico.entrySet()) {
            int[] coords = ParseCoords(entrada.getKey());
            if (coords == null) continue;

            int col = coords[0] - xAtual + LARGURA_GRELHA / 2;
            int linha = yAtual - coords[1] + ALTURA_GRELHA / 2; // flip Y: arena NORTE=y+1

            if (col < 0 || col >= LARGURA_GRELHA || linha < 0 || linha >= ALTURA_GRELHA) continue;

            int intensidade = (int) (255.0 * entrada.getValue() / Math.max(maxVisitas, 1));
            g.setColor(new Color(intensidade / 3, intensidade / 6, 0));
            g.fillRect(OFFSET_X + col * TAMANHO_CELULA + 1, OFFSET_Y + linha * TAMANHO_CELULA + 1,
                    TAMANHO_CELULA - 2, TAMANHO_CELULA - 2);
        }
    }

    private void DrawFixedObjects(Graphics2D g) {
        if (ultimaPercepcao == null) return;
        try {
            JsonArray objetos = ultimaPercepcao.getAsJsonArray("objetos_fixos");
            g.setColor(new Color(180, 100, 0)); // laranja escuro = paredes
            for (JsonElement elem : objetos) {
                JsonObject obj = elem.getAsJsonObject();
                DrawCelule(g, obj.get("x").getAsInt(), obj.get("y").getAsInt(), 1);
            }
        } catch (Exception ignored) {}
    }

    private void DrawResources(Graphics2D g) {
        if (ultimaPercepcao == null) return;
        try {
            JsonArray recursos = ultimaPercepcao.getAsJsonArray("recursos_no_mundo");
            g.setColor(new Color(0, 220, 80)); // verde = energia
            for (JsonElement elem : recursos) {
                JsonObject rec = elem.getAsJsonObject();
                int col = rec.get("x").getAsInt() - xAtual + LARGURA_GRELHA / 2;
                int linha = yAtual - rec.get("y").getAsInt() + ALTURA_GRELHA / 2;
                if (InsideGrid(col, linha)) {
                    int px = OFFSET_X + col * TAMANHO_CELULA + 4;
                    int py = OFFSET_Y + linha * TAMANHO_CELULA + 4;
                    g.fillOval(px, py, TAMANHO_CELULA - 8, TAMANHO_CELULA - 8);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 9));
                    g.drawString("+20", px - 1, py + 12);
                    g.setColor(new Color(0, 220, 80));
                }
            }
        } catch (Exception ignored) {}
    }

    private void DrawChests(Graphics2D g) {
        if (ultimaPercepcao == null) return;
        try {
            JsonArray cofres = ultimaPercepcao.getAsJsonArray("cofres_no_mundo");
            for (JsonElement elem : cofres) {
                JsonObject cofre = elem.getAsJsonObject();
                int col = cofre.get("x").getAsInt() - xAtual + LARGURA_GRELHA / 2;
                int linha = yAtual - cofre.get("y").getAsInt() + ALTURA_GRELHA / 2;
                if (InsideGrid(col, linha)) {
                    int px = OFFSET_X + col * TAMANHO_CELULA + 2;
                    int py = OFFSET_Y + linha * TAMANHO_CELULA + 2;
                    g.setColor(new Color(255, 215, 0)); // amarelo = cofre
                    g.fillRoundRect(px, py, TAMANHO_CELULA - 4, TAMANHO_CELULA - 4, 6, 6);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Monospaced", Font.BOLD, 11));
                    g.drawString("C", px + 7, py + 16);
                }
            }
        } catch (Exception ignored) {}
    }

    private void DrawOtherAgents(Graphics2D g) {
        if (ultimaPercepcao == null) return;
        // Snapshot the volatile references to local before iterating. The main
        // loop publishes a new TickSnapshot and a new rivals list every tick;
        // without the local capture, mid-iteration repaints could pop a
        // ConcurrentModificationException.
        List<RivalProfile> localProfiles = this.rivalProfiles;
        JsonObject localPercepcao = this.ultimaPercepcao;

        Map<String, RivalProfile> byPos = profileIndexByPosition(localProfiles);

        try {
            JsonArray robos = localPercepcao.getAsJsonArray("outros_robots");
            for (JsonElement elem : robos) {
                JsonObject robo = elem.getAsJsonObject();
                int wx = robo.get("x").getAsInt();
                int wy = robo.get("y").getAsInt();
                int col = wx - xAtual + LARGURA_GRELHA / 2;
                int linha = yAtual - wy + ALTURA_GRELHA / 2;
                if (InsideGrid(col, linha)) {
                    int px = OFFSET_X + col * TAMANHO_CELULA + 3;
                    int py = OFFSET_Y + linha * TAMANHO_CELULA + 3;

                    RivalProfile pf = byPos.get(wx + "," + wy);
                    Color c = colorForClass(pf != null ? pf.getClazz() : null);

                    g.setColor(c);
                    g.fillRect(px, py, TAMANHO_CELULA - 6, TAMANHO_CELULA - 6);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 8));
                    String tag = (pf != null) ? pf.getClazz().name().substring(0, 3) : "RIV";
                    g.drawString(tag, px + 1, py + 12);
                }
            }
        } catch (Exception ignored) {}
    }

    /** Indexes profiles by their last-seen world position so the renderer can look them up O(1). */
    private static Map<String, RivalProfile> profileIndexByPosition(List<RivalProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) return Collections.emptyMap();
        Map<String, RivalProfile> m = new HashMap<>(profiles.size());
        for (RivalProfile p : profiles) {
            if (p.getLastX() == Integer.MIN_VALUE) continue;
            m.put(p.getLastX() + "," + p.getLastY(), p);
        }
        return m;
    }

    private static Color colorForClass(RivalProfile.Classification c) {
        if (c == null) return COLOR_RIVAL_UNKNOWN;
        switch (c) {
            case AGGRESSIVE: return COLOR_RIVAL_AGGRESSIVE;
            case DEFENSIVE:  return COLOR_RIVAL_DEFENSIVE;
            case PASSIVE:    return COLOR_RIVAL_PASSIVE;
            default:         return COLOR_RIVAL_UNKNOWN;
        }
    }

    private void DrawAgent(Graphics2D g) {

        int col = LARGURA_GRELHA / 2;
        int linha = ALTURA_GRELHA / 2;
        int px = OFFSET_X + col * TAMANHO_CELULA + 2;
        int py = OFFSET_Y + linha * TAMANHO_CELULA + 2;

        g.setColor(new Color(0, 160, 255)); // azul = o nosso robô
        g.fillRoundRect(px, py, TAMANHO_CELULA - 4, TAMANHO_CELULA - 4, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 9));
        g.drawString("YOU", px + 2, py + 14);
    }

    private void DrawLegend(Graphics2D g) {
        int baseY = OFFSET_Y + ALTURA_GRELHA * TAMANHO_CELULA + 10;
        g.setFont(new Font("Monospaced", Font.PLAIN, 10));

        int[][] cores = {{0, 160, 255}, {180, 100, 0}, {0, 220, 80}, {255, 215, 0}};
        String[] textos = {"Agente", "Parede", "Energia", "Cofre"};

        for (int i = 0; i < textos.length; i++) {
            int px = OFFSET_X + i * 100;
            g.setColor(new Color(cores[i][0], cores[i][1], cores[i][2]));
            g.fillRect(px, baseY, 12, 12);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(textos[i], px + 16, baseY + 11);
        }

        // Second line: rival classification palette (only when tier-2 is on)
        int y2 = baseY + 18;
        g.setFont(new Font("Monospaced", Font.PLAIN, 9));
        Color[] rc = {COLOR_RIVAL_AGGRESSIVE, COLOR_RIVAL_DEFENSIVE, COLOR_RIVAL_PASSIVE, COLOR_RIVAL_UNKNOWN};
        String[] rl = {"AGR", "DEF", "PAS", "?"};
        for (int i = 0; i < rc.length; i++) {
            int px = OFFSET_X + i * 100;
            g.setColor(rc[i]);
            g.fillRect(px, y2, 12, 12);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(rl[i] + " Rival", px + 16, y2 + 11);
        }
    }



    private void DrawCelule(Graphics2D g, int mundoX, int mundoY, int padding) {
        int col = mundoX - xAtual + LARGURA_GRELHA / 2;
        int linha = yAtual - mundoY + ALTURA_GRELHA / 2;
        if (InsideGrid(col, linha)) {
            g.fillRect(
                    OFFSET_X + col * TAMANHO_CELULA + padding,
                    OFFSET_Y + linha * TAMANHO_CELULA + padding,
                    TAMANHO_CELULA - padding * 2,
                    TAMANHO_CELULA - padding * 2
            );
        }
    }

    private boolean InsideGrid(int col, int linha) {
        return col >= 0 && col < LARGURA_GRELHA && linha >= 0 && linha < ALTURA_GRELHA;
    }

    private int[] ParseCoords(String chave) {
        try {
            String[] partes = chave.split(",");
            return new int[]{Integer.parseInt(partes[0]), Integer.parseInt(partes[1])};
        } catch (Exception e) {
            return null;
        }
    }

    public void atualizarTelemetria(
            String mode, boolean antiBacktrack,
            boolean llmAtivo, boolean plannerAtivo,
            long tick, long avgMs, long minMs, long maxMs, long p99Ms,
            String goal,
            String fastReason, String fastAction, long fastTick,
            long plannerTick, int rivalsTracked, int rivalsClassified, int ragChunks,
            int cofresTotal, int cofresAbertos, int cofresFalhados, String ragStatus) {
        if (!guiEnabled) return;

        String txt =
            "─── BOT ────────────────────\n" +
            String.format(" Name:      %-17s%n", nomeRobo != null ? nomeRobo : "?") +
            "\n─── CONFIG ─────────────────\n" +
            String.format(" Mode:      %-17s%n", mode) +
            String.format(" Backtrack: %-17s%n", antiBacktrack ? "penalised (3x3)" : "free") +
            "\n─── TIMING (decidirAcao) ───\n" +
            String.format(" Tick #:    %-17d%n", tick) +
            String.format(" Avg:       %d ms%n",   avgMs) +
            String.format(" Min:       %d ms%n",   minMs) +
            String.format(" Max:       %d ms%n",   maxMs) +
            String.format(" p99 (1%%):  %d ms%n",  p99Ms) +
            "\n─── COFRES ─────────────────\n" +
            String.format(" Visible:   %-17d%n", cofresTotal) +
            String.format(" Opened:    %-17d%n", cofresAbertos) +
            String.format(" Failed:    %-17d%n", cofresFalhados) +
            " Status:\n" +
            wrapText(ragStatus, 25) +
            "\n─── qwen2.5:1.5b (fast) ───\n" +
            String.format(" Status:    %-17s%n", llmAtivo ? "ON" : "OFF") +
            String.format(" Last tick: %-17s%n", fastTick < 0 ? "—" : "#" + fastTick) +
            String.format(" Action:    %-17s%n", fastAction) +
            " Reason:\n" +
            wrapText(fastReason, 25) +
            "\n─── qwen2.5:7b (planner) ──\n" +
            String.format(" Status:    %-17s%n", plannerAtivo && llmAtivo ? "ON" : "OFF") +
            String.format(" Goal:      %-17s%n", goal) +
            String.format(" Last tick: %-17s%n", plannerTick <= 0 ? "—" : "#" + plannerTick) +
            String.format(" Tracked:   %-17d%n", rivalsTracked) +
            String.format(" Classified:%-17d%n", rivalsClassified) +
            "\n─── nomic-embed-text (RAG) ─\n" +
            String.format(" Chunks:    %d in RAM%n", ragChunks) +
            String.format(" Status:    %s%n", ragChunks > 0 ? "** READY **" : "loading...") + "\n";

        SwingUtilities.invokeLater(() -> telPanel.setText(txt));
    }

    private static String wrapText(String s, int width) {
        if (s == null || s.isEmpty()) return "  (none)\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += width) {
            sb.append("  ").append(s, i, Math.min(i + width, s.length())).append("\n");
        }
        return sb.toString();
    }
}
