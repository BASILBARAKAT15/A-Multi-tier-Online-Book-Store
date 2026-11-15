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

/**
 * Catalog microservice (back-end tier, default port 4567).
 *
 * Owns the book catalog: title, topic, price and stock of each book.
 * Supports queries (by topic or by item number) and updates (price,
 * stock), plus an atomic "purchase one copy" operation used by the
 * order service. State is kept in memory and saved to data/books.json
 * after every change, so it survives restarts.
 */
public class CatalogService {

    // Converts objects to/from JSON for the REST replies and the data file.
    private static final Gson gson = new Gson();

    // Port and data directory can be overridden with environment variables
    // so the service can run on any machine or container.
    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "4567"));

    private static final Path DATA_FILE = Paths.get(
            System.getenv().getOrDefault("DATA_DIR", "data"), "books.json");

    // The catalog. Every access is done inside synchronized (books) because
    // Spark handles each request on its own thread.
    private static final List<Book> books =
            Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {

        loadBooks();

        port(PORT);

        get("/", (req, res) ->
                "Catalog Service is running on port " + PORT);

        // query: list every book.
        get("/catalog/books", (req, res) -> {
            res.type("application/json");
            synchronized (books) {
                return gson.toJson(books);
            }
        });

        // query-by-subject (query-string style): GET /catalog/search?topic=...
        // Returns the full record of every book on that topic.
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

            logSearch(topic, result);

            res.type("application/json");
            return gson.toJson(result);
        });

        // Appendix-style search: GET /search/<topic> returns [{id, title}].
        get("/search/:topic", (req, res) -> {
            String topic = req.params(":topic");
            List<java.util.Map<String, Object>> result = new ArrayList<>();
            List<Book> matches = new ArrayList<>();

            synchronized (books) {
                for (Book book : books) {
                    if (book.topic.equalsIgnoreCase(topic)) {
                        matches.add(book);
                        java.util.Map<String, Object> entry =
                                new java.util.LinkedHashMap<>();
                        entry.put("id", book.ID);
                        entry.put("title", book.title);
                        result.add(entry);
                    }
                }
            }

            logSearch(topic, matches);
            res.type("application/json");
            return gson.toJson(result);
        });

        // Appendix-style info: GET /info/<id> returns {title, quantity, price}.
        // The full record (id, topic, stock) is included as well so the
        // bundled web page can use the same endpoint.
        get("/info/:id", (req, res) -> {
            res.type("application/json");
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
                        System.out.println("[info]   " + book.describe());
                        java.util.Map<String, Object> info =
                                new java.util.LinkedHashMap<>();
                        info.put("title", book.title);
                        info.put("quantity", book.stock);
                        info.put("price", book.price);
                        info.put("id", book.ID);
                        info.put("topic", book.topic);
                        info.put("stock", book.stock);
                        return gson.toJson(info);
                    }
                }
            }

            System.out.println("[info]   book " + id + " not found");
            res.status(404);
            return "{\"error\":\"Book not found\"}";
        });

        // query-by-item: GET /catalog/books/<id> returns the full record.
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
                        System.out.println("[info]   " + book.describe());
                        res.type("application/json");
                        return gson.toJson(book);
                    }
                }
            }

            res.status(404);
            return "{\"error\":\"Book not found\"}";
        });

        // update: change the price. Body: {"price": 12.5}.
        // The old value is restored if the file cannot be saved.
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

                        System.out.println("[update] price of book " + id
                                + ": " + oldPrice + " -> " + update.price);

                        res.type("application/json");
                        return gson.toJson(book);
                    }
                }
            }

            res.status(404);
            return "{\"error\":\"Book not found\"}";
        });

        // update: change the stock. Body: {"stock": 10} sets it,
        // {"delta": 5} / {"delta": -2} increases / decreases it.
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

            // Send either {"stock": n} to set the quantity, or
            // {"delta": n} to increase (n > 0) or decrease (n < 0) it.
            if (update == null
                    || (update.stock == null) == (update.delta == null)
                    || (update.stock != null && update.stock < 0)) {
                res.status(400);
                return "{\"error\":\"Invalid stock\"}";
            }

            synchronized (books) {
                for (Book book : books) {
                    if (book.ID == id) {
                        int oldStock = book.stock;
                        int newStock = update.stock != null
                                ? update.stock : oldStock + update.delta;

                        if (newStock < 0) {
                            res.status(400);
                            return "{\"error\":\"Stock cannot be negative\"}";
                        }

                        book.stock = newStock;

                        try {
                            saveBooks();
                        } catch (IOException e) {
                            book.stock = oldStock;
                            res.status(500);
                            return "{\"error\":\"Could not save books\"}";
                        }

                        System.out.println("[update] stock of book " + id
                                + ": " + oldStock + " -> " + newStock);
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
                            System.out.println("[purchase] book " + id
                                    + " is out of stock");
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

                        System.out.println("[purchase] sold one copy of book "
                                + id + ", stock left: " + book.stock);
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
                "Catalog Service is running on port " + PORT);
    }

    /**
     * Loads data/books.json at start-up. If the file is missing or empty,
     * creates the four initial books and saves them.
     */
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

    /** Writes the whole catalog to data/books.json (call under the lock). */
    private static void saveBooks() throws IOException {
        Files.createDirectories(DATA_FILE.getParent());

        synchronized (books) {
            String json = gson.toJson(books);
            Files.writeString(
                    DATA_FILE, json, StandardCharsets.UTF_8);
        }
    }

    /** JSON body of the price update request. */
    private static class PriceUpdate {
        double price;
    }

    /** JSON body of the stock update request: exactly one field is set. */
    private static class StockUpdate {
        Integer stock;
        Integer delta;
    }

    /** Print search results as a small table in the server log. */
    private static void logSearch(String topic, List<Book> matches) {
        System.out.println("[search] topic \"" + topic + "\" -> "
                + matches.size() + " result(s)");
        for (Book book : matches) {
            System.out.println("         " + book.describe());
        }
    }
}