package org.yashasvi.chessserver.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.yashasvi.chessserver.selenium.ChessAutomator;
import org.yashasvi.chessserver.selenium.ChessGameAttrs;
import org.yashasvi.chessserver.selenium.ChessSide;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ChessWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor();

    public ChessWebSocketHandler() {
        keepAliveScheduler.scheduleAtFixedRate(() -> {
            for (WebSocketSession session : sessions.values()) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new PingMessage(ByteBuffer.wrap("".getBytes())));
                    }
                } catch (Exception e) {
                    System.err.println("[WS] Failed to send ping to " + session.getId() + ": " + e.getMessage());
                }
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        System.out.println("[WS] Client " + session.getId() + " connected.");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode data = mapper.readTree(message.getPayload());
            String action = data.path("action").asText();

            switch (action) {
                case "ping" -> {
                    session.sendMessage(new TextMessage("{\"type\":\"ping\", \"status\":\"Ok\"}"));
                }

                case "init" -> {
                    String pgn = data.has("pgn") && !data.get("pgn").isNull() ? data.get("pgn").asText() : null;
                    int moveNo = data.has("move_no") && !data.get("move_no").isNull() ? data.get("move_no").asInt() : -1;
                    String sideStr;
                    ChessSide side;

                    // create new automator for this session
                    ChessAutomator automator;
                    if (pgn != null) {
                        automator = new ChessAutomator(new ChessGameAttrs(pgn, moveNo));
                        side = automator.getSide();
                        sideStr = side == ChessSide.WHITE ? "white" : "black";
                    } else {
                        sideStr = data.has("side") ? data.get("side").asText().toLowerCase() : "white";
                        side = sideStr.equals("black") ? ChessSide.BLACK : ChessSide.WHITE;
                        automator = new ChessAutomator(side);
                    }


                    // save automator in session attributes
                    session.getAttributes().put("automator", automator);
                    while (automator.getEngineResult() == null) {
                        try {
                            Thread.sleep(100);
                        } catch (Exception ignored) {
                        }
                        session.sendMessage(new PingMessage(ByteBuffer.wrap("".getBytes())));
                    }
                    if ((Boolean) automator.getEngineResult().get("engine_active")) {
                        Map<java.lang.String, java.lang.Object> move = automator.getNextBestMove(null);

                        ObjectNode response = mapper.createObjectNode();
                        if (move != null) {
                            ObjectNode moveNode = mapper.createObjectNode();
                            final String piece = (String) move.get("piece"), from = (String) move.get("from"), to = (String) move.get("to");
                            moveNode.put("piece", piece);
                            moveNode.put("from", from);
                            moveNode.put("to", to);
                            response.set("move", moveNode);
                            response.put("type", "engine_move");
                            response.put("status", piece + " to " + to);

                            ObjectNode stateWrapper = mapper.createObjectNode();
                            stateWrapper.setAll((ObjectNode) mapper.valueToTree(move.get("state")));
                            response.set("state", stateWrapper);
                        } else {
                            response.put("error", "No valid moves found or game over.");
                        }

                        Executors.newSingleThreadExecutor().submit(() -> {
                            try {
                                while (!ChessAutomator.isLoaded()) {
                                    try {
                                        Thread.sleep(500);
                                    } catch (Exception ignored) {
                                    }
                                }
                                try {
                                    Thread.sleep(500);
                                } catch (Exception ignored) {
                                }
                                session.sendMessage(new TextMessage(response.toString()));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                    }
                    // run bot loading in a background thread
                    Executors.newSingleThreadExecutor().submit(() -> {
                        try {
                            automator.loadBotList();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }

                        // wait until static flag is set
                        int retry_count = 0;
                        while (!ChessAutomator.isLoaded()) {
                            try {
                                final String statusMessage = "Loading bots" + ".".repeat(retry_count % 4);
                                session.sendMessage(new TextMessage(String.format("{\"status\":\"%s\"}", statusMessage)));
                                Thread.sleep(250);
                                System.out.println("[WS] " + statusMessage);
                            } catch (Exception ignored) {
                            }
                            retry_count++;
                        }

                        try {
                            ObjectNode response = mapper.createObjectNode();
                            response.put("status", "Initialized as " + sideStr.toUpperCase());
                            response.put("type", "init");
                            response.put("side", sideStr);

                            var botsArray = mapper.createArrayNode();
                            for (Map<String, Object> bot : ChessAutomator.BOTS) {
                                ObjectNode b = mapper.createObjectNode();
                                b.put("id", (Integer) bot.get("id"));
                                b.put("name", (String) bot.get("name"));
                                if (bot.containsKey("rating") && bot.get("rating") != null) {
                                    b.put("rating", bot.get("rating").toString());
                                }
                                if (bot.containsKey("avatar")) {
                                    b.put("avatar", (String) bot.get("avatar"));
                                }
                                b.put("is_engine", (Boolean) bot.get("is_engine"));
                                botsArray.add(b);
                            }
                            response.set("bots", botsArray);
                            response.set("current_bot", mapper.valueToTree(automator.getSelectedBot()));
                            ObjectNode stateWrapper = mapper.createObjectNode();
                            stateWrapper.setAll((ObjectNode) mapper.valueToTree(automator.getReadableBoardState(automator.getInitialState())));
                            response.set("state", stateWrapper);

                            session.sendMessage(new TextMessage(response.toString()));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }

                case "next_move" -> {
                    ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                    if (automator == null) {
                        session.sendMessage(new TextMessage("{\"error\":\"Bot not initialized.\"}"));
                        return;
                    }

                    String opponentMove = data.has("opponent_move") && !data.get("opponent_move").isNull() ? data.get("opponent_move").asText() : null;
                    if (opponentMove == null) {
                        session.sendMessage(new TextMessage("{\"error\":\"Opponent move cannot be null.\"}"));
                        return;
                    }

                    Map<java.lang.String, java.lang.Object> move = automator.getNextBestMove(opponentMove);

                    ObjectNode response = mapper.createObjectNode();
                    if (move != null) {
                        ObjectNode moveNode = mapper.createObjectNode();
                        final String piece = (String) move.get("piece"), from = (String) move.get("from"), to = (String) move.get("to");
                        moveNode.put("piece", piece);
                        moveNode.put("from", from);
                        moveNode.put("to", to);
                        response.set("move", moveNode);
                        response.put("type", "engine_move");
                        response.put("status", piece + " to " + to);

                        ObjectNode stateWrapper = mapper.createObjectNode();
                        stateWrapper.setAll((ObjectNode) mapper.valueToTree(move.get("state")));
                        response.set("state", stateWrapper);
                    } else {
                        response.put("error", "No valid moves found or game over.");
                    }
                    session.sendMessage(new TextMessage(response.toString()));
                }

                case "promote" -> {
                    ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                    if (automator == null) {
                        session.sendMessage(new TextMessage("{\"error\":\"Bot not initialized.\"}"));
                        return;
                    }

                    String piece = data.has("promote_to") ? data.get("promote_to").asText() : "q";
                    automator.completePromotion(piece);

                    ObjectNode response = mapper.createObjectNode();
                    response.put("status", "Promoted to " + piece.toUpperCase());
                    response.put("type", "promote");
                    response.put("piece", piece);
                    session.sendMessage(new TextMessage(response.toString()));
                }

                case "select_bot" -> {
                    ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                    if (automator == null) {
                        session.sendMessage(new TextMessage("{\"error\":\"Bot not initialized.\"}"));
                        return;
                    }

                    int botId = data.has("bot_id") ? data.get("bot_id").asInt() : 0;
                    Integer engineLevel = data.has("engine_level") && !data.get("engine_level").isNull() ? data.get("engine_level").asInt() : null;

                    automator.selectBot(botId, engineLevel);

                    ObjectNode response = mapper.createObjectNode();
                    response.put("status", "Selected bot: " + automator.getSelectedBot().get("name"));
                    response.put("type", "select_bot");
                    response.put("current_bot", String.format("%s (Rating: %s)", automator.getSelectedBot().get("name"), automator.getSelectedBot().get("rating")));
                    session.sendMessage(new TextMessage(response.toString()));
                }

                case "undo" -> {
                    ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                    if (automator == null) {
                        session.sendMessage(new TextMessage("{\"error\":\"Bot not initialized.\"}"));
                        return;
                    }

                    automator.undoLastMove();

                    ObjectNode response = mapper.createObjectNode();
                    response.put("status", "Undid last move.");
                    response.put("type", "undo");
                    session.sendMessage(new TextMessage(response.toString()));
                }

                default -> session.sendMessage(new TextMessage("{\"error\":\"Unknown action\"}"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            session.sendMessage(new TextMessage("{\"error\":\"" + e.getMessage() + "\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
        automator.quit();
        sessions.remove(session.getId());
        System.out.println("[WS] Client " + session.getId() + " disconnected.");
    }
}
