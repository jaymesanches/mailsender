package br.com.js.mailsender.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void deveNormalizarParaMinusculas() {
        assertThat(Email.of("Jayme.Sanches@Example.COM").value())
                .isEqualTo("jayme.sanches@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = { "dev@dominio.com", "dev+tag@sub.dominio.com.br", "dev_1-2.3@dominio.io" })
    void deveAceitarEnderecoValido(String valido) {
        assertThat(Email.of(valido).value()).isEqualTo(valido);
    }

    @ParameterizedTest
    @ValueSource(strings = { "  dev@dominio.com  ", "   Dev@Dominio.COM " })
    void deveApararEspacosAntesDeValidar(String comEspacos) {
        assertThat(Email.of(comEspacos).value()).isEqualTo("dev@dominio.com");
    }

    @ParameterizedTest
    @ValueSource(strings = { "sem-arroba", "@dominio.com", "dev@", "dev@dominio", "dev@dominio.c", "dev @dominio.com" })
    void deveRejeitarEnderecoInvalido(String invalido) {
        assertThatThrownBy(() -> Email.of(invalido))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid email: " + invalido);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void deveRejeitarVazio(String vazio) {
        assertThatThrownBy(() -> Email.of(vazio))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email cannot be empty");
    }

    @Test
    void deveRejeitarNulo() {
        assertThatThrownBy(() -> Email.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email cannot be empty");
    }
}
