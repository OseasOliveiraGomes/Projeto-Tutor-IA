package br.com.sylph.service;

import br.com.sylph.config.*;
import br.com.sylph.model.RankedChunk;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;

@Service
public class RagService {
    public static final String UNKNOWN = "Não encontrei essa informação no material consultado. Recomendo confirmar com a coordenação do polo.";
    public record Source(String documento, int pagina, String trecho) { }
    public record Answer(String resposta, List<Source> fontes, boolean encontrou) { }

    private final SettingsService settings;
    private final HybridRetriever retriever;
    private final OllamaClient ollama;
    private final CsvKnowledgeStore store;
    public RagService(SettingsService settings, HybridRetriever retriever, OllamaClient ollama,
                      CsvKnowledgeStore store) {
        this.settings = settings; this.retriever = retriever; this.ollama = ollama; this.store = store;
    }

    public boolean ready() {
        try { return java.nio.file.Files.isRegularFile(store.file(settings.load().chunking_ativo)); }
        catch (Exception e) { return false; }
    }

    public Answer answer(String question) throws IOException {
        if (question == null || question.isBlank() || question.length() > 2000)
            throw new IllegalArgumentException("Digite uma pergunta de até 2000 caracteres.");
        var socialReply = SocialInteraction.replyTo(question);
        // Interação social não representa evidência encontrada no manual.
        if (socialReply.isPresent()) return new Answer(socialReply.get(), List.of(), false);
        ProjectConfig cfg = settings.load(); // configuração do DEV é relida a cada pergunta
        List<RankedChunk> top = retriever.retrieve(cfg, question.trim());
        if (top.isEmpty()) { if (cfg.debug_rag) System.out.println("[RAG] final=UNKNOWN motivo=recuperacao_vazia"); return new Answer(FinalAnswerPresentation.unknown(question), List.of(), false); }
        StringBuilder context = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        Set<String> already = new HashSet<>();
        int retrievalOrder = 0;
        for (RankedChunk r : top) {
            var c = r.chunk();
            context.append("--- INÍCIO DO TRECHO ---\n")
                .append("Ordem de recuperação: ").append(++retrievalOrder).append("\n")
                .append("Identificador do trecho: ").append(c.id()).append("\n")
                .append("Documento: ").append(c.documento()).append("\n")
                .append("Página: ").append(c.pagina()).append("\n")
                .append("Conteúdo: ").append(c.texto()).append("\n")
                .append("--- FIM DO TRECHO ---\n\n");
            String key = c.documento() + ":" + c.pagina();
            if (already.add(key)) sources.add(new Source(c.documento(), c.pagina(), c.texto()));
            else for (int i = 0; i < sources.size(); i++) {
                Source previous = sources.get(i);
                if (previous.documento().equals(c.documento()) && previous.pagina() == c.pagina())
                    sources.set(i, new Source(c.documento(), c.pagina(), previous.trecho() + "\n" + c.texto()));
            }
        }
        if (cfg.debug_rag) for (RankedChunk r : top)
            System.out.println("[RAG] Qwen doc=" + r.chunk().documento() + " pagina=" + r.chunk().pagina() + " chunk=" + r.chunk().id());
        String generated = ollama.chat(cfg, question.trim(), context.toString());
        if (cfg.debug_rag) System.out.println("[RAG] geracao_bruta=" + generated);
        AnswerEvidence.Audit audit = ollama.auditAnswer(cfg, question.trim(), sources, generated);
        if (cfg.debug_rag) System.out.println("[RAG] primeira_decisao=" + audit);
        String validated = String.join("\n\n", audit.approved());
        List<String> finalApproved = new ArrayList<>(audit.approved());
        String presentationNotice = "";
        if (!audit.accepted()) {
            if (cfg.debug_rag) System.out.println("[RAG] reparo=iniciado motivos=" + audit.issues() + " faltantes=" + audit.missing());
            try {
                String repaired = ollama.repairAnswer(cfg, question.trim(), sources, generated, audit);
                if (cfg.debug_rag) System.out.println("[RAG] reparo_bruto=" + repaired);
                AnswerEvidence.Audit second = ollama.auditAnswer(cfg, question.trim(), sources, repaired);
                if (cfg.debug_rag) System.out.println("[RAG] reparo_decisao=" + second);
                if (second.accepted()) {
                    validated = String.join("\n\n", second.approved());
                    finalApproved = new ArrayList<>(second.approved());
                }
                else {
                    // Keep approved original units; a later failed audit must not erase their evidence.
                    LinkedHashSet<String> preserved = new LinkedHashSet<>(audit.approved());
                    preserved.addAll(second.approved());
                    validated = String.join("\n\n", preserved);
                    finalApproved = new ArrayList<>(preserved);
                    presentationNotice = FinalAnswerPresentation.notice(question, second.missing(),second.issues(),false);
                }
                if (validated.isBlank()) {
                    // Distinguish absence reported by the model from technical/semantic validation failure.
                    boolean noInformation = generated.trim().equals(UNKNOWN) && repaired.trim().equals(UNKNOWN);
                    String failure = noInformation ? FinalAnswerPresentation.unknown(question) :
                        "Não foi possível validar uma resposta com segurança. " + presentationNotice;
                    if (cfg.debug_rag) System.out.println("[RAG] final_sem_resposta motivo=" + (noInformation ? "ausencia_reportada" : "falha_validacao") + " resposta=" + failure);
                    return new Answer(failure, List.of(), false);
                }
            } catch (IOException ex) {
                if (cfg.debug_rag) System.out.println("[RAG] reparo_falha_tecnica=" + ex.getMessage());
                if (validated.isBlank()) throw ex;
                presentationNotice = FinalAnswerPresentation.notice(question,audit.missing(),audit.issues(),true);
            }
        }
        // All model calls and evidence decisions used the original text above.
        // Only the student-facing rendering is cleaned/consolidated here.
        generated = FinalAnswerPresentation.render(finalApproved,sources);
        if (!presentationNotice.isBlank()) generated += "\n\n" + presentationNotice;
        if (cfg.debug_rag) System.out.println("[RAG] final_aprovado=" + generated);
        List<Source> cited = AnswerEvidence.parse(generated, sources).units().stream()
            .flatMap(u -> u.evidence().stream()).distinct().toList();
        return new Answer(generated, cited, true);
    }

}
