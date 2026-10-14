package br.com.sylph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AttributionFormattingRegressionTest {
    private final ObjectMapper json = new ObjectMapper();
    private AnswerEvidence.Audit check(String statement,String evidence) {
        var source = new RagService.Source("Guia",72,evidence);
        var verdict = json.valueToTree(Map.of("claims",List.of(Map.of("id",1,"supported",true,
            "quotes",List.of(Map.of("documento","Guia","pagina",72,"texto",evidence)))),"missing",List.of()));
        return AnswerEvidence.decode(verdict,AnswerEvidence.parse(statement+" [Fonte: Guia | Página: 72]",List.of(source)));
    }
    @Test void formattedResponsibilityRetainsItsPropositionBeforeDetailsColon() {
        String evidence = "A secretaria é responsável por enviar o documento. O sistema utilizado é o portal.";
        var result = check("**Responsabilidade:** A secretaria **é responsável** por enviar o documento pelo sistema: portal.",evidence);
        assertTrue(result.accepted(), () -> result.issues().toString());
        assertFalse(check("**Responsabilidade:** O fiscal **é responsável** por enviar o documento pelo sistema: portal.",evidence).accepted());
    }
    @Test void roleLabelsNeedEvidenceForTheNamedActor() {
        String evidence = "A secretaria é responsável pelo envio.";
        assertTrue(check("**Responsável pelo envio:** a secretaria.",evidence).accepted());
        assertFalse(check("**Responsável pelo envio:** o fiscal.",evidence).accepted());
    }
    @Test void nominalPassiveWithLightVerbMatchesTheActiveAction() {
        String evidence = "A secretaria deve enviar o documento.";
        var supported = check("**O envio deve ser realizado pela secretaria.**",evidence);
        assertTrue(supported.accepted(), () -> supported.issues().toString());
        assertFalse(check("**O envio deve ser realizado pelo fiscal.**",evidence).accepted());
    }
}
