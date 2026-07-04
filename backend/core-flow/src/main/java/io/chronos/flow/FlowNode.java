package io.chronos.flow;

/**
 * A live node in a running flow. Processing nodes implement {@link #onMessage}; source nodes
 * (e.g. inject) override {@link #start} to begin emitting. Emit messages downstream via
 * {@link Emit#send(int, FlowMsg)} where {@code port} is the output index (switch uses many).
 */
public interface FlowNode {

    @FunctionalInterface
    interface Emit {
        void send(int port, FlowMsg msg);

        /**
         * Signal an error while handling {@code msg}. The runtime logs it and routes it to any
         * {@code catch} node watching this node (Node-RED-style error handling). Default no-op so a
         * plain send-only lambda is still a valid Emit.
         */
        default void error(FlowMsg msg, String message) {}

        /**
         * Forward {@code msg} over the virtual links configured on this node (Node-RED link out →
         * link in). The runtime routes it to the linked {@code link in} nodes. Default no-op.
         */
        default void link(FlowMsg msg) {}

        /**
         * Report a short status line for this node (Node-RED's {@code node.status()}), e.g.
         * "connected" / "disconnected". Shown under the node in the editor. Default no-op.
         */
        default void status(String text) {}

        /**
         * Link-call: push this node as a return address onto the message's link stack and route it to
         * the configured link-in nodes (Node-RED link call → link in → link out[return]). Default no-op.
         */
        default void linkCall(FlowMsg msg) {}

        /**
         * Link-return: pop the last link-call return address off the message's stack and emit the
         * message from that link-call node's output (Node-RED link out in "return" mode). Default no-op.
         */
        default void linkReturn(FlowMsg msg) {}
    }

    /** Handle an incoming message (no-op for pure sources). */
    default void onMessage(FlowMsg msg, Emit emit) {}

    /** Begin emitting (sources only); {@code emit} routes this node's outputs. */
    default void start(Emit emit) {}

    /** Manually emit one message (Node-RED's inject "now" button); no-op for most nodes. */
    default void trigger() {}

    /** Release timers/resources on stop or re-deploy. */
    default void stop() {}
}
