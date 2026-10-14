package br.com.sylph.service;

import java.util.*;
import java.util.regex.*;
import java.text.Normalizer;
import com.fasterxml.jackson.databind.JsonNode;

/** Evidence units are assigned in Java; the model may judge them but cannot rewrite approved text. */
public final class AnswerEvidence {
    private AnswerEvidence() { }
    static final Pattern CITATION = Pattern.compile("\\[Fonte:\\s*([^\\]|]+)\\s*\\|\\s*P\u00e1gina:\\s*(\\d+)\\s*\\]");
    public record Unit(int id, String text, List<RagService.Source> evidence) { }
    public record Parsed(List<Unit> units, List<String> issues) { }
    public record Audit(List<String> approved, List<String> issues, List<String> missing, boolean valid) {
        public boolean accepted() { return valid && issues.isEmpty() && missing.isEmpty() && !approved.isEmpty(); }
    }

    public static Parsed parse(String answer, List<RagService.Source> sources) {
        List<Unit> units = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        Matcher m = CITATION.matcher(answer);
        int start = 0;
        while (m.find()) {
            int end = m.end();
            List<RagService.Source> evidence = new ArrayList<>();
            boolean legal = addSource(m, sources, evidence);
            // Adjacent citations belong to the same claim, even when they reference different pages.
            while (true) {
                Matcher next = CITATION.matcher(answer);
                if (!next.find(end) || !answer.substring(end, next.start()).isBlank()) break;
                legal &= addSource(next, sources, evidence);
                end = next.end();
            }
            String text = answer.substring(start, end).trim();
            if (legal && !text.replaceAll(CITATION.pattern(), "").isBlank())
                units.add(new Unit(units.size() + 1, text, List.copyOf(evidence)));
            else issues.add("Citacao ausente das evidencias ou unidade sem afirmacao: " + text);
            // A point written AFTER a citation belongs to punctuation, not to
            // the next claim. Do not let it invalidate a later literal unit.
            while (end < answer.length() && ".!?…".indexOf(answer.charAt(end)) >= 0) end++;
            start = end;
            m.region(end, answer.length());
        }
        String tail = answer.substring(start).trim();
        String tailContent = tail.replaceFirst("^\\s*(?:\\d+[.)]|[-*])\\s*", "").trim();
        if (!tail.isBlank() && !tailContent.equals(RagService.UNKNOWN) && !tail.matches("[.!?…;:]+"))
            issues.add("Texto sem citacao: " + tail);
        if (units.isEmpty() && !answer.trim().equals(RagService.UNKNOWN)) issues.add("Nenhuma afirmacao com citacao valida");
        return new Parsed(List.copyOf(units), List.copyOf(issues));
    }

    private static boolean addSource(Matcher m, List<RagService.Source> sources, List<RagService.Source> evidence) {
        try {
            int page = Integer.parseInt(m.group(2));
            var found = sources.stream().filter(s -> s.documento().equals(m.group(1).trim()) && s.pagina() == page).findFirst();
            found.ifPresent(s -> { if (!evidence.contains(s)) evidence.add(s); });
            return found.isPresent();
        } catch (NumberFormatException ex) { return false; }
    }

    public static Audit decode(JsonNode verdict, Parsed parsed) {
        return decode(verdict,parsed,false);
    }

