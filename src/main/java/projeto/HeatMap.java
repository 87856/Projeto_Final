package projeto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;


public class HeatMap extends JPanel {


    private static final int TAMANHO_CELULA = 28;
    private static final int OFFSET_X = 20;
    private static final int OFFSET_Y = 60;
    private static final int LARGURA_GRELHA = 30;
    private static final int ALTURA_GRELHA = 25;


    private volatile JsonObject ultimaPercepcao = null;
    private volatile Map<String, Integer> historicoVisitas = null;
    private volatile int maxVisitas = 1;


    private volatile int hpAtual = 200;
    private volatile int xAtual = 0;
    private volatile int yAtual = 0;
    private volatile String ultimaAcao = "—";
    private volatile String estadoRAG = "Aguardando...";


    private JFrame janela;
    private JLabel labelStatus;


    public HeatMap(String nomeRobo, boolean modoLLM) {
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

        labelStatus = new JLabel("HP: 200 | Pos: (0,0) | Ação: — | RAG: Aguardando");
        labelStatus.setForeground(Color.CYAN);
        labelStatus.setFont(new Font("Monospaced", Font.PLAIN, 12));
        labelStatus.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        janela.setLayout(new BorderLayout());
        janela.add(this, BorderLayout.CENTER);
        janela.add(labelStatus, BorderLayout.SOUTH);
        janela.pack();
        janela.setLocationRelativeTo(null);
        janela.setVisible(true);
    }


    public void atualizar(JsonObject percepcao, Map<String, Integer> historico,
                          int hp, int x, int y, String acao, String estadoRag) {
        this.ultimaPercepcao = percepcao;
        this.historicoVisitas = historico;
        this.hpAtual = hp;
        this.xAtual = x;
        this.yAtual = y;
        this.ultimaAcao = acao;
        this.estadoRAG = estadoRag;


        if (historico != null && !historico.isEmpty()) {
            this.maxVisitas = historico.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        }


        labelStatus.setText(String.format("HP: %d | Pos: (%d,%d) | Ação: %s | RAG: %s", hp, x, y, acao, estadoRag));


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

        for (Map.Entry<String, Integer> entrada : historicoVisitas.entrySet()) {
            int[] coords = ParseCoords(entrada.getKey());
            if (coords == null) continue;

            int col = coords[0] - xAtual + LARGURA_GRELHA / 2;
            int linha = coords[1] - yAtual + ALTURA_GRELHA / 2;

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
                int linha = rec.get("y").getAsInt() - yAtual + ALTURA_GRELHA / 2;
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
                int linha = cofre.get("y").getAsInt() - yAtual + ALTURA_GRELHA / 2;
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
        try {
            JsonArray robos = ultimaPercepcao.getAsJsonArray("outros_robots");
            g.setColor(Color.RED);
            for (JsonElement elem : robos) {
                JsonObject robo = elem.getAsJsonObject();
                int col = robo.get("x").getAsInt() - xAtual + LARGURA_GRELHA / 2;
                int linha = robo.get("y").getAsInt() - yAtual + ALTURA_GRELHA / 2;
                if (InsideGrid(col, linha)) {
                    int px = OFFSET_X + col * TAMANHO_CELULA + 3;
                    int py = OFFSET_Y + linha * TAMANHO_CELULA + 3;
                    g.fillRect(px, py, TAMANHO_CELULA - 6, TAMANHO_CELULA - 6);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 8));
                    g.drawString("RIV", px + 1, py + 12);
                    g.setColor(Color.RED);
                }
            }
        } catch (Exception ignored) {}
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

        int[][] cores = {{0, 160, 255}, {180, 100, 0}, {0, 220, 80}, {255, 215, 0}, {255, 0, 0}};
        String[] textos = {"Agente", "Parede", "Energia", "Cofre", "Rival"};

        for (int i = 0; i < textos.length; i++) {
            int px = OFFSET_X + i * 100;
            g.setColor(new Color(cores[i][0], cores[i][1], cores[i][2]));
            g.fillRect(px, baseY, 12, 12);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(textos[i], px + 16, baseY + 11);
        }
    }



    private void DrawCelule(Graphics2D g, int mundoX, int mundoY, int padding) {
        int col = mundoX - xAtual + LARGURA_GRELHA / 2;
        int linha = mundoY - yAtual + ALTURA_GRELHA / 2;
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
}