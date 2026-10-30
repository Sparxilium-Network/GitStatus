package com.sparxilium.gitstatus.tui;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/** Small dialog helpers shared by the admin screens. */
final class Ui {

    private Ui() {
    }

    static BasicWindow window(String title) {
        BasicWindow w = new BasicWindow(title);
        w.setHints(List.of(Window.Hint.CENTERED));
        return w;
    }

    /**
     * Installs a key handler on a window. The handler sees keys before the focused component;
     * returning true consumes the key. Esc always closes the window unless consumed.
     */
    static void onKeys(Window w, BiPredicate<Window, KeyStroke> handler) {
        w.addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onInput(Window basePane, KeyStroke key, AtomicBoolean deliverEvent) {
                if (handler.test(basePane, key)) {
                    deliverEvent.set(false);
                } else if (key.getKeyType() == KeyType.Escape) {
                    deliverEvent.set(false);
                    basePane.close();
                }
            }
        });
    }

    static boolean isChar(KeyStroke key, char c) {
        return key.getKeyType() == KeyType.Character && Character.toLowerCase(key.getCharacter()) == c;
    }

    static void info(WindowBasedTextGUI gui, String title, String text) {
        MessageDialog.showMessageDialog(gui, title, text, MessageDialogButton.OK);
    }

    static void error(WindowBasedTextGUI gui, String text) {
        MessageDialog.showMessageDialog(gui, "Error", text, MessageDialogButton.OK);
    }

    static boolean confirm(WindowBasedTextGUI gui, String title, String text) {
        return MessageDialog.showMessageDialog(gui, title, text, MessageDialogButton.Yes, MessageDialogButton.No)
                == MessageDialogButton.Yes;
    }

    static String input(WindowBasedTextGUI gui, String title, String description, String initial) {
        return TextInputDialog.showDialog(gui, title, description, initial);
    }

    @SafeVarargs
    static <T> T choose(WindowBasedTextGUI gui, String title, String description, T... items) {
        if (items.length == 0) {
            return null;
        }
        return ListSelectDialog.showDialog(gui, title, description, items);
    }

    /** Runs a blocking call while a "please wait" box is on screen. */
    static <T> T busy(WindowBasedTextGUI gui, String message, Supplier<T> work) {
        BasicWindow w = window("Please wait");
        w.setComponent(new Label(message));
        gui.addWindow(w);
        try {
            gui.updateScreen();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            return work.get();
        } finally {
            w.close();
        }
    }

    static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        int len = com.googlecode.lanterna.TerminalTextUtils.getColumnWidth(sb.toString());
        while (len < width) {
            sb.append(' ');
            len++;
        }
        return sb.toString();
    }

    static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
