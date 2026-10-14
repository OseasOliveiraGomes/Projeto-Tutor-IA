package br.com.sylph.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presentation-only removal of positively identified PDF folios.
 * The evidence, audit input and CSV text remain untouched. Equal numeric values
 * alone never constitute proof: geometry, recurring folios and literal context
 * must identify the particular occurrence in the approved text.
 */
final class PdfPresentationArtifacts {
    private PdfPresentationArtifacts() { }
    private static final Pattern TOKEN = Pattern.compile("\\S+");
    private static final String MARKER = "SYLPH_PHYSICAL_FOLIO_MARKER";
    private static final Map<FileVersion, List<PageProof>> CACHE = new LinkedHashMap<>();
    private record FileVersion(Path path, long size, long modified) { }
    private record Folio(int offset, String number, float x, float y, float font,
                         float pageWidth, float pageHeight) { }
    private record PageProof(int page, String text, List<Folio> folios) { }
    private record Artifact(int offset, String number) { }

    static String clean(String approvedBody, List<RagService.Source> citedSources) {
        if (approvedBody == null || approvedBody.isBlank() || citedSources == null || citedSources.isEmpty())
            return approvedBody;
        try (var paths = Files.list(Path.of("dados"))) {
            return clean(approvedBody, citedSources, paths.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                .sorted().toList());
        } catch (IOException | SecurityException ex) {
            // A missing/unreadable PDF cannot authorize a presentation change.
            return approvedBody;
        }
    }

    /** Explicit PDF input also makes the geometry proof independently testable. */
    static String clean(String approvedBody, List<RagService.Source> citedSources, List<Path> pdfPaths) {
        if (approvedBody == null || approvedBody.isBlank() || citedSources == null || citedSources.isEmpty())
            return approvedBody;
        String normalizedBody = normalize(approvedBody);
        if (!normalizedBody.matches("(?s).*\\p{L}.*")) return approvedBody;
        Set<Integer> removals = new TreeSet<>(Comparator.reverseOrder());
        for (Path path : pdfPaths) {
            List<PageProof> pages;
            try { pages = proofs(path); }
            catch (IOException | RuntimeException ex) { continue; }
            for (RagService.Source source : citedSources) {
                if (source.pagina() < 1 || source.pagina() > pages.size()) continue;
                PageProof page = pages.get(source.pagina() - 1);
                String sourceText = normalize(source.trecho());
                int sourceStart = page.text().indexOf(sourceText);
                // Resolve differently named PDF/CSV documents by literal page
                // content, never by guessing aliases or relying on a page number.
                if (sourceText.isBlank() || sourceStart < 0 ||
                        page.text().indexOf(sourceText, sourceStart + 1) >= 0) continue;
                for (Artifact artifact : artifacts(page, pages)) {
                    int relative = artifact.offset() - sourceStart;
                    if (relative < 0 || relative + artifact.number().length() > sourceText.length()) continue;
                    String anchor = anchor(sourceText, relative, artifact.number().length());
                    if (anchor.isBlank() || !unique(sourceText, anchor)) continue;
                    int bodyAnchor = normalizedBody.indexOf(anchor);
                    if (bodyAnchor < 0 || !unique(normalizedBody, anchor)) continue;
                    // Locate the target relative to the exact context, instead
                    // of deleting every occurrence of the physical page value.
                    int sourceAnchor = sourceText.indexOf(anchor);
                    int bodyNumber = bodyAnchor + relative - sourceAnchor;
                    if (bodyNumber >= 0 && normalizedBody.startsWith(artifact.number(), bodyNumber))
                        removals.add(bodyNumber);
                }
            }
        }
        if (removals.isEmpty()) return approvedBody;
        // Map normalized character offsets back to the original rendering text;
        // every character other than the folio and its following separator stays.
        int[] offsets = originalOffsets(approvedBody);
        StringBuilder rendered = new StringBuilder(approvedBody);
        for (int position : removals) {
            int end = position;
            while (end < normalizedBody.length() && Character.isDigit(normalizedBody.charAt(end))) end++;
            int originalStart = offsets[position];
            int originalEnd = offsets[end - 1] + 1;
            while (originalEnd < rendered.length() && Character.isWhitespace(rendered.charAt(originalEnd))) originalEnd++;
            rendered.delete(originalStart, originalEnd);
        }
        return rendered.toString();
    }

    private static String anchor(String source, int offset, int length) {
        List<int[]> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(source);
        while (matcher.find()) tokens.add(new int[]{matcher.start(), matcher.end()});
        for (int i = 0; i < tokens.size(); i++) {
            int[] token = tokens.get(i);
            if (token[0] != offset || token[1] != offset + length) continue;
            // Two textual tokens on either side identify a context, not a loose
            // number at the edge of an answer or citation.
            if (i < 2 || i + 2 >= tokens.size()) return "";
            String left = source.substring(tokens.get(i - 2)[0], offset);
            String right = source.substring(offset + length, tokens.get(i + 2)[1]);
            if (!left.matches("(?s).*\\p{L}.*") || !right.matches("(?s).*\\p{L}.*")) return "";
            return source.substring(tokens.get(i - 2)[0], tokens.get(i + 2)[1]);
        }
        return "";
    }

