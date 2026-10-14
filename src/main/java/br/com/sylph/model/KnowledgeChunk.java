package br.com.sylph.model;

public record KnowledgeChunk(String id, String documento, int pagina, String metodo,
                             String texto, double[] embedding) { }
