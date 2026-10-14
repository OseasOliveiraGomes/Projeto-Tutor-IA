package br.com.sylph.service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * Conservative witness check for identifiable Portuguese agent/action relations.
 * This is an additional rejection guard, never a replacement for semantic auditing.
 * An actor mentioned as a form field, object or beneficiary is not an agent witness.
 */
final class AttributionEvidence {
    private AttributionEvidence() { }
    enum Mode { ACTION, RESPONSIBILITY, OBLIGATION, PERMISSION, PROHIBITION, EXEMPTION }
    record Relation(String actor, String action, Mode mode, boolean negative, String condition, String clause) { }
    private static final String AGENT = "(?:responsave(?:l|is)|encarregad[oa]s?|incumbid[oa]s?)";
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "^(.+?)\\s+(?:(?:e|sao|sera|serao)\\s+)?(nao\\s+)?(" + AGENT +
        "|obrigad[oa]s?|autorizad[oa]s?)\\s+(?:por|pelo|pela|pelos|pelas|de|a|para)\\s+(.+)$");
    private static final Pattern MODAL = Pattern.compile(
        "^(.+?)\\s+(nao\\s+)?(deve|devem|devera|deverao|precisa|precisam|tem que|tem de|"
        + "pode|podem|podera|poderao)\\s+(.+)$");
    private static final Pattern PASSIVE = Pattern.compile(
        "^(?:ser\\s+)?([a-z]+(?:ad[oa]s?|id[oa]s?))\\b(.*?)\\s+(?:por|pelo|pela|pelos|pelas)\\s+(.+)$");
    private static final Pattern PASSIVE_SENTENCE = Pattern.compile(
        "^(.+?)\\s+(?:e|sao|foi|foram|sera|serao)\\s+(nao\\s+)?(.+)$");
    private static final Pattern REVERSE = Pattern.compile(
        "^(.+?)\\s+(?:e|sao)\\s+(?:da?\\s+)?responsabilidade\\s+(?:de|da|do|dos|das)\\s+(.+)$");
    private static final Pattern ROLE = Pattern.compile(
        "^(?:o|a|os|as)\\s+(?:responsavel|responsaveis)\\s+(?:pelo|pela|por)\\s+(.+?)\\s+(?:e|sao)\\s+(.+)$");
    private static final Pattern DUTY = Pattern.compile(
        "^(?:cabe|compete)\\s+(?:a|ao|aos|as)\\s+(.+?)\\s+([a-z]+(?:ar|er|ir)\\b.*)$");
    private static final Pattern ACTIVE = Pattern.compile(
        "^(.+?)\\s+(nao\\s+)?((?:envi|solicit|cadastr|preench|assin|aprov|valid|"
        + "orient|acompanh|realiz|efetu|atualiz|autoriz|submet|corrig)[a-z]*)\\b(.*)$");
    private static final Pattern IDENTITY = Pattern.compile("^(.+?)\\s+(?:e|sao)\\s+((?:o|a|os|as)\\s+.+)$");
    private static final Pattern CONDITION = Pattern.compile("\\b(?:se|caso|quando|desde que|exceto|salvo)\\b.*$");
    private static final Pattern COORDINATED = Pattern.compile(
        "\\s+(?:e|mas)\\s+(?=(?:o|a|os|as|um|uma)\\s+[a-z ]{1,70}?\\s+"
        + "(?:(?:nao\\s+)?(?:deve|devem|precisa|precisam|pode|podem)\\b|e\\s+(?:nao\\s+)?" + AGENT + "))");
    private static final Pattern ROLE_LABEL = Pattern.compile(
        "^(?:o |a )?(?:responsavel|responsaveis)\\s+(?:pelo|pela|por)\\s+(.+?)\\s*:\\s*(.+)$");
    private static final Pattern STRONG_ASSIGNMENT = Pattern.compile(
        "\\b(?:" + AGENT + "|obrigad[oa]s?|autorizad[oa]s?)\\s+(?:por|pelo|pela|de|a|para)\\b");
    private static final Set<String> LIGHT_VERBS = Set.of("realizar","realiza","realizado","realizada",
        "efetuar","efetua","efetuado","efetuada","fazer","feito","feita","proceder");
    private static final Set<String> FUNCTION_WORDS = Set.of("o","a","os","as","um","uma","de","do","da",
        "dos","das","ao","aos","em","no","na","nos","nas","por","pelo","pela","pelos","pelas","para");

    static List<String> unsupported(String claim, List<String> witnesses) {
        List<Relation> claims = relations(claim);
        if (claims.isEmpty()) {
            // Formatting or unfamiliar syntax must not silently erase a strong attribution.
            return STRONG_ASSIGNMENT.matcher(fold(claim)).find()
                ? List.of("atribuicao nao interpretavel com seguranca: " + AnswerEvidence.normalize(claim)) : List.of();
        }
        List<Relation> evidence = witnesses.stream().flatMap(w -> relations(w).stream()).toList();
        Map<String,Set<String>> identities = new HashMap<>();
        for (String witness : witnesses) for (String clause : fold(witness).split("[.!?;\\n]+")) {
            Matcher identity = IDENTITY.matcher(clause.trim());
            if (!identity.matches()) continue;
            String left = subject(identity.group(1)), right = subject(identity.group(2));
            // Explicit noun-role identity, never mere proximity or inferred pronoun resolution.
            if (!left.isBlank() && !right.isBlank() &&
                    (right.startsWith("responsavel ") || right.contains(" responsavel "))) {
                identities.computeIfAbsent(left,k -> new HashSet<>()).add(right);
                identities.computeIfAbsent(right,k -> new HashSet<>()).add(left);
            }
        }
        List<String> issues = new ArrayList<>();
        for (Relation relation : claims) {
            boolean found = evidence.stream().anyMatch(w ->
                sameActor(relation.actor(),w.actor(),identities) && relation.action().equals(w.action()) &&
                relation.negative() == w.negative() && entails(w.mode(), relation.mode()) &&
                (w.condition().isBlank() || w.condition().equals(relation.condition())));
            if (!found) issues.add("atribuicao sem vinculo agente-acao/modalidade: " + relation.clause());
        }
        return List.copyOf(issues);
    }

    private static boolean sameActor(String left,String right,Map<String,Set<String>> identities) {
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(); pending.add(left);
        while (!pending.isEmpty()) {
            String actor = pending.removeFirst();
            if (actor.equals(right)) return true;
            if (visited.add(actor)) pending.addAll(identities.getOrDefault(actor,Set.of()));
        }
        return false;
    }

    /** Retain the source sentence around a quotation so negation/conditions cannot be cropped away. */
    static List<String> contextualWitnesses(RagService.Source source,String quote) {
        String original = AnswerEvidence.normalize(source.trecho());
        String needle = AnswerEvidence.normalize(quote);
        if (needle.isBlank()) return List.of();
        List<String> contexts = new ArrayList<>();
        int position = original.indexOf(needle);
        while (position >= 0) {
            int start = position, end = position + needle.length();
            while (start > 0 && ".!?;".indexOf(original.charAt(start-1)) < 0) start--;
            while (end < original.length() && ".!?;".indexOf(original.charAt(end-1)) < 0) end++;
            contexts.add(original.substring(start,end).trim());
            position = original.indexOf(needle,position+1);
        }
        return List.copyOf(contexts);
    }

    private static boolean entails(Mode evidence, Mode claim) {
        return evidence == claim || (claim == Mode.ACTION &&
            (evidence == Mode.RESPONSIBILITY || evidence == Mode.OBLIGATION))
            || (claim == Mode.RESPONSIBILITY && evidence == Mode.OBLIGATION);
    }

    static List<Relation> relations(String text) {
        String folded = fold(AnswerEvidence.CITATION.matcher(text).replaceAll(" "));
        List<Relation> result = new ArrayList<>();
        folded = COORDINATED.matcher(folded).replaceAll("; ");
        for (String raw : folded.split("[.!?;\\n]+|\\b(?:enquanto|ao passo que|por outro lado)\\b")) {
            String clause = raw.replaceFirst("^\\s*(?:\\d+[.)]?|[-*•✓])\\s*", "").trim();
            if (clause.isBlank()) continue;
            Matcher role = ROLE_LABEL.matcher(clause);
            if (role.matches() && role.group(2).matches("[a-z ]{1,100}") &&
                    !role.group(2).matches(".*\\b(?:e|sao|deve|devem|pode|podem|responsavel)\\b.*")) {
                add(result,role.group(2),role.group(1),Mode.RESPONSIBILITY,false,clause);
                // The left side is a role label, not an additional actor/action sentence.
                continue;
            }
            // A colon can introduce either a heading OR factual details. Examine
            // both sides; never discard a proposition before the last colon.
            for (String fragment : clause.split(":")) parseClause(fragment.trim(),result);
        }
        return List.copyOf(result);
    }

    private static void parseClause(String clause,List<Relation> result) {
            if (clause.isBlank()) return;
            Matcher match = MODAL.matcher(clause);
            if (match.matches()) {
                Mode mode = match.group(3).startsWith("pod") ? Mode.PERMISSION : Mode.OBLIGATION;
                if (match.group(2)!=null) mode = match.group(3).startsWith("precis") ||
                    match.group(3).startsWith("tem ") ? Mode.EXEMPTION : Mode.PROHIBITION;
                Matcher passive = PASSIVE.matcher(match.group(4));
                if (passive.matches()) {
                    String action = LIGHT_VERBS.contains(passive.group(1)) ? match.group(1) : passive.group(1);
                    add(result,passive.group(3),action,mode,match.group(2)!=null,clause);
                } else if (!match.group(4).startsWith("ser ")) {
                    add(result,match.group(1),match.group(4),mode,match.group(2)!=null,clause);
                }
                return;
            }
            match = ASSIGNMENT.matcher(clause);
            if (match.matches()) {
                String kind = match.group(3);
                Mode mode = kind.startsWith("obrigad") ? Mode.OBLIGATION :
                    kind.startsWith("autorizad") ? Mode.PERMISSION : Mode.RESPONSIBILITY;
                add(result,match.group(1),match.group(4),mode,match.group(2)!=null,clause);
                return;
            }
            match = ROLE.matcher(clause);
            if (match.matches()) { add(result,match.group(2),match.group(1),Mode.RESPONSIBILITY,false,clause); return; }
            match = REVERSE.matcher(clause);
            if (match.matches()) { add(result,match.group(2),match.group(1),Mode.RESPONSIBILITY,false,clause); return; }
            match = DUTY.matcher(clause);
            if (match.matches()) { add(result,match.group(1),match.group(2),Mode.OBLIGATION,false,clause); return; }
            match = PASSIVE_SENTENCE.matcher(clause);
            if (match.matches()) {
                Matcher passive = PASSIVE.matcher(match.group(3));
                if (passive.matches()) {
                    String verb = passive.group(1);
                    String action = LIGHT_VERBS.contains(verb) ? match.group(1) : verb;
                    add(result,passive.group(3),action,Mode.ACTION,match.group(2)!=null,clause);
                }
                // An agentless passive says how an action happens, not who performs it.
                return;
            }
            match = ACTIVE.matcher(clause);
            if (match.matches()) add(result,match.group(1),match.group(3)+match.group(4),Mode.ACTION,match.group(2)!=null,clause);
    }

    private static void add(List<Relation> result,String actor,String action,Mode mode,boolean negative,String clause) {
        String subject = subject(actor);
        // Never equate a nested noun phrase ('nome do candidato') with its complement.
        // A long introductory clause must not turn a recognized attribution into
        // an empty check. An unmatched subject is rejected conservatively instead.
        if (subject.isBlank()) return;
        String actionStem = actionStem(action);
        Matcher condition = CONDITION.matcher(clause);
        if (!actionStem.isBlank()) result.add(new Relation(subject,actionStem,mode,negative,
            condition.find() ? condition.group() : "",clause));
    }

    private static String subject(String actor) {
        String subject = actor.replaceFirst("\\s+(?:por sua vez|por sua parte)\\s*$", "").trim()
            .replaceFirst("^(?:o|a|os|as|um|uma)\\s+", "").trim();
        // PDF headings may repeat the noun phrase immediately before the sentence.
        return subject.replaceFirst("^(.+?)\\s+\\1\\b", "$1");
    }

    private static String actionStem(String action) {
        List<String> words = Arrays.stream(action.split("[^a-z0-9]+"))
            .filter(w -> !w.isBlank() && !FUNCTION_WORDS.contains(w)).toList();
        if (words.isEmpty()) return "";
        int index = LIGHT_VERBS.contains(words.getFirst()) && words.size()>1 ? 1 : 0;
        String word = words.get(index);
        // Procedural verb/noun variants (envia/enviar/envio; cadastra/cadastrar/cadastro).
        String stem = word.replaceFirst("(?:amentos?|imentos?|acoes|acao|coes|cao|ados?|adas?|idos?|idas?|"
            + "arao|erao|irao|aram|eram|iram|am|ar|er|ir|ou|a|e|o)$", "");
        return stem.length() < 3 ? word : stem;
    }

    private static String fold(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
            .replaceAll("[*_`~]", "").replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
            .replaceAll("\\p{M}+", "").replaceAll("[,()]", " ").replaceAll("[\\t\\r ]+", " ").trim();
    }
}
