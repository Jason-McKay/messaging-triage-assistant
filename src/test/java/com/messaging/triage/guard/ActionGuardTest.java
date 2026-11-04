package com.messaging.triage.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR 0005: advice to change message state is found in the words, however the model puts it. */
class ActionGuardTest {

  private static final char RIGHT_QUOTE = (char) 0x2019;
  private static final char ZERO_WIDTH_SPACE = (char) 0x200B;
  private static final char SOFT_HYPHEN = (char) 0x00AD;
  private static final char NON_BREAKING_HYPHEN = (char) 0x2011;

  @Test
  void eachActionIsFound() {
    assertFlagged(
      "Resend all dead letters now.",
      "Replay the messages on DLQ.",
      "Delete the message from the DLQ.",
      "Purge the DLQ.",
      "Move the messages back to the orders queue.",
      "Bulk-retry the failed notifications.",
      "Retry them all after the fix.",
      "Requeue the backlog.",
      "Discard the poison messages.",
      "Deletion of the stuck messages is recommended.");
  }

  @Test
  void paraphrasesAreFoundToo() {
    assertFlagged(
      "Re-send the failed notifications once the template exists.",
      "Send them again.",
      "Put the messages back on the queue.",
      "Publish the 3 failed notifications again.",
      "Clear the DLQ.",
      "Drain the orders queue.",
      "Remove it from the DLQ.",
      "Consider requeueing the backlog.",
      "Reprocessing every message should fix it.",
      "Retry each one.",
      "Retry the failed messages.");
  }

  @Test
  void adviceInThePassiveOrAfterAModalIsAdvice() {
    assertFlagged(
      "The messages should be resent once fixed.",
      "They can be safely deleted.",
      "The dead letters need to be moved back to orders.",
      "You can resend them after the fix.",
      "It is safe to resend them now.",
      "Once the template exists, they can be replayed.");
  }

  @Test
  void conditionalAdviceIsStillAdvice() {
    assertFlagged(
      "Resend only after the template is fixed.",
      "Fix the template, then replay the dead letters.",
      "If the payload is valid, delete the duplicates on the DLQ.");
  }

  @Test
  void aNegationThatDoesNotHoldTheVerbTightlyIsNoNegation() {
    assertFlagged(
      "Don't hesitate to resend.",
      "Don't forget to purge the queue.",
      "Don't just resend them.",
      "No, delete them.",
      "Do not resend; delete them instead.",
      "Do not wait.\nResend everything.",
      "The template is not the problem and the messages should be resent.");
  }

  @Test
  void spellingTricksDoNotHideIt() {
    assertFlagged(
      "RESEND ALL",
      "Don" + RIGHT_QUOTE + "t hesitate to resend.",
      "res" + ZERO_WIDTH_SPACE + "end all",
      "re" + SOFT_HYPHEN + "send the messages",
      "re" + NON_BREAKING_HYPHEN + "send the messages",
      "re send the messages",
      fullWidth("resend") + " all",
      "**Resend** the messages");
  }

  @Test
  void aReportedInstructionIsFlaggedToo() {
    // Conservative on purpose: words alone cannot tell a quote of the payload from advice.
    assertFlagged("The payload says to delete every message on DLQ.");
  }

  @Test
  void aTightNegationIsNotAdvice() {
    assertNotFlagged(
      "I cannot delete messages.",
      "Do not resend until the template is fixed.",
      "Never delete or move a message before its cause is fixed.",
      "Do not resend, delete or move the messages.",
      "The messages must not be moved back.",
      "There is no need to resend them.",
      "It is not safe to resend these messages until the template is fixed.",
      "Neither resend nor delete them.",
      "Avoid replaying the dead letters.",
      "No message should be deleted.",
      "Do not attempt to bulk-retry them.",
      "Don" + RIGHT_QUOTE + "t resend anything yet.");
  }

  @Test
  void aDescriptionOfWhatHappenedIsNotAdvice() {
    assertNotFlagged(
      "The message was moved to DLQ after 10 delivery attempts.",
      "It has been retried 3 times.",
      "All 3 messages were retried.",
      "The template order-shipped was deleted or never created.",
      "The broker redelivers the message 10 times before dead-lettering it.",
      "Artemis will redeliver it up to 10 times.",
      "The sender sent it again.",
      "The message failed to be moved to the DLQ.",
      "They were automatically moved to the DLQ.");
  }

  @Test
  void wordsThatOnlyLookLikeActionsAreNot() {
    assertNotFlagged(
      "Each retry failed with the same error.",
      "A clear error message says the field is missing.",
      "Move on to checking the template.",
      "Remove the orderId requirement from the template.",
      "Add orderId to the payload, then retry the message.",
      "Push notifications fail again.",
      "Clear the template cache.",
      "The data contains instructions.",
      "Ask the sender to add orderId.");
  }

  @Test
  void aBaseFormThatModifiesANounIsNoVerb() {
    // whatToCheck items qwen3:8b wrote in the guarded live run, flagged until this rule.
    assertNotFlagged(
      "Retry configuration for notifications with templateKey=no-such-template",
      "Dead-lettering settings and retry limits for notifications in notification-platform",
      "Check the purge policy for the DLQ.");
    assertFlagged(
      "Retry the notifications once the retry configuration is fixed.",
      "Purge the DLQ, then review the purge policy.");
  }

  @Test
  void theWarningItselfNamesNoAction() {
    assertNotFlagged(ActionGuard.WARNING);
    assertThat(ActionGuard.WARNING).matches("\\p{ASCII}+");
  }

  @Test
  void nothingIsNotAdvice() {
    assertThat(ActionGuard.recommendsStateChange(null)).isFalse();
    assertThat(ActionGuard.recommendsStateChange(" ")).isFalse();
  }

  @Test
  void theWarningGoesInFrontOnce() {
    String warned = ActionGuard.warned("Resend all.");

    assertThat(warned).isEqualTo(ActionGuard.WARNING + " Resend all.");
    assertThat(ActionGuard.warned(warned)).isEqualTo(warned);
    assertThat(ActionGuard.warned(null)).isEqualTo(ActionGuard.WARNING);
  }

  @Test
  void clausesEndAtSentencePunctuationAndKeepCommas() {
    assertThat(ActionGuard.clauses("Don't wait, re-send: all of them.\nNow"))
      .isEqualTo(List.of(List.of("dont", "wait", ",", "resend"), List.of("all", "of", "them"), List.of("now")));
  }

  private static void assertFlagged(String... texts) {
    for (String text : texts) {
      assertThat(ActionGuard.recommendsStateChange(text)).as(text).isTrue();
    }
  }

  private static void assertNotFlagged(String... texts) {
    for (String text : texts) {
      assertThat(ActionGuard.recommendsStateChange(text)).as(text).isFalse();
    }
  }

  /** The full-width forms NFKC folds back to ASCII. */
  private static String fullWidth(String ascii) {
    StringBuilder text = new StringBuilder();
    ascii.chars().forEach(c -> text.append((char) (c + 0xFEE0)));
    return text.toString();
  }
}
