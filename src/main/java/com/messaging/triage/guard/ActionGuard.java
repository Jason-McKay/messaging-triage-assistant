package com.messaging.triage.guard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds advice to change message state in text the model wrote (ADR 0005): to resend, replay,
 * delete, purge, move or bulk-retry messages. v1 only reads, so such advice is never acted
 * on here, but a person may act on it, and an injection that sways the model shows up as
 * exactly this. A hit is flagged and put behind {@link #WARNING}; the model's own wording is
 * never trusted to be the safe version.
 * <p>
 * Matching is on normalized words, not phrases: compatibility forms folded, hidden
 * characters dropped, "re-send" joined, then each clause read word by word.
 * <ul>
 *   <li>resend, redeliver, requeue, replay, delete, purge and the like count in any clause;</li>
 *   <li>send, publish, deliver, put and the like count with "again" or "back" after them;</li>
 *   <li>move, remove, clear, drop, drain and the like count with messages, a queue, "them"
 *       or "everything" just after them, or before them in the passive, so "move on to the
 *       template" does not;</li>
 *   <li>retry counts only in bulk: "retry them all", "bulk-retry", "retry the messages".</li>
 * </ul>
 * Not advice: a tight negation ("do not resend", "there is no need to resend them", "must
 * never be moved"), and a description of what already happens ("was moved to DLQ", "has
 * been retried 3 times", "the broker redelivers it"). Anything else that names the action
 * counts, conditional advice included ("resend once it is fixed"), and so does a negation
 * that does not hold the verb tightly ("don't hesitate to resend", "don't just resend").
 */
public final class ActionGuard {

  /** Put before a flagged next step and after a flagged chat answer. ASCII, and names no action. */
  public static final String WARNING = "[Advice only: v1 takes no actions on messages. Any change to their state "
    + "is for a person to decide, once the cause is fixed.]";

  /** How far back a negator may sit from the verb it negates, in words that do not change the sense. */
  static final int MAX_NEGATION_GAP = 5;

  private enum Family { STATE, SEND, OBJECT, RETRY }

  /** PRESENT is the -s form: "the broker redelivers it" describes, it does not advise. */
  private enum Form { BASE, PRESENT, ING, PAST }

  private record Word(Family family, Form form) {
  }

  private static final Map<String, Word> WORDS = new HashMap<>();

  static {
    // base, -s form, -ing forms, past forms; alternatives joined by commas.
    verbs(Family.STATE, """
      resend resends resending resent
      redeliver redelivers redelivering redelivered
      requeue requeues requeuing,requeueing requeued
      reenqueue reenqueues reenqueuing,reenqueueing reenqueued
      reprocess reprocesses reprocessing reprocessed
      resubmit resubmits resubmitting resubmitted
      republish republishes republishing republished
      redispatch redispatches redispatching redispatched
      repost reposts reposting reposted
      replay replays replaying replayed
      redrive redrives redriving redrove,redriven,redrived
      delete deletes deleting deleted
      purge purges purging purged
      discard discards discarding discarded
      erase erases erasing erased
      """);
    nouns(Family.STATE, "deletion", "deletions", "resubmission", "resubmissions");
    verbs(Family.SEND, """
      send sends sending sent
      publish publishes publishing published
      deliver delivers delivering delivered
      process processes processing processed
      submit submits submitting submitted
      enqueue enqueues enqueuing,enqueueing enqueued
      queue queues queuing,queueing queued
      dispatch dispatches dispatching dispatched
      put puts putting put
      """);
    verbs(Family.OBJECT, """
      move moves moving moved
      remove removes removing removed
      clear clears clearing cleared
      drop drops dropping dropped
      drain drains draining drained
      flush flushes flushing flushed
      clean cleans cleaning cleaned
      wipe wipes wiping wiped
      """);
    nouns(Family.OBJECT, "removal");
    verbs(Family.RETRY, """
      retry retries retrying retried
      """);
  }

  /** "again" or "back" after a send-like verb makes it a resend. */
  private static final Set<String> AGAIN = Set.of("again", "back", "anew");
  private static final int AGAIN_WINDOW = 5;

  /** What a move-like verb must act on to count. */
  private static final Set<String> OBJECTS = Set.of("message", "messages", "msg", "msgs", "notification",
    "notifications", "letter", "letters", "queue", "queues", "dlq", "dlqs", "backlog", "them", "everything", "all");
  /** Words between a move-like verb and its object that do not count against the window. */
  private static final Set<String> OBJECT_SKIP = Set.of("the", "a", "an", "every", "each", "these", "those", "any",
    "its", "their", "this", "that", "of", "failed", "failing", "dead", "stuck", "remaining", "affected", "poison",
    "bad", "old", "existing", "pending", "other", "from", "to", "off", "out", "onto", "into");
  private static final int OBJECT_WINDOW = 6;

  /** What makes a retry a bulk one, after it ... */
  private static final Set<String> BULK = Set.of("all", "every", "each", "bulk", "batch", "mass", "them",
    "everything", "whole", "entire", "messages", "notifications", "letters", "queue", "queues", "dlq", "dlqs",
    "backlog");
  /** ... or just before it. */
  private static final Set<String> BULK_BEFORE = Set.of("bulk", "batch", "mass");
  private static final int BULK_WINDOW = 5;

  /** Before a base form, these make it a noun or an adjective: "each retry", "a clear error". */
  private static final Set<String> NOUN_MARKERS = Set.of("a", "an", "the", "each", "every", "all", "any", "this",
    "that", "these", "those", "its", "their", "his", "her", "our", "your", "my", "one", "another", "per", "max",
    "maximum", "is", "are", "was", "were", "be", "been", "very", "quite", "so", "too", "more", "less", "most");

  private static final Set<String> NEGATORS = Set.of("not", "never", "no", "dont", "doesnt", "didnt", "cannot",
    "cant", "wont", "shouldnt", "mustnt", "neednt", "isnt", "arent", "wasnt", "werent", "avoid", "avoiding",
    "without", "nor", "neither", "nothing", "none", "nobody", "refrain", "stop");
  /** Words a negator may reach across without its sense changing. "hesitate" and "just" are not among them. */
  private static final Set<String> NEGATION_GAP = Set.of("to", "be", "been", "being", "is", "are", "was", "were",
    "am", "do", "does", "have", "has", "had", "get", "got", "any", "the", "a", "an", "them", "these", "those", "it",
    "its", "their", "they", "you", "we", "i", "anyone", "anybody", "one", "all", "of", "yet", "ever", "need",
    "needs", "needed", "should", "must", "can", "could", "will", "would", "shall", "may", "might", "from", "point",
    "reason", "use", "sense", "in", "worth", "safe", "wise", "advisable", "recommended", "necessary", "required",
    "possible", "try", "trying", "attempt", "attempting", "manually", "bulk", "batch", "mass", "message",
    "messages", "notification", "notifications", "dead", "letter", "letters");
  /** Joins one action to the next, so a negator before the first reaches the rest: "do not resend, delete or move". */
  private static final Set<String> LINKS = Set.of(",", "or", "nor", "and");

  /** The auxiliaries of a passive or perfect: "was moved", "has been retried", "should be resent". */
  private static final Set<String> AUXILIARIES = Set.of("is", "are", "was", "were", "be", "been", "being", "am",
    "has", "have", "had", "get", "gets", "got", "gotten", "getting", "keeps", "kept");
  private static final Set<String> ADVERBS = Set.of("already", "automatically", "also", "then", "still", "just",
    "previously", "repeatedly", "eventually", "now", "successfully", "safely", "simply", "manually", "immediately",
    "first", "only");
  /** Before an auxiliary, these make it advice: "should be resent", "can be deleted", "needs to be moved". */
  private static final Set<String> ADVISORY = Set.of("should", "must", "can", "could", "need", "needs", "ought",
    "to", "please", "let", "lets", "shall", "better", "recommend", "recommended", "suggest", "suggested", "advise",
    "advised");
  /** Before "to", these make what follows a description: "failed to be moved", "is going to be deleted". */
  private static final Set<String> DESCRIPTIVE_TO = Set.of("failed", "fails", "fail", "going", "about", "used",
    "appears", "appear", "appeared", "seems", "seem", "seemed", "set", "scheduled", "due", "likely", "unable");
  /** Before a base form, these make it a description: "Artemis will redeliver it". */
  private static final Set<String> DESCRIPTIVE_MODALS = Set.of("will", "would", "may", "might", "does", "did");

  private static final Pattern APOSTROPHES = Pattern.compile("[\\x{2018}\\x{2019}\\x{02BC}\\x{0060}\\x{00B4}]");
  private static final Pattern HIDDEN = Pattern.compile("[\\p{Cf}[\\p{Cc}&&[^\\t\\n\\r\\x{0085}]]]");
  private static final Pattern HYPHENS = Pattern.compile("[\\x{2010}\\x{2011}\\x{2012}\\x{2212}]");
  private static final Pattern RE_PREFIX = Pattern.compile("\\bre-(?=\\p{L})");
  private static final Pattern CLAUSE_END =
    Pattern.compile("[.;:!?()\\[\\]{}<>|\\x{2013}\\x{2014}\\r\\n\\x{0085}\\x{2028}\\x{2029}]+");
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+|,");
  private static final Pattern DIGITS = Pattern.compile("\\d+");

  private ActionGuard() {
  }

  /** Whether {@code text} advises a change to message state; null and blank do not. */
  public static boolean recommendsStateChange(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    for (List<String> clause : clauses(text)) {
      for (int at = 0; at < clause.size(); at++) {
        if (recommends(clause, at)) {
          return true;
        }
      }
    }
    return false;
  }

  /** {@code nextStep} behind the warning, once. */
  public static String warned(String nextStep) {
    if (nextStep == null || nextStep.isBlank()) {
      return WARNING;
    }
    return nextStep.startsWith(WARNING) ? nextStep : WARNING + " " + nextStep;
  }

  private static boolean recommends(List<String> words, int at) {
    Word word = WORDS.get(words.get(at));
    if (word == null) {
      return false;
    }
    if (word.form() == Form.BASE && word.family() != Family.STATE && nounMarked(words, at)) {
      return false;
    }
    boolean inContext = switch (word.family()) {
      case STATE -> true;
      case SEND -> anyAfter(words, at, AGAIN, AGAIN_WINDOW);
      case OBJECT -> objectFollows(words, at) || word.form() == Form.PAST && anyBefore(words, at, OBJECTS);
      case RETRY -> anyAfter(words, at, BULK, BULK_WINDOW) || bulkBefore(words, at);
    };
    return inContext && !negated(words, at) && advised(words, at, word.form());
  }

  private static boolean nounMarked(List<String> words, int at) {
    String before = at > 0 ? words.get(at - 1) : "";
    return NOUN_MARKERS.contains(before) || DIGITS.matcher(before).matches();
  }

  private static boolean anyAfter(List<String> words, int at, Set<String> wanted, int window) {
    for (int next = at + 1; next < words.size() && next <= at + window; next++) {
      String word = words.get(next);
      if (word.equals(",")) {
        return false;
      }
      if (wanted.contains(word)) {
        return true;
      }
    }
    return false;
  }

  private static boolean bulkBefore(List<String> words, int at) {
    for (int before = at - 1; before >= 0 && before >= at - 2; before--) {
      if (BULK_BEFORE.contains(words.get(before))) {
        return true;
      }
    }
    return false;
  }

  /** For a passive, whose object comes first: "the dead letters need to be moved". */
  private static boolean anyBefore(List<String> words, int at, Set<String> wanted) {
    for (int before = at - 1; before >= 0 && before >= at - OBJECT_WINDOW; before--) {
      String word = words.get(before);
      if (word.equals(",")) {
        return false;
      }
      if (wanted.contains(word)) {
        return true;
      }
    }
    return false;
  }

  /** An object soon after, with at most one word between that is not filler. */
  private static boolean objectFollows(List<String> words, int at) {
    int others = 0;
    for (int next = at + 1; next < words.size() && next <= at + OBJECT_WINDOW; next++) {
      String word = words.get(next);
      if (OBJECTS.contains(word)) {
        return true;
      }
      if (word.equals(",") || !OBJECT_SKIP.contains(word) && ++others > 1) {
        return false;
      }
    }
    return false;
  }

  /** A negator before the verb, reached across words that do not change the sense, or across earlier actions. */
  private static boolean negated(List<String> words, int at) {
    int gap = 0;
    for (int before = at - 1; before >= 0 && gap <= MAX_NEGATION_GAP; before--) {
      String word = words.get(before);
      if (NEGATORS.contains(word)) {
        return true;
      }
      if (LINKS.contains(word) && before > 0 && WORDS.containsKey(words.get(before - 1))) {
        before--;
        continue;
      }
      if (!NEGATION_GAP.contains(word)) {
        return false;
      }
      gap++;
    }
    return false;
  }

  /**
   * Advice rather than description. After an auxiliary it is advice only when a modal of
   * advice comes first ("should be resent", not "was resent"); without one, a past or -s
   * form describes, and a base or -ing form advises unless a modal of description comes first.
   */
  private static boolean advised(List<String> words, int at, Form form) {
    int before = at - 1;
    boolean auxiliary = false;
    while (before >= 0 && (AUXILIARIES.contains(words.get(before)) || ADVERBS.contains(words.get(before)))) {
      auxiliary |= AUXILIARIES.contains(words.get(before));
      before--;
    }
    String lead = before >= 0 ? words.get(before) : "";
    if (lead.equals("to") && before > 0 && DESCRIPTIVE_TO.contains(words.get(before - 1))) {
      return false;
    }
    if (auxiliary) {
      return ADVISORY.contains(lead);
    }
    if (form == Form.PAST || form == Form.PRESENT) {
      return false;
    }
    return !DESCRIPTIVE_MODALS.contains(lead);
  }

  /** The text as clauses of words, a comma kept as a word of its own. */
  static List<List<String>> clauses(String text) {
    String folded = APOSTROPHES.matcher(text).replaceAll("'");
    folded = Normalizer.normalize(folded, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    folded = HIDDEN.matcher(folded).replaceAll("").replace("'", "");
    folded = HYPHENS.matcher(folded).replaceAll("-");
    folded = RE_PREFIX.matcher(folded).replaceAll("re");
    List<List<String>> clauses = new ArrayList<>();
    for (String clause : CLAUSE_END.split(folded)) {
      List<String> words = new ArrayList<>();
      Matcher token = TOKEN.matcher(clause);
      while (token.find()) {
        String word = token.group();
        int last = words.size() - 1;
        if (last >= 0 && words.get(last).equals("re") && WORDS.containsKey("re" + word)) {
          words.set(last, "re" + word);
        } else {
          words.add(word);
        }
      }
      if (!words.isEmpty()) {
        clauses.add(words);
      }
    }
    return clauses;
  }

  private static void verbs(Family family, String table) {
    for (String line : table.strip().split("\n")) {
      String[] forms = line.strip().split("\\s+");
      WORDS.put(forms[0], new Word(family, Form.BASE));
      WORDS.putIfAbsent(forms[1], new Word(family, Form.PRESENT));
      for (String ing : forms[2].split(",")) {
        WORDS.putIfAbsent(ing, new Word(family, Form.ING));
      }
      for (String past : forms[3].split(",")) {
        WORDS.putIfAbsent(past, new Word(family, Form.PAST));
      }
    }
  }

  private static void nouns(Family family, String... nouns) {
    for (String noun : nouns) {
      WORDS.put(noun, new Word(family, Form.BASE));
    }
  }
}
