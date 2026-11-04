package com.sparxilium.gitstatus.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

class TuiTest {

    private final DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 40));
    private final Tui tui = new Tui(terminal);

    private void keys(Object... keys) {
        for (Object k : keys) {
            if (k instanceof KeyType type) {
                terminal.addInput(new KeyStroke(type));
            } else {
                for (char c : k.toString().toCharArray()) {
                    terminal.addInput(new KeyStroke(c, false, false));
                }
            }
        }
    }

    private List<String> screen() {
        List<String> lines = new ArrayList<>();
        for (int row = 0; row < terminal.getTerminalSize().getRows(); row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = 0; col < terminal.getTerminalSize().getColumns(); col++) {
                sb.append(terminal.getBufferCharacter(new TerminalPosition(col, row)).getCharacterString());
            }
            lines.add(sb.toString().stripTrailing());
        }
        return lines;
    }

    /** A checklist view like the Sources screen; records what it showed before closing. */
    private final class Checklist extends Tui.View {
        final boolean[] checked = new boolean[3];
        List<String> lastScreen = List.of();

        String title() {
            return "Pick items";
        }

        List<String> rows() {
            return List.of(Tui.checkbox(checked[0]) + "One", Tui.checkbox(checked[1]) + "Two",
                    Tui.checkbox(checked[2]) + "Three");
        }

        String keys() {
            return "Space Toggle    Esc Back";
        }

        boolean onKey(KeyStroke key) {
            if (Tui.isSpace(key)) {
                checked[cursor] = !checked[cursor];
                return true;
            }
            lastScreen = screen();
            return super.onKey(key);
        }
    }

    @Test
    void drawsFromTheTopLeftWithCursorAndCheckboxes() {
        Checklist view = new Checklist();
        keys(KeyType.ArrowDown, " ", KeyType.Escape);
        tui.show(view);

        List<String> shown = view.lastScreen;
        assertEquals(Tui.RULE, shown.get(0));
        assertEquals("Pick items", shown.get(1));
        assertTrue(shown.contains("   [ ] One"));
        assertTrue(shown.contains(" > [*] Two"));
        assertTrue(shown.contains("   [ ] Three"));
        assertTrue(shown.contains("Space Toggle    Esc Back"));
        assertTrue(view.checked[1]);
    }

    @Test
    void longListsScrollWithTheCursor() {
        List<Integer> items = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            items.add(i);
        }
        keys(KeyType.End, KeyType.Enter);
        assertEquals(40, tui.choose("Numbers", "", items));
        List<String> shown = screen();
        assertTrue(shown.contains("   ... 25 more above"));
        assertTrue(shown.contains(" > 40"));
        assertFalse(shown.contains("   10"));
    }

    @Test
    void chooseReturnsNullOnEscape() {
        keys(KeyType.Escape);
        assertNull(tui.choose("Pick", "", List.of("a", "b")));
    }

    @Test
    void confirmDefaultsToNo() {
        keys(KeyType.Enter);
        assertFalse(tui.confirm("Delete", "Really?"));
        keys(KeyType.ArrowDown, KeyType.Enter);
        assertTrue(tui.confirm("Delete", "Really?"));
        keys("y");
        assertTrue(tui.confirm("Delete", "Really?"));
    }

    @Test
    void inputEditsAndCancels() {
        keys(KeyType.Backspace, "xy", KeyType.Enter);
        assertEquals("abxy", tui.input("Name", "Enter a name", "abc"));
        keys("z", KeyType.Escape);
        assertNull(tui.input("Name", "Enter a name", ""));
    }

    @Test
    void closedInputEndsTheTui() {
        keys(KeyType.EOF);
        try {
            tui.choose("Pick", "", List.of("a"));
        } catch (java.io.UncheckedIOException expected) {
            return;
        }
        throw new AssertionError("expected the TUI to stop on EOF");
    }
}
