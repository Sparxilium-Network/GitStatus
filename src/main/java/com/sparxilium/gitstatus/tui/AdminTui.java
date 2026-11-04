package com.sparxilium.gitstatus.tui;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;
import com.sparxilium.gitstatus.GitStatusApplication;
import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.notify.DiscordGateway;
import com.sparxilium.gitstatus.provider.GitProvider;

/** Server-side admin TUI (the only place where configuration can be changed). */
@Component
@Profile(GitStatusApplication.PROFILE_ADMIN)
public class AdminTui implements CommandLineRunner {

    static final String RESTART_TEXT = """
            Restart the notification service?

            The service shuts down and Docker starts it again (restart: unless-stopped).
            Webhooks that arrive during the restart (10-20 seconds) are lost unless you redeliver
            them from the GitHub App's Recent Deliveries. This admin session closes as well.

            Settings changed here apply within 5 seconds without a restart. A restart does not
            pick up a new image or docker-compose.yml; use setup.sh for that.""";

    private final ConfigStore store;
    private final DiscordGateway discord;
    private final List<GitProvider> providers;
    private final AppSettings settings;

    public AdminTui(ConfigStore store, DiscordGateway discord, List<GitProvider> providers, AppSettings settings) {
        this.store = store;
        this.discord = discord;
        this.providers = providers;
        this.settings = settings;
    }

    @Override
    public void run(String... args) throws Exception {
        Terminal terminal;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            DefaultTerminalFactory factory = new DefaultTerminalFactory(System.out, System.in, StandardCharsets.UTF_8);
            factory.setPreferTerminalEmulator(true); // local development only
            terminal = factory.createTerminal();
        } else {
            terminal = new UnixTerminal(System.in, System.out, StandardCharsets.UTF_8);
        }
        try (terminal) {
            terminal.enterPrivateMode();
            try {
                mainMenu(new Tui(terminal));
            } finally {
                terminal.exitPrivateMode();
            }
        }
    }

    void mainMenu(Tui tui) {
        List<String> items = List.of("Discord Destinations", "Git Provider Sources", "Repositories / Notification Rules",
                "System Status", "Restart Service", "Exit");
        tui.show(new Tui.View() {
            String title() {
                return "Git Notification Manager";
            }

            List<String> rows() {
                return items;
            }

            String keys() {
                return "Up/Down Move    Enter Select    Esc Exit";
            }

            boolean onKey(KeyStroke key) {
                if (key.getKeyType() != KeyType.Enter) {
                    return super.onKey(key);
                }
                switch (cursor) {
                    case 0 -> new DestinationsScreen(tui, store, discord).show();
                    case 1 -> new SourcesScreen(tui, store, providers).show();
                    case 2 -> new RepositoriesScreen(tui, store, providers).show();
                    case 3 -> new StatusScreen(tui, store, discord, providers, settings).show();
                    case 4 -> {
                        if (!tui.confirm("Restart Service", RESTART_TEXT)) {
                            return true;
                        }
                        try {
                            store.requestRestart();
                            message = "Restart requested. The service stops within a few seconds and this session"
                                    + " closes with it; open the admin TUI again after about 20 seconds.";
                        } catch (RuntimeException e) {
                            message = "Could not request a restart: " + Tui.message(e);
                        }
                    }
                    default -> {
                        return false;
                    }
                }
                return true;
            }
        });
    }
}
