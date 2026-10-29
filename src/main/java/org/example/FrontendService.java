
package org.example;

import static spark.Spark.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FrontendService {

    private static final HttpClient client = HttpClient.newHttpClient();

    private static final String CATALOG_URL =
            System.getenv().getOrDefault("CATALOG_URL", "http://localhost:4567");

    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_URL", "http://localhost:4569");

    public static void main(String[] args) {

        port(4568);

        // Allow the browser to call the API routes.
        before((req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
        });

        options("/*", (req, res) -> "");

        // Serve the bookstore GUI.
        get("/", (req, res) -> {
            Path index = Path.of("src", "main", "resources", "public", "index.html");

            if (!Files.exists(index)) {
                res.status(404);
                return "index.html not found: " + index.toAbsolutePath();
            }

            res.type("text/html; charset=utf-8");
            return Files.readString(index);
        });

        // List all books.
        get("/catalog/books", (req, res) ->
                sendGet("/catalog/books", res));

        // Search books by topic.
        get("/search", (req, res) -> {
            String topic = req.queryParams("topic");

            if (topic == null || topic.isBlank()) {
                res.status(400);
                res.type("application/json");
                return "{\"error\":\"Please provide a topic\"}";
            }

            String encodedTopic = URLEncoder.encode(
                    topic, StandardCharsets.UTF_8);

            return sendGet("/catalog/search?topic=" + encodedTopic, res);
        });

        // Get information about one book.
        get("/info/:id", (req, res) -> {
            String id = req.params(":id");

            if (!id.matches("\\d+")) {
                res.status(400);
                res.type("application/json");
                return "{\"error\":\"Invalid book ID\"}";
            }

            return sendGet("/catalog/books/" + id, res);
        });

        // Update book price.
        put("/catalog/books/:id/price", (req, res) ->
                sendPut("/catalog/books/" + req.params(":id") + "/price",
                        req.body(), res));

        // Update book stock.
        put("/catalog/books/:id/stock", (req, res) ->
                sendPut("/catalog/books/" + req.params(":id") + "/stock",
                        req.body(), res));

        // Purchase a book through Order Service.
        post("/purchase/:id", (req, res) -> {
            String id = req.params(":id");

            if (!id.matches("\\d+")) {
                res.status(400);
                res.type("application/json");
                return "{\"error\":\"Invalid book ID\"}";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ORDER_URL + "/purchase/" + id))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> result = client.send(
                    request, HttpResponse.BodyHandlers.ofString());

            res.type("application/json");
            res.status(result.statusCode());

            return result.body();
        });

        System.out.println("Frontend Service is running on port 4568");
    }

    private static String sendGet(
            String path, spark.Response response) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CATALOG_URL + path))
                .GET()
                .build();

        HttpResponse<String> result = client.send(
                request, HttpResponse.BodyHandlers.ofString());

        response.type("application/json");
        response.status(result.statusCode());

        return result.body();
    }

    private static String sendPut(
            String path, String body, spark.Response response)
            throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CATALOG_URL + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> result = client.send(
                request, HttpResponse.BodyHandlers.ofString());

        response.type("application/json");
        response.status(result.statusCode());

        return result.body();
    }
}