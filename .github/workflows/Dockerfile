# sbt & Scala
FROM sbtscala/scala-sbt:eclipse-temurin-17.0.4_1.7.1_3.2.0
WORKDIR /app

# Install Node.js
RUN apt-get update && apt-get install -y curl && \
    curl -sL https://deb.nodesource.com/setup_14.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy your application
COPY . .

# Set environment variables for JVM
ENV JAVA_OPTS="-Dsbt.color=true -Xms20G -Xmx20G -Xss10M -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
ENV JVM_OPTS="-Dsbt.color=true -Xms20G -Xmx20G -Xss10M -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"

ARG COMMAND
RUN ${COMMAND}