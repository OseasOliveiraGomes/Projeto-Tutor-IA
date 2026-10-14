package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.config.SettingsService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevTerminalTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final BasePreparation preparation = mock(BasePreparation.class);
    private final RagService rag = mock(RagService.class);
    private final DevTerminal terminal = new DevTerminal(settings, preparation, rag);

    private record Output(String console, String errors) { }

    private Output execute(String input, String... args) throws IOException {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        terminal.execute(args,
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(console, true, StandardCharsets.UTF_8),
            new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Output(console.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private static RagService.Answer answer(String text, int page) {
        return new RagService.Answer(text + " [Fonte: Manual de Estágio | Página: " + page + "]",
            List.of(new RagService.Source("Manual de Estágio", page, "Evidência original")), true);
    }

    @Test void acceptsSuccessiveFreeQuestionsAndPrintsResponsesAndSources() throws Exception {
        when(rag.ready("recursivo")).thenReturn(true);
        when(rag.answer("Qual é o prazo?", "recursivo")).thenReturn(answer("Quatro dias úteis.", 4));
        when(rag.answer("Quais formatos?", "recursivo")).thenReturn(answer("PDF e JPG.", 34));

        Output result = execute("1\n\n  Qual é o prazo?  \nQuais formatos?\nsair\nPergunta após sair\n");

        assertTrue(result.console().contains("Quatro dias úteis. [Fonte: Manual de Estágio | Página: 4]"));
        assertTrue(result.console().contains("PDF e JPG. [Fonte: Manual de Estágio | Página: 34]"));
        assertTrue(result.console().contains("Fonte recuperada: Manual de Estágio pág. 4"));
        assertTrue(result.console().contains("Fonte recuperada: Manual de Estágio pág. 34"));
        assertEquals("", result.errors());
        var order = inOrder(rag);
        order.verify(rag).ready("recursivo");
        order.verify(rag).answer("Qual é o prazo?", "recursivo");
        order.verify(rag).answer("Quais formatos?", "recursivo");
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @Test void reportsFailureAndContinuesWithTheNextQuestion() throws Exception {
        when(rag.ready("recursivo")).thenReturn(true);
        when(rag.answer("Primeira pergunta", "recursivo")).thenThrow(new IOException("Ollama indisponível"));
        when(rag.answer("Segunda pergunta", "recursivo")).thenReturn(answer("Resposta validada.", 15));

        Output result = execute("1\nPrimeira pergunta\nSegunda pergunta\nsair\n");

        assertTrue(result.errors().contains("Falha: Ollama indisponível"));
        assertTrue(result.console().contains("Resposta validada."));
        assertTrue(result.console().contains("Fonte recuperada: Manual de Estágio pág. 15"));
        verify(rag).ready("recursivo");
        verify(rag).answer("Primeira pergunta", "recursivo");
        verify(rag).answer("Segunda pergunta", "recursivo");
        verifyNoMoreInteractions(rag);
    }

    @Test void endsAtEofAfterAnUnterminatedQuestionAndHandlesEmptyInput() throws Exception {
        when(rag.ready("recursivo")).thenReturn(true);
        when(rag.answer("Pergunta sem quebra de linha", "recursivo")).thenReturn(answer("Resposta.", 16));

        Output result = execute("1\nPergunta sem quebra de linha");
        Output empty = execute("");

        assertTrue(result.console().contains("Resposta."));
        assertEquals("", result.errors());
        assertEquals("", empty.errors());
        verify(rag).ready("recursivo");
        verify(rag).answer("Pergunta sem quebra de linha", "recursivo");
        verifyNoMoreInteractions(rag);
    }

    @Test void oneShotQuestionJoinsArgumentsAndAlsoPrintsSources() throws Exception {
        when(rag.answer("Qual é o prazo?")).thenReturn(answer("Quatro dias úteis.", 4));

        Output result = execute("", "perguntar", "Qual", "é", "o", "prazo?");

        assertTrue(result.console().contains("Quatro dias úteis."));
        assertTrue(result.console().contains("[Fonte: Manual de Estágio | Página: 4]"));
        assertTrue(result.console().contains("Fonte recuperada: Manual de Estágio pág. 4"));
        assertEquals("", result.errors());
        verify(rag).answer("Qual é o prazo?");
        verifyNoMoreInteractions(rag);
    }

    @Test void oneShotBackendFailurePropagatesForTheApplicationExitStatus() throws Exception {
        when(rag.answer("Pergunta")).thenThrow(new IOException("Falha HTTP"));

        IOException failure = assertThrows(IOException.class, () -> execute("", "perguntar", "Pergunta"));

        assertEquals("Falha HTTP", failure.getMessage());
    }

    @Test void retainsDeveloperStatusModeAndQuestionCommands() throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.chunking_ativo = "recursivo";
        when(settings.load()).thenReturn(config);
        when(rag.ready()).thenReturn(true);
        doAnswer(invocation -> {
            config.chunking_ativo = invocation.getArgument(0);
            return null;
        }).when(settings).changeMode("semantico");
        when(rag.answer("Qual é o procedimento?")).thenReturn(answer("Procedimento aprovado.", 34));

        Output result = execute("status\nmodo semantico\nperguntar\nQual é o procedimento?\nsair\n", "dev");

        assertTrue(result.console().contains("AMBIENTE DESENVOLVEDOR"));
        assertTrue(result.console().contains("Método salvo: recursivo; base pronta: true"));
        assertTrue(result.console().contains("Método salvo: semantico"));
        assertTrue(result.console().contains("Procedimento aprovado."));
        assertTrue(result.console().contains("Fonte recuperada: Manual de Estágio pág. 34"));
        assertEquals("", result.errors());
        verify(settings).changeMode("semantico");
        verify(rag).answer("Qual é o procedimento?");
        verifyNoInteractions(preparation);
    }

    @Test void developerPromptEndsAtEofWithoutSendingAnEmptyQuestion() throws Exception {
        Output result = execute("perguntar\n", "dev");

        assertTrue(result.console().contains("Pergunta livre:"));
        assertEquals("", result.errors());
        verifyNoInteractions(rag, settings, preparation);
    }

    @Test void isolatedPreparationAndModeStillDelegateToExistingServices() throws Exception {
        execute("", "preparar");
        Output result = execute("", "modo", "recursivo");

        verify(preparation).prepare();
        verify(settings).changeMode("recursivo");
        verifyNoInteractions(rag);
        assertTrue(result.console().contains("Configuração salva: recursivo"));
    }

    @Test void rejectsInvalidArgumentsBeforeAnyServiceCall() {
        for (String[] args : List.of(
                new String[]{"servidor"}, new String[]{"dev", "extra"},
                new String[]{"preparar", "extra"}, new String[]{"modo"},
                new String[]{"modo", "outro"}, new String[]{"modo", "recursivo", "extra"},
                new String[]{"perguntar"}, new String[]{"perguntar", "   "})) {
            assertThrows(IllegalArgumentException.class, () -> execute("", args));
        }
        verifyNoInteractions(rag, settings, preparation);
    }
}
