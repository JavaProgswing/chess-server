package org.yashasvi.chessserver.selenium;

public class ChessGameAttrs {
    private final String pgn;
    private final int moveNumber;

    public ChessGameAttrs(String pgn, int moveNumber) {
        this.pgn = pgn;
        this.moveNumber = moveNumber;
    }

    public String getPgn() {
        return pgn;
    }

    public int getMoveNumber() {
        return moveNumber;
    }
}
