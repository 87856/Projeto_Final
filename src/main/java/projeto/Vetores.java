package projeto;


public class Vetores {

    private String texto;
    private double[] vetor;

    public Vetores(String texto, double[] vetor) {
        this.texto = texto;
        this.vetor = vetor;
    }



    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }

    public double[] getVetor() {
        return vetor;
    }

    public void setVetor(double[] vetor) {
        this.vetor = vetor;
    }


    public double calcularSimilaridade(double[] vetorConsulta) {
        if (this.vetor == null || vetorConsulta == null || this.vetor.length != vetorConsulta.length) {
            return 0.0;
        }

        double produtoInterno = 0.0;
        double normaA = 0.0;
        double normaB = 0.0;

        for (int i = 0; i < this.vetor.length; i++) {
            produtoInterno += this.vetor[i] * vetorConsulta[i];
            normaA += this.vetor[i] * this.vetor[i];
            normaB += vetorConsulta[i] * vetorConsulta[i];
        }

        double denominador = Math.sqrt(normaA) * Math.sqrt(normaB);
        if (denominador == 0.0) {
            return 0.0;
        }

        return produtoInterno / denominador;
    }

    @Override
    public String toString() {
        return "Vetores{" +
                "texto='" + texto.substring(0, Math.min(texto.length(), 60)) + "...'" +
                ", tamanhoVetor=" + (vetor != null ? vetor.length : 0) +
                '}';
    }
}