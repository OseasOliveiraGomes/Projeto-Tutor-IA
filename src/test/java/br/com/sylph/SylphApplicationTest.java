package br.com.sylph;

import br.com.sylph.service.DevTerminal;
import br.com.sylph.service.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SylphApplicationTest {
    @Test void startsTheActualApplicationContextWithoutAWebServerAndWithJsonServices() {
        var application = SylphApplication.terminalApplication();
        application.setDefaultProperties(Map.of("spring.main.banner-mode", "off", "logging.level.root", "ERROR"));

        try (var context = application.run()) {
            assertEquals(WebApplicationType.NONE, application.getWebApplicationType());
            assertFalse(context instanceof WebServerApplicationContext);
            assertNotNull(context.getBean(ObjectMapper.class));
            assertNotNull(context.getBean(DevTerminal.class));
            assertNotNull(context.getBean(RagService.class));
        }
    }
}
