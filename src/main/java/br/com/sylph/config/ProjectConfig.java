package br.com.sylph.config;

public class ProjectConfig {
    public String chunking_ativo = "recursivo";
    public String modelo_llm = "sylph-unopar";
    public String modelo_embeddings = "nomic-embed-text";
    public String ollama_url = "http://127.0.0.1:11434";
    public int top_k = 4;
    public boolean debug_rag = false;
    public int expansao_paginas = 2;
    public double min_similaridade = 0.35;
    public double limiar_semantico = 0.66;

    public void validar() {
        if (!chunking_ativo.equals("recursivo") && !chunking_ativo.equals("semantico")) {
            throw new IllegalArgumentException("chunking_ativo precisa ser recursivo ou semantico");
        }
        if (expansao_paginas < 0 || expansao_paginas > 5) throw new IllegalArgumentException("expansao_paginas entre 0 e 5");
        if (top_k < 1 || top_k > 10) throw new IllegalArgumentException("top_k entre 1 e 10");
        if (min_similaridade < -1 || min_similaridade > 1) throw new IllegalArgumentException("min_similaridade fora do intervalo");
        if (limiar_semantico < -1 || limiar_semantico > 1) throw new IllegalArgumentException("limiar_semantico fora do intervalo");
    }
}
