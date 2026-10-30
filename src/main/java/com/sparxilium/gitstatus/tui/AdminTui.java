package com.sparxilium.gitstatus.tui;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.sparxilium.gitstatus.GitStatusApplication;
import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.notify.DiscordGateway;
import com.sparxilium.gitstatus.provider.GitProvider;

/** Server-side admin TUI (the only place where configuration can be changed). */
@Component
@Profile(GitStatusApplication.PROFILE_ADMIN)
public class AdminTui implements CommandLineRunner {

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
        DefaultTerminalFactory factory = new DefaultTerminalFactory(System.out, System.in, StandardCharsets.UTF_8);
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            factory.setPreferTerminalEmulator(true); // local development only
        } else {
            factory.setForceTextTerminal(true);
        }
        try (Screen screen = factory.createScreen()) {
            screen.startScreen();
            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLUE));
            mainMenu(gui);
            screen.stopScreen();
        }
    }

    private void mainMenu(MultiWindowTextGUI gui) {
        BasicWindow w = Ui.window("Git Notification Manager");
        ActionListBox menu = new ActionListBox(new TerminalSize(40, 5));
        menu.addItem("Discord Destinations", () -> new DestinationsScreen(gui, store, discord).show());
        menu.addItem("Git Provider Sources", () -> new SourcesScreen(gui, store, providers).show());
        menu.addItem("Repositories / Notification Rules", () -> new RepositoriesScreen(gui, store, providers).show());
        menu.addItem("System Status", () -> new StatusScreen(gui, store, discord, providers, settings).show());
        menu.addItem("Exit", w::close);

        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(menu);
        panel.addComponent(new EmptySpace());
        panel.addComponent(new Label("Up/Down: Select    Enter: Confirm    Esc: Back"));
        w.setComponent(panel);
        Ui.onKeys(w, (win, key) -> false);
        gui.addWindowAndWait(w);
    }
}