    public static Audit decode(JsonNode verdict, Parsed parsed, boolean debug) {
        List<String> approved = new ArrayList<>();
        List<String> issues = new ArrayList<>(parsed.issues());
        List<String> missing = new ArrayList<>();
        if (verdict == null || !verdict.path("claims").isArray() || !verdict.path("missing").isArray())
            return new Audit(List.of(), List.of("Protocolo de auditoria invalido"), List.of(), false);
        Map<Integer, JsonNode> claims = new HashMap<>();
        for (JsonNode c : verdict.path("claims")) {
            if (!c.path("id").isIntegralNumber() || claims.put(c.path("id").asInt(), c) != null)
                return new Audit(List.of(), List.of("IDs de auditoria invalidos ou duplicados"), List.of(), false);
        }
        if (claims.size() != parsed.units().size() || parsed.units().stream().anyMatch(u -> !claims.containsKey(u.id())))
            return new Audit(List.of(), List.of("Auditoria omitiu ou inventou unidades"), List.of(), false);
        for (Unit unit : parsed.units()) {
            JsonNode c = claims.get(unit.id());
            boolean supported = c.path("supported").isBoolean() && c.path("supported").booleanValue();
            // A positive semantic judgment must also point to literal evidence on every cited page.
            boolean quotesValid = c.path("quotes").isArray() && !c.path("quotes").isEmpty();
            List<String> invalidQuotes = new ArrayList<>();
            List<String> witnesses = new ArrayList<>();
            Set<RagService.Source> covered = new HashSet<>();
            for (JsonNode quote : c.path("quotes")) {
                String doc = quote.path("documento").asText();
                int page = quote.path("pagina").asInt(-1);
                String text = quote.path("texto").asText();
                var source = unit.evidence().stream().filter(s -> s.documento().equals(doc) && s.pagina() == page &&
                    !text.isBlank() && normalize(s.trecho()).contains(normalize(text))).findFirst();
                if (source.isEmpty()) { quotesValid = false; invalidQuotes.add("Trecho literal nao consta em " + doc + " pagina " + page + ": " + text); }
                else { covered.add(source.get()); witnesses.addAll(AttributionEvidence.contextualWitnesses(source.get(),text)); }
            }
            // Detect comparatives that the auditor marked as supported although its
            // literal quotations never mention one of the compared subjects.
            List<String> missingAnchors = missingComparisonAnchors(unit, c);
            boolean comparisonGrounded = missingAnchors.isEmpty();
            String factualText = normalize(CITATION.matcher(unit.text()).replaceAll(""));
            // An extractive unit is already a literal witness; a malformed quote field must not negate it.
            // This still requires a positive semantic judgment, rather than just page presence.
            boolean literalUnit = unit.evidence().size() == 1 && !factualText.isBlank() &&
                normalize(unit.evidence().getFirst().trecho()).contains(factualText);
            // A verbatim unit can supply its own witness, but it does not bypass
            // actor/action/modality checks or the model's semantic judgment.
            if (literalUnit) witnesses.addAll(AttributionEvidence.contextualWitnesses(unit.evidence().getFirst(),factualText));
            List<String> attributionIssues = AttributionEvidence.unsupported(factualText, witnesses);
            if (debug && !attributionIssues.isEmpty()) System.out.println(
                "[RAG] atribuicao_rejeitada unidade=" + unit.id() + " motivo=" + String.join("; ",attributionIssues));
            if (supported && comparisonGrounded && attributionIssues.isEmpty() &&
                    (literalUnit || (quotesValid && covered.containsAll(unit.evidence()))))
                approved.add(unit.text());
            else issues.add("Unidade " + unit.id() + ": " + c.path("reason").asText("Sem apoio verificavel") +
                (supported && !quotesValid ? " (citacao literal da auditoria invalida: " + invalidQuotes + "; confira a pagina de cada afirmacao)" : "") +
                (!comparisonGrounded ? " (comparacao sem evidencia para: " + missingAnchors + ")" : "") +
                (!attributionIssues.isEmpty() ? " (" + String.join("; ", attributionIssues) + ")" : ""));
        }
        for (JsonNode item : verdict.path("missing")) {
            if (!item.isTextual()) return new Audit(List.of(), List.of("Campo missing invalido"), List.of(), false);
            if (!item.asText().isBlank()) missing.add(item.asText());
        }
        return new Audit(List.copyOf(approved), List.copyOf(issues), List.copyOf(missing), true);
    }
    // Apply this conservative guard only to comparisons with two identifiable
    // grammatical subjects. It does not claim to establish semantic entailment:
    // the LLM auditor still has to verify the actual claim.
    private static final Pattern COMPARATIVE = Pattern.compile(
        "(?iu)\\b(?:enquanto|ao\\s+passo\\s+que|por\\s+outro\\s+lado|j[aá])\\b");
    private static final Pattern PREDICATE = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}])(?:é|são|era|eram|está|estão|foi|foram|será|serão|atua|atuam|"
        + "deve|devem|pode|podem|precisa|precisam|representa|representam|"
        + "acompanha|acompanham|orienta|orientam|tem|têm)(?![\\p{L}\\p{N}])");

    /** Returns identifiable subject names missing from the auditor's quoted evidence. */
    static List<String> missingComparisonAnchors(Unit unit, JsonNode claim) {
        String factual = CITATION.matcher(unit.text()).replaceAll(" ");
        String[] clauses = COMPARATIVE.split(factual);
        if (clauses.length < 2) return List.of();
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        for (String clause : clauses) {
            String stripped = clause.replaceFirst("^\\s*\\d+[.)]\\s*", "").trim();
            Matcher predicate = PREDICATE.matcher(stripped);
            if (!predicate.find()) continue;
            String subject = stripped.substring(0, predicate.start()).trim()
                .replaceFirst("(?iu)^(?:o|a|os|as|um|uma)\\s+", "");
            String normalized = fold(subject);
            if (normalized.matches("[a-z0-9 ]+") && normalized.split(" ").length >= 2
                    && normalized.split(" ").length <= 6) anchors.add(normalized);
        }
        // Never reject on the basis of guessed subjects: require two distinct
        // and syntactically recognizable clauses before enabling this guard.
        if (anchors.size() < 2) return List.of();
        StringBuilder quotationText = new StringBuilder();
        if (claim.path("quotes").isArray()) {
            for (JsonNode quote : claim.path("quotes")) {
                // Only text within this unit's explicitly cited pages counts.
                String doc = quote.path("documento").asText();
                int page = quote.path("pagina").asInt(-1);
                String text = quote.path("texto").asText();
                boolean valid = unit.evidence().stream().anyMatch(source ->
                    source.documento().equals(doc) && source.pagina() == page &&
                    !text.isBlank() && normalize(source.trecho()).contains(normalize(text)));
                if (valid) quotationText.append(' ').append(fold(text));
            }
        }
        String quoted = quotationText.toString();
        return anchors.stream().filter(anchor -> !quoted.contains(anchor)).toList();
    }

    private static String fold(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").trim();
    }

    static String normalize(String text) { return text.replaceAll("\\s+", " ").trim(); }
}
