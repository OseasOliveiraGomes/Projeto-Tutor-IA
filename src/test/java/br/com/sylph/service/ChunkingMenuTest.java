package br.com.sylph.service;

import br.com.sylph.config.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkingMenuTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final BasePreparation preparation = mock(BasePreparation.class);
    private final RagService rag = mock(RagService.class);
    private final DevTerminal terminal = new DevTerminal(settings, preparation, rag);

    private record Output(String console, String errors) { }

    private Output execute(String input) throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        terminal.execute(new String[0],
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(console, true, StandardCharsets.UTF_8),
            new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Output(console.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private static RagService.Answer answer(String text) {
        return new RagService.Answer(text, List.of(), false);
    }

    @ParameterizedTest
    @CsvSource({"1,recursivo,RECURSIVO", "2,semantico,SEMÂNTICO"})
    void initialChoiceStaysActiveAcrossQuestions(String choice, String method, String label) throws Exception {
        when(rag.ready(method)).thenReturn(true);
        when(rag.answer("Pergunta um", method)).thenReturn(answer("Resposta um"));
        when(rag.answer("Pergunta dois", method)).thenReturn(answer("Resposta dois"));

        Output output = execute("  " + choice + "  \nPergunta um\nPergunta dois\nsair\n");

        assertTrue(output.console().contains("Tutora Virtual de Estágio"));
        assertTrue(output.console().contains("[1] Chunking Recursivo\n[2] Chunking Semântico\n[0] Sair"));
        assertTrue(output.console().contains("Método selecionado: " + label));
        assertTrue(output.console().contains("Sylph está pronta para ajudar!"));
        assertTrue(output.console().contains("Você [" + label + "]: "));
        assertTrue(output.console().contains("Resposta um"));
        assertTrue(output.console().contains("Resposta dois"));
        assertEquals("", output.errors());
        var order = inOrder(rag);
        order.verify(rag).ready(method);
        order.verify(rag).answer("Pergunta um", method);
        order.verify(rag).answer("Pergunta dois", method);
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @Test
    void zeroAtInitialMenuEndsBeforeAnyQuestionOrServiceCall() throws Exception {
        Output output = execute("0\n1\nPergunta após sair\n");

        assertTrue(output.console().contains("Escolha uma opção:"));
        assertFalse(output.console().contains("Sylph está pronta"));
        assertFalse(output.console().contains("Você ["));
        assertEquals("", output.errors());
        verifyNoInteractions(rag, settings, preparation);
    }

    @ParameterizedTest
    @CsvSource({"1,recursivo,2,semantico,SEMÂNTICO", "2,semantico,1,recursivo,RECURSIVO"})
    void metodoReopensMenuAndSwitchesBothDirections(String firstChoice, String firstMethod,
            String nextChoice, String nextMethod, String nextLabel) throws Exception {
        when(rag.ready(anyString())).thenReturn(true);
        when(rag.answer("Antes", firstMethod)).thenReturn(answer("Resposta antes"));
        when(rag.answer("Depois", nextMethod)).thenReturn(answer("Resposta depois"));

        Output output = execute(firstChoice + "\nAntes\n  metodo  \n" + nextChoice + "\nDepois\nsair\n");

        assertEquals(2, output.console().split("Selecione o método de chunking:", -1).length - 1);
        assertEquals(1, output.console().split("Tutora Virtual de Estágio", -1).length - 1);
        assertTrue(output.console().contains("Você [" + nextLabel + "]: "));
        assertEquals("", output.errors());
        var order = inOrder(rag);
        order.verify(rag).ready(firstMethod);
        order.verify(rag).answer("Antes", firstMethod);
        order.verify(rag).ready(nextMethod);
        order.verify(rag).answer("Depois", nextMethod);
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2"})
    void zeroDuringSwitchEndsEntireConversation(String firstChoice) throws Exception {
        when(rag.ready(anyString())).thenReturn(true);

        Output output = execute(firstChoice + "\nmetodo\n0\nPergunta após sair\n1\n");

        assertEquals(2, output.console().split("Selecione o método de chunking:", -1).length - 1);
        assertEquals("", output.errors());
        verify(rag).ready(firstChoice.equals("1") ? "recursivo" : "semantico");
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @Test
    void sairStopsConversationWithoutConsumingFollowingQuestions() throws Exception {
        when(rag.ready("recursivo")).thenReturn(true);

        Output output = execute("1\n  sair  \nmetodo\n2\nPergunta após sair\n");

        assertEquals(1, output.console().split("Selecione o método de chunking:", -1).length - 1);
        assertEquals("", output.errors());
        verify(rag).ready("recursivo");
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @Test
    void invalidChoicesAreRetriedInitiallyAndDuringSwitch() throws Exception {
        when(rag.ready(anyString())).thenReturn(true);

        Output output = execute("\nabc\n3\n1\nmetodo\n-1\nrecursivo\n2\nsair\n");

        assertEquals(5, output.console().split("Opção inválida. Digite 1, 2 ou 0.", -1).length - 1);
        assertTrue(output.console().contains("Método selecionado: RECURSIVO"));
        assertTrue(output.console().contains("Método selecionado: SEMÂNTICO"));
        assertEquals("", output.errors());
        verify(rag).ready("recursivo");
        verify(rag).ready("semantico");
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @ParameterizedTest
    @CsvSource({"1,recursivo,2,semantico", "2,semantico,1,recursivo"})
    void missingBaseAllowsAnotherChoiceWithoutPreparingOrSaving(String missingChoice, String missingMethod,
            String validChoice, String validMethod) throws Exception {
        when(rag.ready(missingMethod)).thenReturn(false);
        when(rag.ready(validMethod)).thenReturn(true);
        when(rag.answer("Oi", validMethod)).thenReturn(answer("Olá!"));

        Output output = execute(missingChoice + "\n" + validChoice + "\nOi\nsair\n");

        assertTrue(output.errors().contains("Base CSV ausente: dados/base_" + missingMethod + ".csv"));
        assertTrue(output.console().contains("Olá!"));
        var order = inOrder(rag);
        order.verify(rag).ready(missingMethod);
        order.verify(rag).ready(validMethod);
        order.verify(rag).answer("Oi", validMethod);
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @Test
    void missingBaseDuringSwitchCanBeFollowedByZeroToExit() throws Exception {
        when(rag.ready("recursivo")).thenReturn(true);
        when(rag.ready("semantico")).thenReturn(false);

        Output output = execute("1\nmetodo\n2\n0\nPergunta após sair\n");

        assertTrue(output.errors().contains("dados/base_semantico.csv"));
        verify(rag).ready("recursivo");
        verify(rag).ready("semantico");
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1\n", "1\nmetodo\n", "1\nmetodo\nabc"})
    void eofEndsMenusAndConversationWithoutSendingQuestions(String input) throws Exception {
        when(rag.ready("recursivo")).thenReturn(true);

        Output output = execute(input);

        assertEquals("", output.errors());
        if (input.startsWith("1")) verify(rag).ready("recursivo");
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }

    @Test
    void separateSessionsRequireTheirOwnChoice() throws Exception {
        when(rag.ready(anyString())).thenReturn(true);
        when(rag.answer("Primeira sessão", "semantico")).thenReturn(answer("Semântico"));
        when(rag.answer("Segunda sessão", "recursivo")).thenReturn(answer("Recursivo"));

        execute("2\nPrimeira sessão\nsair\n");
        Output output = execute("1\nSegunda sessão\nsair\n");

        assertTrue(output.console().contains("Método selecionado: RECURSIVO"));
        var order = inOrder(rag);
        order.verify(rag).ready("semantico");
        order.verify(rag).answer("Primeira sessão", "semantico");
        order.verify(rag).ready("recursivo");
        order.verify(rag).answer("Segunda sessão", "recursivo");
        verifyNoMoreInteractions(rag);
        verifyNoInteractions(settings, preparation);
    }
}
