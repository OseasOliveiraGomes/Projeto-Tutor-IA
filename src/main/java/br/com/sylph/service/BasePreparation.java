package br.com.sylph.service;

import br.com.sylph.config.*;
import br.com.sylph.model.KnowledgeChunk;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class BasePreparation {
    private static final String DOC_NAME = "Manual Estágio Obrigatório_Polo-20262.pdf";
    private final PdfPages pdf;
    private final TextChunker chunker;
    private final OllamaClient ollama;
    private final CsvKnowledgeStore store;
    private final SettingsService settings;
    public BasePreparation(PdfPages pdf, TextChunker chunker, OllamaClient ollama,
                           CsvKnowledgeStore store, SettingsService settings) {
        this.pdf = pdf; this.chunker = chunker; this.ollama = ollama; this.store = store; this.settings = settings;
    }

    public void prepare() throws IOException {
        ProjectConfig cfg = settings.load();
        Path path = Path.of("dados", "Manual Estagio Obrigatorio UNOPAR 2026-2.pdf");
        if (!Files.isRegularFile(path)) throw new IOException("Não encontrei o manual em " + path.toAbsolutePath());
        List<PdfPages.Page> pages = pdf.extract(path);
        if (pages.isEmpty()) throw new IOException("Não foi possível extrair conteúdo textual do PDF");
        System.out.println("Páginas com texto extraível: " + pages.size());
        List<KnowledgeChunk> recursive = new ArrayList<>(), semantic = new ArrayList<>();
        for (PdfPages.Page page : pages) {
            for (String text : chunker.recursive(page.text()))
                recursive.add(new KnowledgeChunk("R-" + page.number() + "-" + (recursive.size() + 1),
                    DOC_NAME, page.number(), "recursivo", text, new double[0]));
            for (String text : chunker.semantic(cfg, page.text(), ollama))
                semantic.add(new KnowledgeChunk("S-" + page.number() + "-" + (semantic.size() + 1),
                    DOC_NAME, page.number(), "semantico", text, new double[0]));
            System.out.print("\rPáginas processadas: " + page.number() + " / " + pages.size());
        }
        System.out.println();
        // Ambas as bases são construídas antes de alterar os arquivos utilizados pelo chat.
        List<KnowledgeChunk> re = embedChunks(cfg, recursive);
        List<KnowledgeChunk> se = embedChunks(cfg, semantic);
        store.write("recursivo", re);
        store.write("semantico", se);
        System.out.println("Bases atualizadas. Recursivo: " + re.size() + " chunks; semântico: " + se.size() + " chunks.");
    }

    private List<KnowledgeChunk> embedChunks(ProjectConfig cfg, List<KnowledgeChunk> chunks) throws IOException {
        List<KnowledgeChunk> results = new ArrayList<>();
        for (int from = 0; from < chunks.size(); from += 12) {
            List<KnowledgeChunk> batch = chunks.subList(from, Math.min(from+12, chunks.size()));
            List<double[]> vectors = ollama.embed(cfg, batch.stream().map(KnowledgeChunk::texto).toList());
            for (int i = 0; i < batch.size(); i++) {
                KnowledgeChunk c = batch.get(i);
                results.add(new KnowledgeChunk(c.id(), c.documento(), c.pagina(), c.metodo(), c.texto(), vectors.get(i)));
            }
        }
        return results;
    }
}