    private static boolean unique(String haystack, String needle) {
        int first = haystack.indexOf(needle);
        return first >= 0 && haystack.indexOf(needle, first + 1) < 0;
    }

    private static synchronized List<PageProof> proofs(Path path) throws IOException {
        FileVersion key = new FileVersion(path.toAbsolutePath().normalize(), Files.size(path),
            Files.getLastModifiedTime(path).toMillis());
        List<PageProof> cached = CACHE.get(key);
        if (cached != null) return cached;
        List<PageProof> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                var stripper = new FolioStripper(page, document.getPage(page - 1).getCropBox().getWidth(),
                    document.getPage(page - 1).getCropBox().getHeight());
                var writer = new StringWriter();
                stripper.writeText(document, writer);
                String raw = writer.toString();
                String cleaned = normalize(PdfPages.clean(raw));
                List<Folio> located = new ArrayList<>();
                for (Folio folio : stripper.folios) {
                    String marked = raw.substring(0, folio.offset()) + MARKER +
                        raw.substring(folio.offset() + folio.number().length());
                    String normalizedMarked = normalize(PdfPages.clean(marked));
                    // PdfPages already removes standalone extraction lines.
                    // Only folios actually retained inside source text qualify.
                    if (!normalizedMarked.replace(MARKER, folio.number()).equals(cleaned)) continue;
                    located.add(new Folio(normalizedMarked.indexOf(MARKER), folio.number(),
                        folio.x(), folio.y(), folio.font(), folio.pageWidth(), folio.pageHeight()));
                }
                // Geometry candidates are kept separately from textual offsets:
                // an already-cleaned neighbor still proves recurring layout.
                pages.add(new PageProof(page, cleaned, mergeGeometry(located, stripper.folios)));
            }
        }
        List<PageProof> result = List.copyOf(pages);
        if (CACHE.size() >= 16) CACHE.clear();
        CACHE.put(key, result);
        return result;
    }

    private static List<Folio> mergeGeometry(List<Folio> located, List<Folio> all) {
        List<Folio> result = new ArrayList<>(located);
        for (Folio geometry : all) {
            boolean present = located.stream().anyMatch(f -> f.x() == geometry.x() && f.y() == geometry.y());
            if (!present) result.add(new Folio(-1, geometry.number(), geometry.x(), geometry.y(),
                geometry.font(), geometry.pageWidth(), geometry.pageHeight()));
        }
        return List.copyOf(result);
    }

    private static List<Artifact> artifacts(PageProof page, List<PageProof> pages) {
        List<Artifact> artifacts = new ArrayList<>();
        for (Folio candidate : page.folios()) {
            if (candidate.offset() < 0) continue;
            long neighbors = pages.stream().filter(p -> p.page() != page.page() &&
                Math.abs(p.page() - page.page()) <= 2).filter(p -> p.folios().stream().anyMatch(other ->
                    Math.abs(candidate.x() / candidate.pageWidth() - other.x() / other.pageWidth()) <= .04 &&
                    Math.abs(candidate.y() / candidate.pageHeight() - other.y() / other.pageHeight()) <= .04 &&
                    Math.abs(candidate.font() - other.font()) <= 1)).count();
            if (neighbors >= 2) artifacts.add(new Artifact(candidate.offset(), candidate.number()));
        }
        return artifacts;
    }

    private static final class FolioStripper extends PDFTextStripper {
        final int page;
        final float width, height;
        final List<Folio> folios = new ArrayList<>();
        FolioStripper(int page, float width, float height) throws IOException {
            this.page = page; this.width = width; this.height = height;
            setSortByPosition(true); setStartPage(page); setEndPage(page);
        }
        @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
            String number = Integer.toString(page);
            if (text.equals(number) && !positions.isEmpty()) {
                TextPosition first = positions.getFirst();
                boolean margin = first.getYDirAdj() >= height * .90f &&
                    (first.getXDirAdj() <= width * .08f || first.getXDirAdj() >= width * .92f);
                boolean small = positions.stream().allMatch(p -> p.getFontSizeInPt() <= 12 &&
                    p.getHeightDir() <= height * .025f);
                if (margin && small && getOutput() instanceof StringWriter writer)
                    folios.add(new Folio(writer.getBuffer().length(), number, first.getXDirAdj(),
                        first.getYDirAdj(), first.getFontSizeInPt(), width, height));
            }
            super.writeString(text, positions);
        }
    }

    private static String normalize(String text) { return text.replaceAll("\\s+", " ").trim(); }
    private static int[] originalOffsets(String text) {
        List<Integer> offsets = new ArrayList<>();
        boolean whitespace = false;
        for (int i = 0; i < text.length(); i++) {
            char c=text.charAt(i);
            // Match Java's default regex \\s used by normalize(), rather than
            // collapsing additional Unicode characters that stayed in the text.
            if (c==' ' || c>='\t' && c<='\r') {
                if (!offsets.isEmpty() && !whitespace) offsets.add(i);
                whitespace = true;
            } else {
                offsets.add(i); whitespace = false;
            }
        }
        if (whitespace && !offsets.isEmpty()) offsets.removeLast();
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }
}
