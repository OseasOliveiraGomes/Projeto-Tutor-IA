package br.com.sylph;

import br.com.sylph.service.DevTerminal;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class SylphApplication {
    public static void main(String[] args) {
        try {
            DevTerminal.validateArguments(args);
            SpringApplication app = terminalApplication();
            try (ConfigurableApplicationContext ctx = app.run(args)) {
                ctx.getBean(DevTerminal.class).execute(args);
            }
        } catch (Exception e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(1);
        }
    }

    static SpringApplication terminalApplication() {
        SpringApplication app = new SpringApplication(SylphApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app;
    }
}
