package br.com.sylph.service;

import java.util.*;
import java.util.regex.*;

/** Presentation of already approved text only; never changes evidence or audit decisions. */
final class FinalAnswerPresentation {
    private FinalAnswerPresentation() { }
    private record Display(String body,List<String> citations,List<RagService.Source> sources) {
        String text() { return body + (citations.isEmpty() ? "" : " " + String.join(" ",citations)); }
        Set<String> provenance() {
            return sources.stream().map(s -> s.documento()+":"+s.pagina())
                .collect(java.util.stream.Collectors.toSet());
        }
    }
    private static final Pattern FORMAT_SUMMARY = Pattern.compile(
        "(?iu)^(?:os )?formatos(?: de arquivos?)?(?: aceitos| permitidos)? (?:sao|são|:|incluem)\\s+(.+?)[.!]?$|"
        + "^(?:os )?formatos(?: de arquivos?)?(?: aceitos| permitidos)?\\s*:\\s*(.+?)[.!]?$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FORMAT_LIST = Pattern.compile(
        "(?iu)\\b(?:no|nos|em) formatos?\\s+([\\p{L}\\d]+(?:\\s*(?:,| e )\\s*[\\p{L}\\d]+)+)");

    static String render(List<String> approved,List<RagService.Source> sources) {
        List<Display> kept = new ArrayList<>();
        boolean changed = false;
        for (String raw : approved) {
            for (Display item : split(raw,sources)) {
                String clean = PdfPresentationArtifacts.clean(item.body(),item.sources());
                changed |= !clean.equals(item.body());
                Display candidate = new Display(clean,item.citations(),item.sources());
                boolean duplicate = false;
                for (int i=0;i<kept.size();i++) {
                    Display previous = kept.get(i);
                    String a = comparable(previous.body()), b = comparable(candidate.body());
                    // Exact repetitions can consolidate all citations; different wording
                    // can only consolidate when it refers to the same cited sources.
                    boolean sameText = a.equals(b);
                    boolean sameSources = previous.provenance().equals(candidate.provenance());
                    boolean aCovered = sameSources && (completeSentenceInside(a,b) || formatSummaryInside(a,b));
                    boolean bCovered = sameSources && (completeSentenceInside(b,a) || formatSummaryInside(b,a));
                    if (sameText || aCovered || bCovered) {
                        Display richer = aCovered && !bCovered ? candidate : previous;
                        LinkedHashSet<String> citations = new LinkedHashSet<>(previous.citations());
                        citations.addAll(candidate.citations());
                        LinkedHashSet<RagService.Source> provenance = new LinkedHashSet<>(previous.sources());
                        provenance.addAll(candidate.sources());
                        kept.set(i,new Display(richer.body(),List.copyOf(citations),List.copyOf(provenance)));
                        duplicate = true; changed = true; break;
                    }
                }
                if (!duplicate) kept.add(candidate);
            }
        }
        // Preserve untouched answers byte-for-byte, including existing Markdown/layout.
        return changed ? String.join("\n\n",kept.stream().map(Display::text).toList()) : String.join("\n\n",approved);
    }

    private static List<Display> split(String text,List<RagService.Source> sources) {
        List<Display> units = new ArrayList<>();
        Matcher matcher = AnswerEvidence.CITATION.matcher(text);
        int start=0;
        while (matcher.find()) {
            int end=matcher.end();
            List<String> citations = new ArrayList<>(List.of(matcher.group()));
            List<RagService.Source> evidence = new ArrayList<>();
            addSource(matcher,sources,evidence);
            while (true) {
                Matcher next = AnswerEvidence.CITATION.matcher(text);
                if (!next.find(end) || !text.substring(end,next.start()).isBlank()) break;
                citations.add(next.group()); addSource(next,sources,evidence); end=next.end();
            }
            String body = text.substring(start,matcher.start()).trim();
            if (!body.isBlank()) units.add(new Display(body,List.copyOf(citations),List.copyOf(evidence)));
            while (end<text.length() && ".!?…".indexOf(text.charAt(end))>=0) end++;
            start=end; matcher.region(end,text.length());
        }
        String tail=text.substring(start).trim();
        if (!tail.isBlank()) units.add(new Display(tail,List.of(),List.of()));
        return units;
    }

    private static void addSource(Matcher citation,List<RagService.Source> sources,List<RagService.Source> found) {
        sources.stream().filter(s -> s.documento().equals(citation.group(1).trim()) &&
            Integer.toString(s.pagina()).equals(citation.group(2))).findFirst().ifPresent(s -> {
                if (!found.contains(s)) found.add(s);
            });
    }

