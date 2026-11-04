package com.messaging.triage.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR 0005: evidence must be in the group's own data, as the model was shown it. */
class EvidenceCheckTest {

  private static final List<String> SHOWN = List.of(
    "channel=EMAIL",
    "Template '<value>' not found",
    "no template configured for key order-refunded",
    "Missing required field: address",
    "{\"orderId\":\"o-3001\",\"note\":\"[text, 124 chars]\"}");

  @Test
  void aQuoteHoldsDespiteCaseSpacingQuoteMarksAndEdgePunctuation() {
    String curly = (char) 0x201C + "no template configured" + (char) 0x201D;
    String fullWidth = "" + (char) 0xFF4E + (char) 0xFF4F + " template configured";

    assertThat(check("No template  configured for KEY order-refunded", "Missing required field: address.",
      curly, fullWidth, "\"orderId\":\"o-3001\"").supported()).isTrue();
  }

  @Test
  void aQuoteCutWithAnEllipsisHoldsOnlyIfEveryPartDoes() {
    String ellipsis = "no template configured " + (char) 0x2026 + " order-refunded";

    assertThat(check("no template configured ... order-refunded", ellipsis).supported()).isTrue();
    assertThat(check("no template configured ... order-shipped").supported()).isFalse();
  }

  @Test
  void madeUpQuotesAreUnmatchedAndOnlyTheOnesThatHeldAreKept() {
    EvidenceCheck.Result result = check("Missing required field: address", "Connection refused by the SMTP relay",
      "Missing required field: orderId");

    assertThat(result.supported()).isFalse();
    assertThat(result.matched()).containsExactly("Missing required field: address");
    assertThat(result.unmatched()).isEqualTo(2);
  }

  @Test
  void placeholdersAndScrapsAreNotEvidence() {
    // Each of these occurs in what was shown, and says nothing about why.
    assertThat(check("'<value>'").supported()).isFalse();
    assertThat(check("[text, 124 chars]").supported()).isFalse();
    assertThat(check("key").supported()).as("fewer than 4 letters").isFalse();
    assertThat(check("...").supported()).isFalse();
    assertThat(check("Template '<value>' not found").supported()).as("words around a placeholder").isTrue();
  }

  @Test
  void aCountHoldsWhenItIsTheGroupsCount() {
    assertThat(EvidenceCheck.check(List.of("3 failures"), SHOWN, 3).supported()).isTrue();
    assertThat(EvidenceCheck.check(List.of("3 failures in this group"), SHOWN, 3).supported()).isTrue();
    assertThat(EvidenceCheck.check(List.of("3"), SHOWN, 3).supported()).isTrue();
    assertThat(EvidenceCheck.check(List.of("4 failures"), SHOWN, 3).supported()).isFalse();
    assertThat(EvidenceCheck.check(List.of("3 failures after 2 retries"), SHOWN, 3).supported()).isFalse();
    assertThat(EvidenceCheck.check(List.of("timed out after 3"), SHOWN, 3).supported())
      .as("a number with words that are not about the count").isFalse();
  }

  @Test
  void noEvidenceIsNotSupported() {
    assertThat(EvidenceCheck.check(null, SHOWN, 1).supported()).isFalse();
    assertThat(check().supported()).isFalse();
    EvidenceCheck.Result blanks = EvidenceCheck.check(Arrays.asList(" ", null), SHOWN, 1);
    assertThat(blanks.supported()).isFalse();
    assertThat(blanks.unmatched()).isEqualTo(2);
  }

  @Test
  void everyQuoteIsCheckedButOnlySoManyAreKept() {
    List<String> evidence = new ArrayList<>(Collections.nCopies(EvidenceCheck.MAX_KEPT + 2, "channel=EMAIL"));
    assertThat(EvidenceCheck.check(evidence, SHOWN, 1).matched()).hasSize(EvidenceCheck.MAX_KEPT);

    evidence.add("made up");
    assertThat(EvidenceCheck.check(evidence, SHOWN, 1).supported()).as("a bad quote past the kept ones").isFalse();
  }

  @Test
  void aQuoteSplitAcrossTwoFieldsDoesNotHold() {
    assertThat(check("channel=EMAIL Template").supported()).isFalse();
  }

  @Test
  void aLabelThePromptWritesMayLeadAQuote() {
    // As qwen3:8b quoted in the guarded live run.
    assertThat(check("payload: {\"orderId\":\"o-3001\",\"note\":\"[text, 124 chars]\"}").supported()).isTrue();
    assertThat(check("Error: Missing required field: address").supported()).isTrue();
    assertThat(check("payload: [text, 124 chars]").supported()).as("a label adds no letters").isFalse();
    assertThat(check("error: Connection refused").supported()).isFalse();
  }

  @Test
  void aFenceInAQuoteCutsItAndAddsNothing() {
    // qwen3:8b copied the fence along with an XML payload in the live eval.
    assertThat(check("payload: <<<UNTRUSTED kind=payload>>>Missing required field: address<<<END UNTRUSTED>>>")
      .supported()).isTrue();
    assertThat(check("<<<UNTRUSTED kind=dimensions>>>channel=EMAIL<<<END UNTRUSTED>>> Error: "
      + "<<<UNTRUSTED kind=error>>>Template '<value>' not found<<<END UNTRUSTED>>>").supported())
      .as("each fenced piece holds on its own").isTrue();
    assertThat(check("<<<UNTRUSTED kind=error>>>Connection refused<<<END UNTRUSTED>>>").supported()).isFalse();
    assertThat(check("<<<END UNTRUSTED>>> error: <<<UNTRUSTED kind=error>>>").supported())
      .as("markup alone").isFalse();
  }

  private static EvidenceCheck.Result check(String... evidence) {
    return EvidenceCheck.check(List.of(evidence), SHOWN, 1);
  }
}
