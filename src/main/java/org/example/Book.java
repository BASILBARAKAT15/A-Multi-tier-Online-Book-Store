package org.example;

public class Book {
    public int ID;
    public String title;
    public String topic;
    public double price;
    public int stock;

    public Book(int itemNumber, String title, String topic,
                double price, int stock) {
        this.ID = itemNumber;
        this.title = title;
        this.topic = topic;
        this.price = price;
        this.stock = stock;
    }

    /** One-line, human readable form used in server logs. */
    public String describe() {
        return String.format("#%d | %-50s | %-21s | $%6.2f | stock: %d",
                ID, title, topic, price, stock);
    }
}