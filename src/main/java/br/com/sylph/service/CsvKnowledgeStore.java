package br.com.sylph.service;

import br.com.sylph.model.KnowledgeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class CsvKnowledgeStore {
    private final ObjectMapper mapper;
    public CsvKnowledgeStore(ObjectMapper mapper) { this.mapper = mapper; }

    public Path file(String method) {
        if (!"recursivo".equals(method) && !"semantico".equals(method))
            throw new IllegalArgumentException("Método inválido");
        return Path.of("dados", "base_" + method + ".csv").toAbsolutePath();
    }
    private static String esc(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }

    public void write(String method, List<KnowledgeChunk> chunks) throws IOException {
        Path dst = file(method);
        Files.createDirectories(dst.getParent());
        Path temp = dst.resolveSibling(dst.getFileName().toString() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            out.write("id,documento,pagina,metodo,texto,embedding\n");
            for (KnowledgeChunk c : chunks) {
                out.write(esc(c.id()) + "," + esc(c.documento()) + "," + c.pagina() + "," + esc(c.metodo())
                    + "," + esc(c.texto()) + "," + esc(mapper.writeValueAsString(c.embedding())) + "\n");
            }
        }
        try { Files.move(temp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, dst, StandardCopyOption.REPLACE_EXISTING); }
    }

    public List<KnowledgeChunk> read(String method) throws IOException {
        Path path = file(method);
        if (!Files.isRegularFile(path)) throw new IOException("Base " + method + " ausente. Execute: java -jar target/sylph-unopar-java-1.0.0.jar preparar");
        List<KnowledgeChunk> chunks = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            boolean header = true;
            for (List<String> row : records(reader)) {
                if (header) { header = false; continue; }
                if (row.size() != 6) throw new IOException("CSV malformado (esperado 6 campos): " + path);
                if (!row.get(3).equals(method)) throw new IOException("Método diferente dentro da base: " + path);
                chunks.add(new KnowledgeChunk(row.get(0), row.get(1), Integer.parseInt(row.get(2)), row.get(3),
                    row.get(4), mapper.readValue(row.get(5), double[].class)));
            }
        }
        if (chunks.isEmpty()) throw new IOException("Base " + method + " vazia; execute novamente 'preparar'");
        return chunks;
    }

    static List<List<String>> records(Reader reader) throws IOException {
        // Leitor CSV com suporte a vírgulas, aspas e quebras de linha dentro do texto.
        List<List<String>> rows = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        java.io.PushbackReader in = new java.io.PushbackReader(reader, 1);
        boolean quoted = false;
        int ch;
        while ((ch = in.read()) != -1) {
            char c = (char) ch;
            if (c == '"') {
                if (quoted) {
                    int next = in.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; if (next != -1) in.unread(next); }
                } else if (field.isEmpty()) quoted = true;
                else field.append(c);
            } else if (!quoted && c == ',') {
                record.add(field.toString()); field.setLength(0);
            } else if (!quoted && c == '\n') {
                record.add(field.toString()); field.setLength(0);
                rows.add(record); record = new ArrayList<>();
            } else if (!quoted && c == '\r') {
                // Aceita CSV CRLF do Windows.
            } else field.append(c);
        }
        if (quoted) throw new IOException("Campo CSV com aspas não fechadas");
        if (!field.isEmpty() || !record.isEmpty()) { record.add(field.toString()); rows.add(record); }
        return rows;
    }
}
