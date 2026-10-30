package com.sparxilium.gitstatus.notify;

import com.sparxilium.gitstatus.model.Records.DiscordDestination;

/** Sends one message to one destination. */
public interface DiscordSender {

    void send(DiscordDestination destination, DiscordMessage message) throws SendException;

    class SendException extends Exception {
        private final boolean permanent;

        /** @param permanent true when retrying cannot help (unknown channel, missing permission) */
        public SendException(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }

        public boolean permanent() {
            return permanent;
        }
    }
}
