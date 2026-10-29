
package org.example;

import static spark.Spark.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OrderService {

    private static final Gson gson = new Gson();

    private static final HttpClient client =
            HttpClient.newHttpClient();

    private static final String CATALOG_URL =
            "http://catalog:4567";

    private static final Path ORDERS_FILE =
            Path.of("data", "orders.json");

    private static final List<JsonObject> orders =
            Collections.synchronizedList(new ArrayList<>());

    // Load previously saved orders from disk.
    private static void loadOrders() throws IOException {
        synchronized (orders) {
            if (!Files.exists(ORDERS_FILE)) {
                Files.createDirectories(ORDERS_FILE.getParent());
                Files.writeString(ORDERS_FILE, "[]");
                return;
            }

            String json = Files.readString(ORDERS_FILE);

            Type orderListType =
                    new TypeToken<List<JsonObject>>() {}.getType();

            List<JsonObject> savedOrders =
                    gson.fromJson(json, orderListType);

            orders.clear();

            if (savedOrders != null) {
                orders.addAll(savedOrders);
            }

            System.out.println(
                    "Loaded " + orders.size() + " saved orders");
        }
    }

    // Save all orders to disk.
    private static void saveOrders() throws IOException {
        Files.createDirectories(ORDERS_FILE.getParent());

        Path tempFile = ORDERS_FILE.resolveSibling("orders.tmp");

        Files.writeString(tempFile, gson.toJson(orders));

        try {
            Files.move(
                    tempFile,
                    ORDERS_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Fall back if atomic move is not supported.
            Files.move(
                    tempFile,
                    ORDERS_FILE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void main(String[] args) throws Exception {

        // Restore the order history before accepting requests.
        loadOrders();

        port(4569);

        get("/", (req, res) ->
                "Order Service is running on port 4569");

        // Purchase one copy of a book.
        post("/purchase/:id", (req, res) -> {

            String id = req.params(":id");

            if (!id.matches("\\d+")) {
                res.type("application/json");
                res.status(400);
                return "{\"error\":\"Invalid book ID\"}";
            }

            HttpRequest purchaseRequest =
                    HttpRequest.newBuilder()
                            .uri(URI.create(
                                    CATALOG_URL
                                            + "/catalog/books/"
                                            + id
                                            + "/purchase"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();

            HttpResponse<String> purchaseResponse =
                    client.send(
                            purchaseRequest,
                            HttpResponse.BodyHandlers.ofString());

            res.type("application/json");

            // Forward Catalog errors, including out-of-stock (409).
            if (purchaseResponse.statusCode() != 200) {
                res.status(purchaseResponse.statusCode());
                return purchaseResponse.body();
            }

            JsonObject book = gson.fromJson(
                    purchaseResponse.body(),
                    JsonObject.class);

            JsonObject order = new JsonObject();

            int itemNumber = book.has("itemNumber")
                    ? book.get("itemNumber").getAsInt()
                    : book.get("ID").getAsInt();

            order.addProperty("itemNumber", itemNumber);
            order.addProperty(
                    "title", book.get("title").getAsString());
            order.addProperty(
                    "price", book.get("price").getAsDouble());
            order.addProperty("status", "SUCCESS");

            synchronized (orders) {
                orders.add(order);

                try {
                    saveOrders();
                } catch (IOException e) {
                    orders.remove(orders.size() - 1);

                    System.err.println(
                            "Could not save order: " + e.getMessage());

                    res.status(500);
                    return "{\"error\":\"Could not save order history\"}";
                }
            }

            System.out.println(
                    "bought book " + order.get("title").getAsString());

            res.status(201);
            return gson.toJson(order);
        });

        // Return all orders, including those loaded from disk.
        get("/orders", (req, res) -> {
            res.type("application/json");

            synchronized (orders) {
                return gson.toJson(orders);
            }
        });

        System.out.println(
                "Order Service is running on port 4569");
    }
}
