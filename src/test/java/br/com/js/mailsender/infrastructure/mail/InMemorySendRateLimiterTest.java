package br.com.js.mailsender.infrastructure.mail;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySendRateLimiterTest {

    private final AtomicLong relogio = new AtomicLong(0);
    private final InMemorySendRateLimiter limiter = new InMemorySendRateLimiter(relogio::get);

    @Test
    void deveLiberarAteOLimiteDaJanela() {
        assertThat(limiter.tryAcquire("conta-a", 3)).isTrue();
        assertThat(limiter.tryAcquire("conta-a", 3)).isTrue();
        assertThat(limiter.tryAcquire("conta-a", 3)).isTrue();

        assertThat(limiter.tryAcquire("conta-a", 3)).isFalse();
    }

    @Test
    void deveLiberarDeNovoQuandoAJanelaDesliza() {
        limiter.tryAcquire("conta-a", 1);
        assertThat(limiter.tryAcquire("conta-a", 1)).isFalse();

        relogio.set(59_999);
        assertThat(limiter.tryAcquire("conta-a", 1)).isFalse();

        relogio.set(60_000);
        assertThat(limiter.tryAcquire("conta-a", 1)).isTrue();
    }

    @Test
    void deveLiberarParcialmenteConformeOsEnviosSaemDaJanela() {
        limiter.tryAcquire("conta-a", 2);
        relogio.set(30_000);
        limiter.tryAcquire("conta-a", 2);
        assertThat(limiter.tryAcquire("conta-a", 2)).isFalse();

        // o primeiro envio saiu da janela, o segundo ainda nao
        relogio.set(60_000);
        assertThat(limiter.tryAcquire("conta-a", 2)).isTrue();
        assertThat(limiter.tryAcquire("conta-a", 2)).isFalse();
    }

    @Test
    void contasTemJanelasIndependentes() {
        assertThat(limiter.tryAcquire("conta-a", 1)).isTrue();
        assertThat(limiter.tryAcquire("conta-a", 1)).isFalse();

        assertThat(limiter.tryAcquire("conta-b", 1)).isTrue();
    }
}
