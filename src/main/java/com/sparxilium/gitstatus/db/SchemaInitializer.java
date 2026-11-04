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
import com.sparxilium.gitstatus.config.StartupCheckException;

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
        checkWritable(db);

        int version = jdbc.queryForObject("PRAGMA user_version", Integer.class);
        if (version > SCHEMA_VERSION) {
            throw new StartupCheckException("The database " + db + " is schema version " + version
                    + ", but this release only understands versions up to " + SCHEMA_VERSION
                    + ". It was upgraded by a newer gitstatus.",
                    "Run the newer release again (git pull && docker compose up -d --build), or restore a copy of "
                            + "the database made before that upgrade.");
        }

        String script;
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String sql : stripComments(script).split(";")) {
            if (!sql.isBlank()) {
                jdbc.execute(sql);
            }
        }
        migrate(version);
        restrictPermissions(db);
    }

    /**
     * SQLite only reports "unable to open database file", so check the usual cause first: the
     * data directory (a Docker bind mount) is not writable by the service user.
     */
    static void checkWritable(Path db) {
        Path dir = db.getParent();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw notWritable(dir, db);
        }
        if (!Files.isWritable(dir)) {
            throw notWritable(dir, db);
        }
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path p = Path.of(db + suffix);
            if (Files.exists(p) && !Files.isWritable(p)) {
                throw notWritable(p, db);
            }
        }
    }

    private static StartupCheckException notWritable(Path path, Path db) {
        String owner = "";
        try {
            owner = " (owner uid " + Files.getAttribute(path, "unix:uid") + ")";
        } catch (IOException | RuntimeException e) {
            // not a POSIX file system or the path does not exist
        }
        return new StartupCheckException(
                path + owner + " is not writable by user '" + System.getProperty("user.name")
                        + "', so the database " + db + " cannot be opened.",
                "Give the service user write access to the data directory. Docker deployment: in the deploy "
                        + "directory on the host run 'sudo ./setup.sh' (or 'sudo chown -R 10001:10001 data && "
                        + "sudo chmod 700 data'), then 'docker compose up -d'.");
    }

    /**
     * Schema version, stored in SQLite's {@code PRAGMA user_version}. A database written by a newer
     * release refuses to start instead of being used with a schema this release does not know.
     * <p>
     * To change the schema: update schema.sql (new databases), raise this number and add an
     * {@code if (from < N)} step to {@link #migrate} (existing databases).
     */
    public static final int SCHEMA_VERSION = 1;

    /** Upgrades an existing database; CREATE TABLE IF NOT EXISTS does not add new columns to it. */
    private void migrate(int from) {
        if (from < 1) {
            // databases from before schema versioning: columns added after the first release
            addColumnIfMissing("discord_destination", "language", "TEXT NOT NULL DEFAULT 'en'");
            addColumnIfMissing("provider_installation", "app_id", "TEXT");
        }
        if (from < SCHEMA_VERSION) {
            jdbc.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            log.info("Database schema version {} -> {}", from, SCHEMA_VERSION);
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean exists = jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class, table).contains(column);
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("Database migrated: added {}.{}", table, column);
        }
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
