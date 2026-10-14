package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

@Service
public class TextChunker {
    public static final int RECURSIVE_MAX = 1350;
    public static final int OVERLAP = 200;
    public List<String> recursive(String text) {
        List<String> output = new ArrayList<>();
        if (text == null || text.isBlank()) return output;
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(text.length(), pos + RECURSIVE_MAX);
            if (end < text.length()) {
                int minCut = pos + 550;
                int breakAt = text.lastIndexOf("\n\n", end);
                if (breakAt < minCut) breakAt = text.lastIndexOf("\n", end);
                if (breakAt < minCut) breakAt = text.lastIndexOf(". ", end);
                if (breakAt < minCut) breakAt = text.lastIndexOf(" ", end);
                if (breakAt >= minCut) end = breakAt + 1;
            }
            String part = text.substring(pos, end).trim();
            if (!part.isBlank()) output.add(part);
            if (end >= text.length()) break;
            pos = Math.max(pos + 1, end - OVERLAP);
        }
        return output;
    }

    // Fase semântica: agrupa frases e observa a similaridade entre unidades adjacentes.
    // Cada página é processada isoladamente para preservar a procedência da citação.
    public List<String> semantic(ProjectConfig cfg, String text, OllamaClient ollama) throws IOException {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() < 700) return List.of(text.trim());
        List<String> units = sentencesAsUnits(text, 330);
        if (units.size() < 3) return recursive(text);
        List<double[]> vectors = new ArrayList<>();
        for (int i = 0; i < units.size(); i += 12) {
            vectors.addAll(ollama.embed(cfg, units.subList(i, Math.min(i+12, units.size()))));
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(units.getFirst());
        for (int i = 1; i < units.size(); i++) {
            String unit = units.get(i);
            double affinity = VectorMath.cosine(vectors.get(i-1), vectors.get(i));
            boolean change = affinity < cfg.limiar_semantico && current.length() >= 350;
            boolean overflow = current.length() + unit.length() > 1300 && current.length() >= 280;
            if (change || overflow) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            } else if (!current.isEmpty()) current.append(" ");
            current.append(unit);
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());
        return chunks;
    }

    static List<String> sentencesAsUnits(String text, int targetChars) {
        // Mantém códigos e abreviações no texto; o segmento nunca ignora informações.
        String[] sentences = text.replaceAll("\\s*\\n\\s*", " ")
            .split("(?<=[.!?;:])\\s+(?=[\\p{Lu}0-9•✓])");
        List<String> units = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;
            if (b.length() >= targetChars && b.length() + sentence.length() > targetChars * 2) {
                units.add(b.toString().trim()); b.setLength(0);
            }
            if (!b.isEmpty()) b.append(' ');
            b.append(sentence.trim());
        }
        if (!b.isEmpty()) units.add(b.toString().trim());
        // Se a página possuir uma única frase longa, separa por palavras.
        if (units.size() == 1 && units.getFirst().length() > 1350) {
            String longUnit = units.getFirst();
            units.clear();
            int start = 0;
            while (start < longUnit.length()) {
                int end = Math.min(longUnit.length(), start + 600);
                if (end < longUnit.length()) {
                    int space = longUnit.lastIndexOf(' ', end);
                    if (space > start + 150) end = space;
                }
                units.add(longUnit.substring(start, end).trim());
                start = end;
            }
        }
        return units;
    }
}
