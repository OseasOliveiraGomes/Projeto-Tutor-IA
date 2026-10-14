package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.model.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

@Service
public class HybridRetriever {
    private final OllamaClient ollama;
    private final CsvKnowledgeStore store;
    public HybridRetriever(OllamaClient ollama, CsvKnowledgeStore store) { this.ollama=ollama; this.store=store; }

    static List<String> terms(String text) {
        String plain = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        Matcher m = Pattern.compile("[a-z0-9]{2,}").matcher(plain);
        List<String> words = new ArrayList<>();
        while (m.find()) words.add(m.group());
        return words;
    }

    static double[] bm25(List<KnowledgeChunk> data, String query) {
        int n = data.size();
        List<List<String>> docs = data.stream().map(c -> terms(c.texto())).toList();
        Map<String,Integer> documentFreq = new HashMap<>();
        for (List<String> d : docs) for (String word : new HashSet<>(d))
            documentFreq.merge(word, 1, Integer::sum);
        double avg = docs.stream().mapToInt(List::size).average().orElse(1.0);
        double[] scores = new double[n];
        for (int i=0;i<n;i++) {
            List<String> words = docs.get(i);
            Map<String, Long> frequency = words.stream().collect(Collectors.groupingBy(w->w, Collectors.counting()));
            for (String word : new HashSet<>(terms(query))) {
                int df = documentFreq.getOrDefault(word,0);
                long tf = frequency.getOrDefault(word,0L);
                if (tf == 0) continue;
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                double k1 = 1.5, b = 0.75;
                scores[i] += idf * (tf*(k1+1)) / (tf + k1*(1-b+b*words.size()/Math.max(1.0,avg)));
            }
        }
        return scores;
    }

    // V1: reranking heurístico (cosseno + BM25 + cobertura de termos).
    // Não é CrossEncoder neural; versão futura poderá substituir este passo.
    public List<RankedChunk> retrieve(ProjectConfig cfg, String question) throws IOException {
        List<KnowledgeChunk> chunks = store.read(cfg.chunking_ativo);
        double[] q = ollama.embed(cfg, List.of(question)).getFirst();
        double[] lexical = bm25(chunks, question);
        double[] sem = new double[chunks.size()];
        double maxCos = -1, maxLex = 0;
        for (int i=0;i<chunks.size();i++) {
            sem[i] = VectorMath.cosine(q, chunks.get(i).embedding());
            maxCos = Math.max(maxCos, sem[i]); maxLex = Math.max(maxLex, lexical[i]);
        }
        // BM25 pode resgatar nomes próprios e códigos mesmo se o cosseno for baixo.
        if (cfg.debug_rag) System.out.println("[RAG] gate maxCos=" + maxCos + " min=" + cfg.min_similaridade + " maxBM25=" + maxLex);
        if (maxCos < cfg.min_similaridade && maxLex < 2.0) return List.of();

        List<Integer> indices = IntStream.range(0,chunks.size()).boxed().toList();
        List<Integer> byVector = new ArrayList<>(indices), byLexical = new ArrayList<>(indices);
        byVector.sort((a,b)->Double.compare(sem[b],sem[a]));
        byLexical.sort((a,b)->Double.compare(lexical[b],lexical[a]));
        Set<Integer> pool = new LinkedHashSet<>();
        byVector.stream().limit(12).forEach(pool::add);
        byLexical.stream().limit(12).forEach(pool::add);
        Map<Integer,Integer> vrank = new HashMap<>(), lrank = new HashMap<>();
        for (int i=0;i<indices.size();i++) { vrank.put(byVector.get(i),i+1); lrank.put(byLexical.get(i),i+1); }
        Set<String> queryWords = new HashSet<>(terms(question));
        List<RankedChunk> candidates = new ArrayList<>();
        for (int i : indices) {
            // Fusão inicial por RRF; o reordenamento final usa sinais adicionais.
            double rrf = 1.0/(60+vrank.get(i)) + 1.0/(60+lrank.get(i));
            Set<String> chunkWords = new HashSet<>(terms(chunks.get(i).texto()));
            double coverage = queryWords.isEmpty() ? 0 : queryWords.stream().filter(chunkWords::contains).count() / (double) queryWords.size();
            double normLex = maxLex > 0 ? lexical[i]/maxLex : 0;
            double score = (0.65*Math.max(0,sem[i])) + (0.20*normLex) + (0.10*coverage) + (0.05*rrf*30);
            candidates.add(new RankedChunk(chunks.get(i), sem[i], lexical[i], score));
        }
        candidates.sort(Comparator.comparingDouble(RankedChunk::scoreFinal).reversed());
        List<RankedChunk> primary = candidates.stream().filter(c -> pool.contains(chunks.indexOf(c.chunk())))
            .limit(cfg.top_k).toList();
        if (cfg.debug_rag) {
            System.out.println("[RAG] topK=" + cfg.top_k + " raio=" + cfg.expansao_paginas);
            for (RankedChunk c : candidates) System.out.printf(java.util.Locale.ROOT,
                "[RAG] chunk=%s doc=%s pagina=%d cos=%.5f bm25=%.5f final=%.5f semente=%s%n",
                c.chunk().id(), c.chunk().documento(), c.chunk().pagina(), c.cosseno(), c.bm25(), c.scoreFinal(), primary.contains(c));
        }
        List<RankedChunk> expanded = expandNearby(primary, candidates, cfg.expansao_paginas);
        if (cfg.debug_rag) for (RankedChunk c : expanded) if (!primary.contains(c))
            System.out.println("[RAG] expansao doc=" + c.chunk().documento() + " pagina=" + c.chunk().pagina() + " chunk=" + c.chunk().id());
        return expanded;
    }

    static List<RankedChunk> expandNearby(List<RankedChunk> primary, List<RankedChunk> ranked) {
        return expandNearby(primary, ranked, 2);
    }

    static List<RankedChunk> expandNearby(List<RankedChunk> primary, List<RankedChunk> ranked, int radius) {
        List<RankedChunk> result = new ArrayList<>();
        Set<KnowledgeChunk> seen = new HashSet<>();
        int characters = 0;
        List<RankedChunk> ordered = new ArrayList<>(primary);
        for (int distance = 0; distance <= radius; distance++) {
            final int d = distance;
            ranked.stream().filter(c -> primary.stream().anyMatch(seed ->
                c.chunk().documento().equals(seed.chunk().documento()) &&
                Math.abs(c.chunk().pagina() - seed.chunk().pagina()) == d)).forEach(ordered::add);
        }
        for (RankedChunk candidate : ordered) {
            if (!seen.add(candidate.chunk())) continue;
            int length = candidate.chunk().texto().length();
            if (characters + length > 16000) continue;
            result.add(candidate);
            characters += length;
        }
        return List.copyOf(result);
    }
}
