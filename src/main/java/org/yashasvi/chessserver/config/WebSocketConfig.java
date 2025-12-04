package org.yashasvi.chessserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.yashasvi.chessserver.handler.ChessWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChessWebSocketHandler chessHandler;

    public WebSocketConfig(ChessWebSocketHandler chessHandler) {
        this.chessHandler = chessHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chessHandler, "/ws")
                .setAllowedOrigins("*");
    }
}
