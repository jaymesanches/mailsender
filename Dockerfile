# syntax=docker/dockerfile:1
#
# Consome o jar JA construido em target/, produzido pelo job `package` do CI.
# A imagem nao compila: isso elimina a recompilacao dentro do DinD, que era a mais
# caro do pipeline por nao aproveitar o cache do Maven.
#
# Para construir na mao:  ./mvnw package -DskipTests && docker build -t mailsender .
# Sem o jar em target/, o COPY abaixo falha — e o sintoma e explicito.

# ---------------- separacao em camadas ----------------
# JRE basta: o jarmode `tools` roda com java.base + java.logging. Usar a mesma tag
# do estagio final significa uma imagem baixada, nao duas.
FROM eclipse-temurin:25-jre AS layers

WORKDIR /layers
COPY target/*.jar app.jar

# O fat jar tem ~77MB e as dependencias nao mudam entre releases; separando as
# camadas, cada release publica so a ultima (as classes, ~70KB).
# No Boot 4 o modo e `tools` — o antigo `layertools` nao existe mais.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------- runtime ----------------
FROM eclipse-temurin:25-jre

WORKDIR /app

# Ordem deliberada: do que muda menos para o que muda mais.
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

# UID numerico direto: evita a camada de useradd e o chown -R, que duplicaria a
# arvore inteira. Os arquivos sao legiveis por todos, e o app nao escreve em /app.
USER 1001:1001

EXPOSE 8081

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
