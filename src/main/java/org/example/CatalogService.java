package org.example;

import static spark.Spark.*;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CatalogService {

    private static final Gson gson = new Gson();

    private static final Path DATA_FILE =
            Paths.get("data", "books.json");

    private static final List<Book> books =
            Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {

        loadBooks();

        port(4567);

        get("/", (req, res) ->
                "Catalog Service is running on port 4567");

        get("/catalog/books", (req, res) -> {
            res.type("application/json");
            synchronized (books) {
                return gson.toJson(books);
            }
        });

        get("/catalog/search", (req, res) -> {
            String topic = req.queryParams("topic");

            if (topic == null || topic.isBlank()) {
                res.status(400);
                return "{\"error\":\"Please provide a topic\"}";
            }

            List<Book> result = new ArrayList<>();

            synchronized (books) {
                for (Book book : books) {
                    if (book.topic.equalsIgnoreCase(topic)) {
                        result.add(book);
                    }
                }
            }

            res.type("application/json");
            return gson.toJson(result);
        });

        get("/catalog/books/:id", (req, res) -> {
            int id;

            try {
                id = Integer.parseInt(req.params(":id"));
            } catch (NumberFormatException e) {
                res.status(400);
                return "{\"error\":\"Invalid book ID\"}";
            }

            synchronized (books) {
                for (Book book : books) {
                    if (book.ID == id) {
                        res.type("application/json");
                        return gson.toJson(book);
                    }
                }
            }

            res.status(404);
            return "{\"error\":\"Book not found\"}";
        });

        put("/catalog/books/:id/price", (req, res) -> {
            int id;
            PriceUpdate update;

            try {
                id = Integer.parseInt(req.params(":id"));
                update = gson.fromJson(req.body(), PriceUpdate.class);
            } catch (Exception e) {
                res.status(400);
                return "{\"error\":\"Invalid request\"}";
            }

            if (update == null || !Double.isFinite(update.price)
                    || update.price < 0) {
                res.status(400);
                return "{\"error\":\"Invalid price\"}";
            }

            synchronized (books) {
                for (Book book : books) {
                    if (book.ID == id) {
                        double oldPrice = book.price;
                        book.price = update.price;

                        try {
                            saveBooks();
                        } catch (IOException e) {
                            book.price = oldPrice;
                            res.status(500);
                            return "{\"error\":\"Could not save books\"}";
                        }

                        res.type("application/json");
                        return gson.toJson(book);
                    }
                }
            }

            res.status(404);
            return "{\"error\":\"Book not found\"}";
        });

        put("/catalog/books/:id/stock", (req, res) -> {
            int id;
            StockUpdate update;

            try {
                id = Integer.parseInt(req.params(":id"));
                update = gson.fromJson(req.body(), StockUpdate.class);
            } catch (Exception e) {
                res.status(400);
                return "{\"error\":\"Invalid request\"}";
            }

            if (update == null || update.stock < 0) {
                res.status(400);
                return "{\"error\":\"Invalid stock\"}";
            }

            synchronized (books) {
                for (Book book : books) {
                    if (book.ID == id) {
                        int oldStock = book.stock;
                        book.stock = update.stock;

                        try {
                            saveBooks();
                        } catch (IOException e) {
                            book.stock = oldStock;
                            res.status(500);
                            return "{\"error\":\"Could not save books\"}";
                        }

                        res.type("application/json");
                        return gson.toJson(book);
                    }
                }
            }

            res.status(404);
            return "{\"error\":\"Book not found\"}";
        });
// Purchase one copy safely.
// Checking and decreasing stock happen under the same lock.
        post("/catalog/books/:id/purchase", (req, res) -> {
            int id;

            try {
                id = Integer.parseInt(req.params(":id"));
            } catch (NumberFormatException e) {
                res.type("application/json");
                res.status(400);
                return "{\"error\":\"Invalid book ID\"}";
            }

            res.type("application/json");

            synchronized (books) {
                for (Book book : books) {
                    if (book.ID == id) {

                        // Reject the purchase if no copies remain.
                        if (book.stock <= 0) {
                            res.status(409);
                            return "{\"error\":\"Book is out of stock\"}";
                        }

                        // Save the old value so we can undo the change if saving fails.
                        int oldStock = book.stock;
                        book.stock--;

                        try {
                            saveBooks();
                        } catch (IOException e) {
                            book.stock = oldStock;
                            res.status(500);
                            return "{\"error\":\"Could not save books\"}";
                        }

                        res.status(200);
                        return gson.toJson(book);
                    }
                }
            }

            res.type("application/json");
            res.status(404);
            return "{\"error\":\"Book not found\"}";
        });

        System.out.println(
                "Catalog Service is running on port 4567");
    }

    private static void loadBooks() {
        try {
            Files.createDirectories(DATA_FILE.getParent());

            if (Files.exists(DATA_FILE)
                    && Files.size(DATA_FILE) > 0) {

                Type listType =
                        new TypeToken<List<Book>>() {}.getType();

                List<Book> savedBooks =
                        gson.fromJson(
                                Files.readString(DATA_FILE),
                                listType);

                if (savedBooks != null && !savedBooks.isEmpty()) {
                    books.addAll(savedBooks);
                    System.out.println(
                            "Books loaded from " + DATA_FILE);
                    return;
                }
            }

            // Create the initial catalog only if no saved data exists.
            books.add(new Book(1,
                    "How to get a good grade in DOS in 40 minutes a day",
                    "distributed systems", 20, 10));

            books.add(new Book(2,
                    "RPCs for Noobs",
                    "distributed systems", 25, 8));

            books.add(new Book(3,
                    "Xen and the Art of Surviving Undergraduate School",
                    "undergraduate school", 30, 5));

            books.add(new Book(4,
                    "Cooking for the Impatient Undergrad",
                    "undergraduate school", 15, 7));

            saveBooks();

        } catch (Exception e) {
            throw new RuntimeException(
                    "Could not load catalog data", e);
        }
    }

    private static void saveBooks() throws IOException {
        Files.createDirectories(DATA_FILE.getParent());

        synchronized (books) {
            String json = gson.toJson(books);
            Files.writeString(
                    DATA_FILE, json, StandardCharsets.UTF_8);
        }
    }

    private static class PriceUpdate {
        double price;
    }

    private static class StockUpdate {
        int stock;
    }
}