package com.sparxilium.gitstatus.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredInstallation;
import com.sparxilium.gitstatus.db.ConfigStore.DiscoveredRepository;
import com.sparxilium.gitstatus.db.SchemaInitializer;
import com.sparxilium.gitstatus.model.EventType;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.Records.NotificationRule;
import com.sparxilium.gitstatus.model.Records.ProviderInstallation;
import com.sparxilium.gitstatus.model.Records.RepositoryRecord;
import com.sparxilium.gitstatus.provider.GitProvider;

class RepoEventsScreenTest {

    // row positions: categories are rows of their own
    private static final int PULL_REQUEST = 2;
    private static final int PR_OPENED = 3;
    private static final int SECURITY = 13;

    @TempDir
    Path tmp;

    private final DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(120, 40));
    private final Tui tui = new Tui(terminal);
    private ConfigStore store;
    private ProviderInstallation inst;
    private RepositoryRecord repo;

    /** Every supported event is available; the rest are not supported yet. */
    private final GitProvider provider = new GitProvider() {
        public String id() { return "github"; }
        public String displayName() { return "GitHub"; }
        public List<Credential> credentials() { return List.of(); }
        public String checkAuth(Credential c) { return ""; }
        public List<DiscoveredInstallation> listInstallations(Credential c) { return List.of(); }
        public List<DiscoveredRepository> listRepositories(ProviderInstallation i) { return List.of(); }
        public Map<EventType, Availability> capabilities(ProviderInstallation i) {
            Map<EventType, Availability> out = new EnumMap<>(EventType.class);
            for (EventType t : EventType.values()) {
                out.put(t, t.supported() ? Availability.AVAILABLE : Availability.UNSUPPORTED);
            }
            return out;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        String db = tmp.resolve("bot.db").toString();
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + db + "?foreign_keys=true");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchemaInitializer(jdbc, new AppSettings(db, "https://api.github.com", "", "", "", "", "", 5000, 1000, 33444))
                .afterPropertiesSet();
        store = new ConfigStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        store.syncInstallations("github", "1001", List.of(new DiscoveredInstallation("7", "acme", "Organization", "{}", "[]")));
        inst = store.installations("github").get(0);
        store.syncRepositories(inst.id(), List.of(new DiscoveredRepository("42", "acme/api", "PRIVATE", false)));
        repo = store.repositories(inst.id()).get(0);
        store.addDestination("build", "1", "111111111111111111", Language.EN);
    }

    private void keys(Object... keys) {
        for (Object k : keys) {
            if (k instanceof KeyType type) {
                terminal.addInput(new KeyStroke(type));
            } else if (k instanceof Integer n) {
                for (int i = 0; i < n; i++) {
                    terminal.addInput(new KeyStroke(KeyType.ArrowDown));
                }
            } else {
                for (char c : k.toString().toCharArray()) {
                    terminal.addInput(new KeyStroke(c, false, false));
                }
            }
        }
    }

    private String screen() {
        List<String> lines = new ArrayList<>();
        for (int row = 0; row < terminal.getTerminalSize().getRows(); row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < terminal.getTerminalSize().getColumns(); col++) {
                sb.append(terminal.getBufferCharacter(new TerminalPosition(col, row)).getCharacterString());
            }
            lines.add(sb.toString().stripTrailing());
        }
        return String.join("\n", lines);
    }

    private Set<EventType> savedRules() {
        return store.rules(repo.id()).stream().filter(NotificationRule::enabled).map(NotificationRule::eventType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EventType.class)));
    }

    private void open() {
        new RepoEventsScreen(tui, store, provider, inst, repo).show();
    }

    @Test
    void categoryRowShowsPartialSelectionAndTurnsTheWholeCategoryOn() {
        // one event, then the category row: the rest of the category is turned on
        keys(PR_OPENED, " ", KeyType.ArrowUp, " ", "s", KeyType.Escape);
        open();
        assertEquals(EnumSet.of(EventType.PR_OPENED, EventType.PR_MERGED, EventType.PR_CLOSED), savedRules());
        assertTrue(screen().contains("[*] Pull Request"), screen());
        assertTrue(screen().contains("    [*] Opened"), screen());
    }

    @Test
    void partialCategoryIsShownAsMixed() {
        keys(PR_OPENED, " ", "s", KeyType.Escape);
        open();
        assertEquals(EnumSet.of(EventType.PR_OPENED), savedRules());
        assertTrue(screen().contains("[-] Pull Request"), screen());
    }

    @Test
    void fullCategoryIsTurnedOffWithOneKey() {
        store.saveRules(repo.id(), Map.of(store.destinations().get(0).id(),
                EnumSet.of(EventType.PUSH, EventType.PR_OPENED, EventType.PR_MERGED, EventType.PR_CLOSED)));
        keys(PULL_REQUEST, " ", "s", KeyType.Escape);
        open();
        assertEquals(EnumSet.of(EventType.PUSH), savedRules());
    }

    @Test
    void categoryWithNothingAvailableCannotBeTurnedOn() {
        keys(SECURITY, " ", KeyType.Escape);
        open();
        assertEquals(EnumSet.noneOf(EventType.class), savedRules());
        assertTrue(screen().contains("Security: unavailable / not supported yet"), screen());
    }
}
