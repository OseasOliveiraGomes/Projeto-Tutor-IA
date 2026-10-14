package br.com.sylph.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;

@Service
public class SettingsService {
    private final Path path = Path.of("config.json").toAbsolutePath();
    private final ObjectMapper mapper;

    public SettingsService(ObjectMapper mapper) {
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public synchronized ProjectConfig load() throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Arquivo config.json não encontrado. Abra a pasta raiz do projeto.");
        ProjectConfig config = mapper.readValue(path.toFile(), ProjectConfig.class);
        config.validar();
        return config;
    }

    public synchronized void changeMode(String mode) throws IOException {
        if (!"recursivo".equals(mode) && !"semantico".equals(mode))
            throw new IllegalArgumentException("Use 'recursivo' ou 'semantico'.");
        ProjectConfig cfg = load();
        cfg.chunking_ativo = mode;
        Path temp = path.resolveSibling("config.json.tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), cfg);
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
