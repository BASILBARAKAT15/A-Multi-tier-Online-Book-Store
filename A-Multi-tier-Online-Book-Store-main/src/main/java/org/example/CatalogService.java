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
import java.util.List;

public class CatalogService {

    private static final Gson gson = new Gson();
    private static final Path DATA_FILE = Path.of("data", "books.json");

    private static final Object lock = new Object();
    private static final List<Book> books = new ArrayList<>();

    public static void main(String[] args) {

        loadBooks();
        port(4567);

        // Check that the service is running.
        get("/", (req, res) -> "Catalog Service is running");

        // Return all books.
        get("/catalog/books", (req, res) -> {
            res.type("application/json");

            synchronized (lock) {
                return gson.toJson(books);
            }
        });

        // Search books by topic.
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

            res.type("application/json");
            return gson.toJson(result);
        });

        // Get one book by ID.
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

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        // Update book price.
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

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        // Update book stock.
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

            if (update == null || update.stock < 0) {
                return error(res, 400, "Invalid stock");
            }

            synchronized (lock) {
                Book book = findBook(id);

                if (book == null) {
                    return error(res, 404, "Book not found");
                }

                int oldStock = book.stock;
                book.stock = update.stock;

                try {
                    saveBooks();
                } catch (IOException e) {
                    book.stock = oldStock;
                    return error(res, 500, "Could not save books");
                }

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        // Purchase one book.
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
                    return error(res, 409, "Book is out of stock");
                }

                book.stock--;

                try {
                    saveBooks();
                } catch (IOException e) {
                    book.stock++;
                    return error(res, 500, "Could not save books");
                }

                res.type("application/json");
                return gson.toJson(book);
            }
        });

        System.out.println("Catalog Service is running on port 4567");
    }

    // Find a book using its ID.
    private static Book findBook(int id) {
        for (Book book : books) {
            if (book.ID == id) {
                return book;
            }
        }
        return null;
    }

    // Read the book ID from the request.
    private static Integer getId(spark.Request req) {
        try {
            return Integer.parseInt(req.params(":id"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Return an error response.
    private static String error(spark.Response res, int status, String message) {
        res.status(status);
        res.type("application/json");
        return "{\"error\":\"" + message + "\"}";
    }

    // Load books from the JSON file.
    private static void loadBooks() {
        try {
            Files.createDirectories(DATA_FILE.getParent());

            if (Files.exists(DATA_FILE) && Files.size(DATA_FILE) > 0) {
                Type type = new TypeToken<List<Book>>() {}.getType();

                List<Book> savedBooks = gson.fromJson(
                        Files.readString(DATA_FILE), type);

                if (savedBooks != null && !savedBooks.isEmpty()) {
                    books.addAll(savedBooks);
                    System.out.println("Books loaded successfully");
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

    // Save books to the JSON file.
    private static void saveBooks() throws IOException {
        Files.writeString(
                DATA_FILE,
                gson.toJson(books),
                StandardCharsets.UTF_8
        );
    }

    private static class PriceUpdate {
        double price;
    }

    private static class StockUpdate {
        int stock;
    }
}