    private static String comparable(String body) {
        return body.replaceFirst("^\\s*(?:\\d+[.)]\\s+|[-*]\\s+)", "")
            .replaceAll("\\s+", " ").trim().replaceFirst("\\.$", "");
    }

    private static boolean completeSentenceInside(String shorter,String longer) {
        if (shorter.isBlank() || shorter.equals(longer)) return false;
        return Pattern.compile("(?:^|[.!?;]\\s+)"+Pattern.quote(shorter)+"(?:[.!;]|$)")
            .matcher(longer).find();
    }

    /** Only a bare file-format enumeration can be covered by a richer approved clause. */
    private static boolean formatSummaryInside(String shorter,String longer) {
        Matcher summary = FORMAT_SUMMARY.matcher(shorter);
        if (!summary.matches()) return false;
        String list = summary.group(1)!=null ? summary.group(1) : summary.group(2);
        List<String> formats = formats(list);
        if (formats.isEmpty()) return false;
        Matcher rich=FORMAT_LIST.matcher(longer);
        if (!rich.find() || !formats.equals(formats(rich.group(1)))) return false;
        int clauseStart=Math.max(Math.max(longer.lastIndexOf('.',rich.start()),longer.lastIndexOf('!',rich.start())),
            Math.max(longer.lastIndexOf('?',rich.start()),longer.lastIndexOf(';',rich.start())))+1;
        String prefix=longer.substring(clauseStart,rich.start()).trim();
        // Never equate permission with a negation, prohibition or obligation.
        return prefix.matches("(?iu).*\\barquivos\\b.*\\b(?:poderão|podem)\\s+ser\\s+enviados\\s*") &&
            !prefix.matches("(?iu).*\\b(?:não|nao|proibid[oa]s?|exceto)\\b.*");
    }

    private static List<String> formats(String text) {
        if (!text.matches("[A-Z0-9]+(?:\\s*(?:,| e )\\s*[A-Z0-9]+)+")) return List.of();
        return Arrays.stream(text.split("\\s*(?:,| e )\\s*")).map(String::trim).toList();
    }

    static String unknown(String question) {
        return "Não encontrei informação suficiente para responder à pergunta: “"+question.trim()+
            "”. Recomendo confirmar com a coordenação do polo.";
    }

    static String notice(String question,List<String> missing,List<String> issues,boolean technicalFailure) {
        LinkedHashSet<String> labels=new LinkedHashSet<>();
        for (String item:missing) if (item!=null && !item.isBlank() &&
                !item.matches("(?iu)\\s*(?:sem informacao|sem informação|nenhuma informacao|nenhuma informação)\\s*")) {
            String label=item.trim().replaceFirst("[.!?]+$", "");
            // 'Not identified' is already expressed by the notice itself. Only
            // remove this terminal status, retaining the item and its qualifiers.
            label=label.replaceFirst("(?iu)\\s+(?:não|nao)\\s+(?:foi\\s+)?(?:identificad[oa]|comprovad[oa]|confirmad[oa])(?:\\s+expressamente|\\s+explicitamente)?$", "");
            if (!label.isBlank()) labels.add(label);
        }
        if (labels.isEmpty()) for(String issue:issues) {
            int marker=issue.indexOf("atribuicao sem vinculo agente-acao/modalidade:");
            if (marker<0) continue;
            String clause=issue.substring(marker).replaceFirst("^[^:]+:\\s*", "").replaceFirst("\\)$", "");
            Matcher action=Pattern.compile("(?iu)\\b(respons[aá]vel\\s+(?:por|pelo|pela)|encarregad[oa]\\s+de|deve|devem|precisa|precisam|pode|podem)\\s+(.+)$")
                .matcher(clause);
            if(action.find()) {
                String modal=action.group(1).toLowerCase(Locale.ROOT);
                String prefix=modal.startsWith("pod") ? "quem pode " :
                    modal.startsWith("dev") || modal.startsWith("precis") ? "quem deve " : "responsável pela ação de ";
                labels.add(prefix+action.group(2).trim().replaceFirst("[.!?]+$", ""));
            }
        }
        String subject=labels.isEmpty() ? "a pergunta: “"+question.trim()+"”" : String.join("; ",labels);
        String reason=technicalFailure ? " devido a uma falha na comunicação com o modelo" : " nas evidências consultadas";
        return "Não foi possível confirmar"+reason+": "+subject+". Recomendo confirmar esses pontos com a coordenação do polo.";
    }
}
