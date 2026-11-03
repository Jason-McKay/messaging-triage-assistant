package com.messaging.triage.web;

import com.messaging.triage.chat.ChatReply;
import com.messaging.triage.chat.ChatRequest;
import com.messaging.triage.chat.ChatService;
import com.messaging.triage.chat.ModelUnavailableException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/chat}: a question answered with read-only tools; 503 when the model is down. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private final ChatService chatService;

  public ChatController(ChatService chatService) {
    this.chatService = chatService;
  }

  @PostMapping
  public ChatReply chat(@Valid @RequestBody ChatRequest request) {
    return chatService.chat(request);
  }

  @ExceptionHandler(ModelUnavailableException.class)
  public ProblemDetail modelUnavailable(ModelUnavailableException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    problem.setTitle("Model unavailable");
    return problem;
  }
}
