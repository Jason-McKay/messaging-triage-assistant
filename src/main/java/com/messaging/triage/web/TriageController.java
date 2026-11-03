package com.messaging.triage.web;

import com.messaging.triage.triage.LatestTriageHolder;
import com.messaging.triage.triage.TriageResult;
import com.messaging.triage.triage.TriageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/triage} runs a triage and always answers 200 with what it found: a source
 * or the model being down is part of the result ({@code status}, {@code model.usable}), not
 * an error. {@code GET /api/triage/latest} is the last run, 404 before the first.
 */
@RestController
@RequestMapping("/api/triage")
public class TriageController {

  private final TriageService triageService;
  private final LatestTriageHolder latest;

  public TriageController(TriageService triageService, LatestTriageHolder latest) {
    this.triageService = triageService;
    this.latest = latest;
  }

  @PostMapping
  public TriageResult triage() {
    return triageService.run();
  }

  @GetMapping("/latest")
  public ResponseEntity<TriageResult> latest() {
    return latest.latest().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
