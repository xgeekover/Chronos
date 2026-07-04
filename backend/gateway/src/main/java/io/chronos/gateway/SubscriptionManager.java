package io.chronos.gateway;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-client tag subscriptions (§9). A subscription with no explicit tags means "all
 * tags". Matching is by canonical tag key so it is independent of node id/name.
 */
public final class SubscriptionManager {

    /** One client's subscription: all tags, or a specific set. */
    public static final class Subscription {
        private volatile boolean all;
        private final Set<String> tags = ConcurrentHashMap.newKeySet();

        void add(java.util.List<String> requested) {
            if (requested.isEmpty()) {
                all = true;
            } else {
                tags.addAll(requested);
            }
        }

        void remove(java.util.List<String> requested) {
            if (requested.isEmpty()) {
                all = false;
                tags.clear();
            } else {
                tags.removeAll(requested);
            }
        }

        public boolean matches(String tagKey) {
            return all || tags.contains(tagKey);
        }
    }

    private final Map<ClientSink, Subscription> subscriptions = new ConcurrentHashMap<>();

    public void register(ClientSink sink) {
        subscriptions.computeIfAbsent(sink, s -> new Subscription());
    }

    public void subscribe(ClientSink sink, java.util.List<String> tags) {
        subscriptions.computeIfAbsent(sink, s -> new Subscription()).add(tags);
    }

    public void unsubscribe(ClientSink sink, java.util.List<String> tags) {
        Subscription sub = subscriptions.get(sink);
        if (sub != null) {
            sub.remove(tags);
        }
    }

    public void remove(ClientSink sink) {
        subscriptions.remove(sink);
    }

    public Map<ClientSink, Subscription> all() {
        return subscriptions;
    }
}
