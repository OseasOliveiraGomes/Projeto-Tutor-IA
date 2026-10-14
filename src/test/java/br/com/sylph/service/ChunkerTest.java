package br.com.sylph.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {
    @Test void recursiveRespectsPageTextAndMaxSize() {
        TextChunker chunks = new TextChunker();
        String text = ("O manual define prazo de 4 dias úteis. O polo cadastra a empresa.\n").repeat(80);
        var pieces = chunks.recursive(text);
        assertTrue(pieces.size()>1);
        assertTrue(pieces.stream().allMatch(p -> p.length() <= TextChunker.RECURSIVE_MAX));
        assertTrue(pieces.getFirst().contains("prazo de 4 dias úteis"));
    }
    @Test void cleanRemovesIsolatedPageNumbersWithoutDeletingPhrases() {
        assertEquals("O prazo é de 4 dias úteis.", PdfPages.clean("Público\nO prazo é de 4 dias úteis.\n4\n"));
    }
}
