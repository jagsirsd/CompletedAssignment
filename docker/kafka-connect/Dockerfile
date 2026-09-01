FROM apache/kafka:3.7.0
USER root
ARG DEBEZIUM_VERSION=2.6.2.Final
RUN wget -q -O /tmp/dbz.tar.gz \
      "https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz" && \
    mkdir -p /opt/kafka/plugins/debezium-postgres && \
    tar xz --strip-components=1 -f /tmp/dbz.tar.gz -C /opt/kafka/plugins/debezium-postgres && \
    rm /tmp/dbz.tar.gz
COPY connect-distributed.properties /opt/kafka/config/connect-distributed.properties
USER appuser
CMD ["/opt/kafka/bin/connect-distributed.sh", "/opt/kafka/config/connect-distributed.properties"]
