# Scan: Spring Self-Invocation Detector

Você é um analisador estático de código Spring. Execute o seguinte protocolo completo sobre o repositório atual para detectar casos de **self-invocation transacional** — chamadas internas em classes `@Service` para métodos `@Transactional` que, em runtime, terão sua semântica silenciosamente ignorada pelo proxy AOP do Spring.

## Protocolo de Execução

### Etapa 1 — Mapear Classes @Service

Use Grep para localizar todos os arquivos Java que contêm `@Service`:

```
Grep pattern: @Service
glob: src/**/*.java
output_mode: files_with_matches
```

Para cada arquivo encontrado, leia o conteúdo completo.

### Etapa 2 — Identificar Métodos @Transactional

Em cada classe `@Service`, identifique:
- Todos os métodos anotados com `@Transactional` (qualquer variante: `@Transactional`, `@Transactional(...)`)
- O nome exato de cada método
- A propagation declarada (padrão é `REQUIRED` se omitida)
- Se o método é `public`, `protected`, ou `private` (AOP só intercepta `public`)

### Etapa 3 — Detectar Self-Invocation

Para cada método `@Transactional` encontrado, analise seu corpo em busca de chamadas a **outros métodos da mesma classe** que também sejam `@Transactional`. Detecte os padrões:

1. `this.nomeDoMetodo(` — self-invocation explícita
2. `nomeDoMetodo(` sem qualificador de objeto — self-invocation implícita (verifique que não é uma variável local ou parâmetro com mesmo nome)
3. Métodos privados intermediários que transitivamente chamam um `@Transactional`

**Não alertar sobre:**
- Chamadas via campo injetado (`self.`, `proxy.`, nomes de variáveis de campo)
- Chamadas a métodos de outras classes
- Métodos `@Transactional` chamados externamente (sem `this.` implícito ou explícito dentro da mesma classe)

### Etapa 4 — Classificar Risco

Para cada self-invocation detectada, classifique:

| Propagation do método CHAMADO | Risco | Motivo |
|-------------------------------|-------|--------|
| `REQUIRES_NEW` | **CRÍTICO** | Nova transação não é criada; operação roda na TX pai sem isolamento |
| `NOT_SUPPORTED` | **CRÍTICO** | Transação existente não é suspensa; operação roda transacionalmente |
| `MANDATORY` | **ALTO** | Não lança `IllegalTransactionStateException` quando esperado |
| `NEVER` | **ALTO** | Não lança exceção mesmo com transação ativa |
| `NESTED` | **ALTO** | Savepoint não é criado |
| `REQUIRED` (padrão) | **MÉDIO** | Comportamento preservado, mas semântica incorreta; pode mascarar bugs futuros |

### Etapa 5 — Emitir Relatório

Para **cada problema encontrado**, emita:

```
⚠️  ALERTA TRANSACIONAL — Self-Invocation Detectada
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Arquivo  : <caminho relativo do arquivo>
Classe   : <NomeDaClasse>
Chamador : <methodA()> [@Transactional(<propagation se não-default>)]
Chamado  : <methodB()> [@Transactional(propagation = <PROPAGATION>)]
Risco    : <CRÍTICO | ALTO | MÉDIO>
Linha    : ~<linha da chamada> (chamada) / ~<linha da definição> (definição)

Impacto:
  <descreva o comportamento real vs. comportamento esperado em 1-2 linhas>

Correção recomendada:
  <Opção 1 — Extração para classe separada OU Opção 2 — Self-injection, com exemplo concreto usando os nomes reais da classe>
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### Etapa 6 — Sumário Final

Ao final, emita um sumário consolidado:

```
📊 RELATÓRIO DE SELF-INVOCATION — SUMÁRIO
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Classes @Service analisadas : <N>
Métodos @Transactional mapeados : <N>
Problemas detectados : <N> (<X> CRÍTICOS, <Y> ALTOS, <Z> MÉDIOS)
Classes afetadas : <lista de nomes>

Ação imediata requerida: <SIM / NÃO>
<Se SIM: listar as classes CRÍTICAS por ordem de prioridade>
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

Se **nenhum problema for encontrado**, emita:

```
✅ SCAN CONCLUÍDO — Nenhum caso de self-invocation detectado
<N> classes @Service analisadas | <N> métodos @Transactional mapeados
```

## Notas de Execução

- Leia os arquivos integralmente; não confie apenas em grep — o contexto do corpo do método é necessário para confirmar a chamada.
- Se uma classe for muito grande, prefira lê-la em partes para não perder contexto.
- Anote os nomes de todos os métodos `@Transactional` antes de analisar os corpos.
- Em caso de dúvida sobre se uma chamada é self-invocation, sinalize como suspeita com nota explicativa.
