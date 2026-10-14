package br.com.sylph.model;

public record RankedChunk(KnowledgeChunk chunk, double cosseno, double bm25,
                          double scoreFinal) { }
