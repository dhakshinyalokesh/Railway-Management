FROM maven:3.9-eclipse-temurin-21

WORKDIR /app
COPY . .

# Compile the Java server and download the SQLite JDBC dependency at image build time.
RUN mvn -B -DskipTests compile

EXPOSE 8080
CMD ["mvn", "-q", "exec:java"]
