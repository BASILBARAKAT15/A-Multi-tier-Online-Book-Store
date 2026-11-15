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

    // Service addresses.
    private static final String CATALOG_URL =
            System.getenv().getOrDefault("CATALOG_URL", "http://localhost:4567");

    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_URL", "http://localhost:4569");

    private static final Path INDEX_FILE =
            Path.of("src", "main", "resources", "public", "index.html");

    public static void main(String[] args) {

        port(4568);

        // Allow browser requests.
        before((req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
        });

        options("/*", (req, res) -> "");

        // Open the bookstore website.
        get("/", (req, res) -> {
            if (!Files.exists(INDEX_FILE)) {
                res.status(404);
                return "index.html not found";
            }

            res.type("text/html");
            return Files.readString(INDEX_FILE);
        });

        // Get all books.
        get("/catalog/books", (req, res) ->
                sendGet(CATALOG_URL + "/catalog/books", res));

        // Search books by topic.
        get("/search", (req, res) -> {
            String topic = req.queryParams("topic");

            if (topic == null || topic.isBlank()) {
                return error(res, 400, "Please provide a topic");
            }

            String encodedTopic =
                    URLEncoder.encode(topic, StandardCharsets.UTF_8);

            return sendGet(
                    CATALOG_URL + "/catalog/search?topic=" + encodedTopic,
                    res
            );
        });

        // Get information about one book.
        get("/info/:id", (req, res) -> {
            String id = req.params(":id");

            if (!validId(id)) {
                return error(res, 400, "Invalid book ID");
            }

            return sendGet(CATALOG_URL + "/catalog/books/" + id, res);
        });

        // Update book price.
        put("/catalog/books/:id/price", (req, res) -> {
            String id = req.params(":id");

            if (!validId(id)) {
                return error(res, 400, "Invalid book ID");
            }

            return sendPut(
                    CATALOG_URL + "/catalog/books/" + id + "/price",
                    req.body(),
                    res
            );
        });

        // Update book stock.
        put("/catalog/books/:id/stock", (req, res) -> {
            String id = req.params(":id");

            if (!validId(id)) {
                return error(res, 400, "Invalid book ID");
            }

            return sendPut(
                    CATALOG_URL + "/catalog/books/" + id + "/stock",
                    req.body(),
                    res
            );
        });

        // Purchase a book through Order Service.
        post("/purchase/:id", (req, res) -> {
            String id = req.params(":id");

            if (!validId(id)) {
                return error(res, 400, "Invalid book ID");
            }

            return sendPost(ORDER_URL + "/purchase/" + id, res);
        });

        System.out.println("Frontend Service is running on port 4568");
    }

    // Check whether the book ID contains only numbers.
    private static boolean validId(String id) {
        return id != null && id.matches("\\d+");
    }

    // Send a GET request.
    private static String sendGet(String url, spark.Response res)
            throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        return send(request, res);
    }

    // Send a PUT request.
    private static String sendPut(
            String url, String body, spark.Response res) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return send(request, res);
    }

    // Send a POST request.
    private static String sendPost(String url, spark.Response res)
            throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return send(request, res);
    }

    // Send the request and return the service response.
    private static String send(
            HttpRequest request, spark.Response res) throws Exception {

        HttpResponse<String> result = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        res.status(result.statusCode());
        res.type("application/json");

        return result.body();
    }

    // Return an error message.
    private static String error(
            spark.Response res, int status, String message) {

        res.status(status);
        res.type("application/json");

        return "{\"error\":\"" + message + "\"}";
    }
}