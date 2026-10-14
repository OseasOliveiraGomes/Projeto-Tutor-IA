package br.com.sylph.service;

import br.com.sylph.config.SettingsService;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DevTerminal {
    private final SettingsService settings;
    private final BasePreparation prep;
    private final RagService rag;
    public DevTerminal(SettingsService settings, BasePreparation prep, RagService rag) {
        this.settings=settings; this.prep=prep; this.rag=rag;
    }

    public static void validateArguments(String[] args) {
        if (args.length == 0) return;
        switch (args[0]) {
            case "dev", "preparar" -> {
                if (args.length != 1) throw new IllegalArgumentException("Uso: " + args[0]);
            }
            case "modo" -> {
                if (args.length != 2 || !(args[1].equals("recursivo") || args[1].equals("semantico")))
                    throw new IllegalArgumentException("Uso: modo recursivo|semantico");
            }
            case "perguntar" -> {
                if (args.length < 2 || questionArgument(args).isBlank())
                    throw new IllegalArgumentException("Uso: perguntar \"Sua dúvida\"");
            }
            default -> throw new IllegalArgumentException(
                "Comando inválido: " + args[0] + ". Use dev, preparar, modo ou perguntar; "
                    + "sem argumentos, inicie a consulta interativa.");
        }
    }

    public void execute(String[] args) throws IOException {
        execute(args, System.in, System.out, System.err);
    }

    void execute(String[] args, InputStream input, PrintStream out, PrintStream error) throws IOException {
        validateArguments(args);
        if (args.length == 0) { interactive(false, input, out, error); return; }
        if (args[0].equals("preparar")) { prep.prepare(); return; }
        if (args[0].equals("modo")) {
            settings.changeMode(args[1]);
            out.println("Configuração salva: " + args[1]); return;
        }
        if (args[0].equals("perguntar")) {
            printAnswer(rag.answer(questionArgument(args)), out);
            return;
        }
        interactive(true, input, out, error);
    }

    private static String questionArgument(String[] args) {
        return String.join(" ", Arrays.copyOfRange(args, 1, args.length));
    }

    private static void printAnswer(RagService.Answer answer, PrintStream out) {
        out.println("\n" + answer.resposta() + "\n");
        for (var source : answer.fontes())
            out.println("Fonte recuperada: " + source.documento() + " pág. " + source.pagina());
    }

    private void interactive(boolean developer, InputStream input, PrintStream out, PrintStream error) {
        Scanner in = new Scanner(input, StandardCharsets.UTF_8);
        out.println(developer ? "\nSYLPH — AMBIENTE DESENVOLVEDOR\n"
            + "Comandos: status | modo recursivo | modo semantico | preparar | perguntar | testar | sair"
            : "\nSYLPH — CONSULTA AO MANUAL DE ESTÁGIO\nDigite sua pergunta ou sair para encerrar.");
        while (true) {
            out.print(developer ? "dev> " : "sylph> ");
            out.flush();
            if (!in.hasNextLine()) return;
            String cmd = in.nextLine().trim();
            try {
                if (cmd.equals("sair")) break;
                if (cmd.isEmpty()) continue;
                if (!developer) {
                    printAnswer(rag.answer(cmd), out);
                }
                else if (cmd.equals("status")) out.println("Método salvo: " + settings.load().chunking_ativo
                    + "; base pronta: " + rag.ready());
                else if (cmd.equals("modo recursivo") || cmd.equals("modo semantico")) {
                    settings.changeMode(cmd.substring(5)); out.println("Método salvo: " + settings.load().chunking_ativo);
                }
                else if (cmd.equals("preparar")) prep.prepare();
                else if (cmd.equals("perguntar") || cmd.equals("testar")) {
                    out.print("Pergunta livre: ");
                    out.flush();
                    if (!in.hasNextLine()) break;
                    String question = in.nextLine().trim();
                    var answer = rag.answer(question);
                    printAnswer(answer, out);
                    if (cmd.equals("testar")) {
                        recordTest(question, answer);
                        out.println("Teste registrado. Verifique manualmente acerto e citações.");
                    }
                } else out.println("Comando inválido.");
            } catch (Exception ex) { error.println("Falha: " + ex.getMessage()); }
        }
    }

    private void recordTest(String question, RagService.Answer answer) throws IOException {
        Path file = Path.of("testes", "resultados_testes.csv");
        Files.createDirectories(file.toAbsolutePath().getParent());
        boolean exists = Files.isRegularFile(file);
        String pages = answer.fontes().stream().map(s->Integer.toString(s.pagina())).distinct().reduce((a,b)->a+";"+b).orElse("");
        String row = String.join(",", csv(OffsetDateTime.now().toString()), csv(settings.load().chunking_ativo),
            csv(question),csv(answer.resposta()),csv(pages),csv("PENDENTE"),csv("PENDENTE")) + "\n";
        if (!exists) Files.writeString(file,"data_hora,metodo,pergunta,resposta,paginas_citadas,acerto_validado,citacao_validada\n",StandardCharsets.UTF_8);
        Files.writeString(file,row,StandardCharsets.UTF_8,StandardOpenOption.APPEND);
    }
    private String csv(String s) { return "\"" + s.replace("\"","\"\"") + "\""; }
}
