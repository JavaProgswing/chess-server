package org.yashasvi.chessserver.selenium;

public enum ChessSide {
    WHITE("white"), BLACK("black");

    private final String value;

    ChessSide(String v) {
        this.value = v;
    }

    public String value() {
        return value;
    }
}
