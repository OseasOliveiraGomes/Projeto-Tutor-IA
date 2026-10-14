package br.com.sylph.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FinalAnswerPresentationTest {
    private static final RagService.Source SOURCE=new RagService.Source("Manual",7,"Texto de apoio.");
    private static final String CITE=" [Fonte: Manual | Página: 7]";
    @Test void consolidatesNumberedDuplicateWithoutDroppingItsCitation() {
        String one="1. A empresa deve assinar o termo."+CITE;
        String two="A empresa deve assinar o termo."+CITE;
        String output=FinalAnswerPresentation.render(List.of(one,two),List.of(SOURCE));
        assertEquals(one,output);
    }
    @Test void richerLiteralUnitKeepsRestrictionsAndExceptions() {
        String shorter="O prazo é de 4 dias."+CITE;
        String richer="O prazo é de 4 dias. Somente em dias úteis, exceto feriados; não há dispensa."+CITE;
        assertEquals(richer,FinalAnswerPresentation.render(List.of(shorter,richer),List.of(SOURCE)));
    }
    @Test void differingNegationsValuesAndConditionsAreNeverMerged() {
        List<String> distinct=List.of("A empresa pode enviar."+CITE,"A empresa não pode enviar."+CITE,
            "O prazo é de 4 dias."+CITE,"O prazo é de 5 dias."+CITE,
            "A empresa pode enviar se houver autorização."+CITE);
        assertEquals(String.join("\n\n",distinct),FinalAnswerPresentation.render(distinct,List.of(SOURCE)));
    }
    @Test void formatSummariesCannotEraseRestrictionsOrDifferentFormats() {
        String rich="Os arquivos somente poderão ser enviados no formato PDF, PNG e JPG; devem estar assinados e legíveis."+CITE;
        for(String different:List.of("Os formatos de arquivo aceitos são PDF e PNG."+CITE,
                "Os formatos de arquivo aceitos são PDF, PNG e JPG, exceto documentos assinados."+CITE)) {
            assertEquals(different+"\n\n"+rich,FinalAnswerPresentation.render(List.of(different,rich),List.of(SOURCE)));
        }
    }
    @Test void paraphrasesFromDifferentSourcesKeepBothTextsAndCitations() {
        var other=new RagService.Source("Outro",9,"Outro apoio.");
        String shortText="Os formatos de arquivo aceitos são PDF e PNG."+CITE;
        String rich="Os arquivos somente poderão ser enviados no formato PDF e PNG."+" [Fonte: Outro | Página: 9]";
        assertEquals(shortText+"\n\n"+rich,FinalAnswerPresentation.render(List.of(shortText,rich),List.of(SOURCE,other)));
    }
    @Test void noticeIdentifiesOnlyTheItemsReportedMissing() {
        String notice=FinalAnswerPresentation.notice("Quem envia e quais formatos são aceitos?",
            List.of("Responsável pelo envio não identificado"),List.of(),false);
        assertTrue(notice.contains("Responsável pelo envio"));
        assertFalse(notice.contains("formatos"));
        assertTrue(notice.contains("confirmar"));
        assertFalse(notice.contains("não identificado"));
    }
    @Test void attributionNoticeNamesTheActionWithoutReassertingTheUnsupportedActor() {
        String notice=FinalAnswerPresentation.notice("Como envio?",List.of(),
            List.of("Unidade 1: apoio ausente (atribuicao sem vinculo agente-acao/modalidade: o aluno deve enviar a declaracao)"),false);
        assertTrue(notice.contains("quem deve enviar a declaracao"));
        assertFalse(notice.contains("aluno"));
    }
    @Test void ambiguousFailuresNameTheQuestionRatherThanInventingAMissingComponent() {
        String notice=FinalAnswerPresentation.notice("Qual procedimento deve ser seguido?",List.of(),
            List.of("JSON de auditoria invalido"),false);
        assertTrue(notice.contains("Qual procedimento deve ser seguido?"));
        assertFalse(notice.contains("responsável"));
    }
    @Test void formatPermissionIsNotMergedWithANegationOrObligation() {
        String summary="Os formatos de arquivo aceitos são PDF e PNG."+CITE;
        for(String other:List.of("Os arquivos não poderão ser enviados no formato PDF e PNG."+CITE,
                "Os arquivos devem ser enviados no formato PDF e PNG."+CITE))
            assertEquals(summary+"\n\n"+other,FinalAnswerPresentation.render(List.of(summary,other),List.of(SOURCE)));
    }
    @Test void questionsAndAssertionsAreNotDuplicates() {
        List<String> units=List.of("O prazo é de 4 dias?"+CITE,"O prazo é de 4 dias."+CITE);
        assertEquals(String.join("\n\n",units),FinalAnswerPresentation.render(units,List.of(SOURCE)));
    }
    @Test void missingPermissionDoesNotInventResponsibilityOrObligation() {
        String notice=FinalAnswerPresentation.notice("Quem pode enviar?",List.of(),
            List.of("Unidade 1: apoio ausente (atribuicao sem vinculo agente-acao/modalidade: o fiscal pode enviar o documento)"),false);
        assertTrue(notice.contains("quem pode enviar o documento"));
        assertFalse(notice.contains("responsável"));
        assertFalse(notice.contains("deve"));
        assertFalse(notice.contains("fiscal"));
    }
}
