package org.example;

import static spark.Spark.*;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final Path DATA_FILE = Path.of(
            System.getenv().getOrDefault("DATA_DIR", "data"), "books.json");

    // One lock protects the catalog: Spark handles each request on its own
    // thread, so every read or change of "books" happens inside it.
    private static final Object lock = new Object();
    private static final List<Book> books = new ArrayList<>();

    public static void main(String[] args) {

        loadBooks();
        port(PORT);

        // Check that the service is running.
        get("/", (req, res) ->
                "Catalog Service is running on port " + PORT);

        // Return all books.
        get("/catalog/books", (req, res) -> {
            res.type("application/json");

            synchronized (lock) {
                return gson.toJson(books);
            }
        });

        // query-by-subject (query-string style): GET /catalog/search?topic=...
        // Returns the full record of every book on that topic.
        get("/catalog/search", (req, res) -> {
            String topic = req.queryParams("topic");

            if (topic == null || topic.isBlank()) {
                return error(res, 400, "Please provide a topic");
            }

            List<Book> result = new ArrayList<>();

            synchronized (lock) {
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
            List<Map<String, Object>> result = new ArrayList<>();
            List<Book> matches = new ArrayList<>();

            synchronized (lock) {
                for (Book book : books) {
                    if (book.topic.equalsIgnoreCase(topic)) {
                        matches.add(book);

                        Map<String, Object> entry = new LinkedHashMap<>();
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
            Integer id = getId(req);

            if (id == null) {
                return error(res, 400, "Invalid book ID");
            }

            synchronized (lock) {
                Book book = findBook(id);

                if (book == null) {
                    System.out.println("[info]   book " + id + " not found");
                    return error(res, 404, "Book not found");
                }

                System.out.println("[info]   " + book.describe());

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("title", book.title);
                info.put("quantity", book.stock);
                info.put("price", book.price);
                info.put("id", book.ID);
                info.put("topic", book.topic);
                info.put("stock", book.stock);

                res.type("application/json");
                return gson.toJson(info);
            }
        });

        // query-by-item: GET /catalog/books/<id> returns the full record.
        get("/catalog/books/:id", (req, res) -> {
            Integer id = getId(req);

            if (id == null) {
                return error(res, 400, "Invalid book ID");
            }

            synchronized (lock) {
                Book book = findBook(id);

                if (book == null) {
                    return error(res, 404, "Book not found");
                }

                System.out.println("[info]   " + book.describe());

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        // update: change the price. Body: {"price": 12.5}.
        // The old value is restored if the file cannot be saved.
        put("/catalog/books/:id/price", (req, res) -> {
            Integer id = getId(req);

            if (id == null) {
                return error(res, 400, "Invalid book ID");
            }

            PriceUpdate update;

            try {
                update = gson.fromJson(req.body(), PriceUpdate.class);
            } catch (Exception e) {
                return error(res, 400, "Invalid price");
            }

            if (update == null || !Double.isFinite(update.price)
                    || update.price < 0) {
                return error(res, 400, "Invalid price");
            }

            synchronized (lock) {
                Book book = findBook(id);

                if (book == null) {
                    return error(res, 404, "Book not found");
                }

                double oldPrice = book.price;
                book.price = update.price;

                try {
                    saveBooks();
                } catch (IOException e) {
                    book.price = oldPrice;
                    return error(res, 500, "Could not save books");
                }

                System.out.println("[update] price of book " + id
                        + ": " + oldPrice + " -> " + update.price);

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        // update: change the stock. Body: {"stock": 10} sets it,
        // {"delta": 5} / {"delta": -2} increases / decreases it.
        put("/catalog/books/:id/stock", (req, res) -> {
            Integer id = getId(req);

            if (id == null) {
                return error(res, 400, "Invalid book ID");
            }

            StockUpdate update;

            try {
                update = gson.fromJson(req.body(), StockUpdate.class);
            } catch (Exception e) {
                return error(res, 400, "Invalid stock");
            }

            // Exactly one of "stock" and "delta" must be given.
            if (update == null
                    || (update.stock == null) == (update.delta == null)
                    || (update.stock != null && update.stock < 0)) {
                return error(res, 400, "Invalid stock");
            }

            synchronized (lock) {
                Book book = findBook(id);

                if (book == null) {
                    return error(res, 404, "Book not found");
                }

                int oldStock = book.stock;
                int newStock = update.stock != null
                        ? update.stock : oldStock + update.delta;

                if (newStock < 0) {
                    return error(res, 400, "Stock cannot be negative");
                }

                book.stock = newStock;

                try {
                    saveBooks();
                } catch (IOException e) {
                    book.stock = oldStock;
                    return error(res, 500, "Could not save books");
                }

                System.out.println("[update] stock of book " + id
                        + ": " + oldStock + " -> " + newStock);

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        // Purchase one copy safely: checking and decreasing the stock
        // happen under the same lock, so two buyers can never both get
        // the last copy.
        post("/catalog/books/:id/purchase", (req, res) -> {
            Integer id = getId(req);

            if (id == null) {
                return error(res, 400, "Invalid book ID");
            }

            synchronized (lock) {
                Book book = findBook(id);

                if (book == null) {
                    return error(res, 404, "Book not found");
                }

                if (book.stock <= 0) {
                    System.out.println("[purchase] book " + id
                            + " is out of stock");
                    return error(res, 409, "Book is out of stock");
                }

                book.stock--;

                try {
                    saveBooks();
                } catch (IOException e) {
                    book.stock++;
                    return error(res, 500, "Could not save books");
                }

                System.out.println("[purchase] sold one copy of book "
                        + id + ", stock left: " + book.stock);

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        System.out.println("Catalog Service is running on port " + PORT);
    }

    // Find a book using its ID (call while holding the lock).
    private static Book findBook(int id) {
        for (Book book : books) {
            if (book.ID == id) {
                return book;
            }
        }
        return null;
    }

    // Read the book ID from the request; null if it is not a number.
    private static Integer getId(spark.Request req) {
        try {
            return Integer.parseInt(req.params(":id"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Return an error response as JSON.
    private static String error(spark.Response res, int status, String message) {
        res.status(status);
        res.type("application/json");
        return "{\"error\":\"" + message + "\"}";
    }

    // Print search results as a small table in the server log.
    private static void logSearch(String topic, List<Book> matches) {
        System.out.println("[search] topic \"" + topic + "\" -> "
                + matches.size() + " result(s)");
        for (Book book : matches) {
            System.out.println("         " + book.describe());
        }
    }

    // Load books from data/books.json at start-up. If the file is missing
    // or empty, create the four initial books and save them.
    private static void loadBooks() {
        try {
            Files.createDirectories(DATA_FILE.getParent());

            if (Files.exists(DATA_FILE) && Files.size(DATA_FILE) > 0) {
                Type type = new TypeToken<List<Book>>() {}.getType();

                List<Book> savedBooks = gson.fromJson(
                        Files.readString(DATA_FILE), type);

                if (savedBooks != null && !savedBooks.isEmpty()) {
                    books.addAll(savedBooks);
                    System.out.println("Books loaded from " + DATA_FILE);
                    return;
                }
            }

            // Add default books if the file is empty.
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
            throw new RuntimeException("Could not load books", e);
        }
    }

    // Save books to the JSON file (call while holding the lock).
    private static void saveBooks() throws IOException {
        Files.writeString(
                DATA_FILE,
                gson.toJson(books),
                StandardCharsets.UTF_8
        );
    }

    // JSON body of the price update request.
    private static class PriceUpdate {
        double price;
    }

    // JSON body of the stock update request: exactly one field is set.
    private static class StockUpdate {
        Integer stock;
        Integer delta;
    }
}
