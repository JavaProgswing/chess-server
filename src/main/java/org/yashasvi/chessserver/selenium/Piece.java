package org.yashasvi.chessserver.selenium;


public class Piece {
    private final String type; // p, r, n, b, q, k
    private final String color; // "white" or "black"

    public Piece(String type, String color) {
        this.type = type;
        this.color = color;
    }

    public String getType() {
        return type;
    }

    public String getColor() {
        return color;
    }

    @Override
    public String toString() {
        return color + " " + type;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Piece)) return false;
        Piece p = (Piece) o;
        return this.type.equals(p.type) && this.color.equals(p.color);
    }

    @Override
    public int hashCode() {
        return (type + "#" + color).hashCode();
    }
}