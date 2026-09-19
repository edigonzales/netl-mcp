FROM sogis/gretl:3.2.861@sha256:5e1e2b89995d11d8317bf114c7ce9d74351c1167df59eee664376b825ff7ddea
USER root
COPY src/main/resources/runner /opt/netl/schema
COPY src/main/resources/runner/netl-run /usr/local/bin/netl-run
COPY runtime/SCHEMA-JOBS-LICENSE /opt/netl/SCHEMA-JOBS-LICENSE
RUN chmod 755 /usr/local/bin/netl-run && mkdir -p /home/gradle/.gradle && chown -R 1001:0 /home/gradle/.gradle
ENV GRADLE_USER_HOME=/home/gradle/.gradle
USER 1001
ENTRYPOINT ["sleep", "infinity"]
