package br.com.js.mailsender.infrastructure.mail;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Janela deslizante de 60s por conta. Fonte de tempo injetada para os testes nao dormirem. */
@Component
public class InMemorySendRateLimiter implements SendRateLimiter {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();

    private final Map<String, Deque<Long>> envios = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InMemorySendRateLimiter() {
        this(System::currentTimeMillis);
    }

    InMemorySendRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String accountName, int maxPerMinute) {
        var janela = envios.computeIfAbsent(accountName, k -> new ArrayDeque<>());

        synchronized (janela) {
            long agora = clock.getAsLong();
            while (!janela.isEmpty() && agora - janela.peekFirst() >= WINDOW_MILLIS) {
                janela.pollFirst();
            }

            if (janela.size() >= maxPerMinute) {
                return false;
            }

            janela.addLast(agora);
            return true;
        }
    }
}
