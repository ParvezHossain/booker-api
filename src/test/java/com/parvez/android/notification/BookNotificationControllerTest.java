package com.parvez.android.notification;

import com.parvez.android.controller.BookNotificationController;
import com.parvez.android.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookNotificationControllerTest {
    @Test
    void invalidCursorReturns400EvenWhenClientAcceptsOnlyEventStream() throws Exception {
        var store = new BookEventStore(null) {
            @Override public long latestId() { return 5; }
        };
        var stream = new BookEventStream(store, 1, 1000);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new BookNotificationController(stream))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/api/books/events").accept("text/event-stream")
                            .header("Last-Event-ID", "invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/json"));
        } finally {
            stream.shutdown();
        }
    }
}
