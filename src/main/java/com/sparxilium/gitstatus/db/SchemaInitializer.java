package com.sparxilium.gitstatus.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.sparxilium.gitstatus.config.AppSettings;

/** Creates the SQLite schema on startup (both modes) and restricts file permissions. */
@Component
public class SchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private final AppSettings settings;

    public SchemaInitializer(JdbcTemplate jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        Path db = Path.of(settings.database()).toAbsolutePath();
        Files.createDirectories(db.getParent());

        String script;
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String sql : stripComments(script).split(";")) {
            if (!sql.isBlank()) {
                jdbc.execute(sql);
            }
        }
        restrictPermissions(db);
    }

    private static String stripComments(String statement) {
        StringBuilder sb = new StringBuilder();
        for (String line : statement.split("\n")) {
            if (!line.trim().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** SEC-009: database (and its WAL/SHM files) readable only by the owning user. */
    private static void restrictPermissions(Path db) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path p = Path.of(db + suffix);
            if (Files.exists(p)) {
                try {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
                } catch (IOException | UnsupportedOperationException e) {
                    log.warn("Could not restrict permissions on {}", p);
                }
            }
        }
    }
}
