package io.github.gawhara.antoutlaw;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Normalizes chat text (Arabic + Latin) so simple evasion tricks do not bypass the filter:
 * diacritics, tatweel, alef/yaa/taa-marbuta variants, repeated letters, spacing, symbols and leetspeak.
 */
final class TextNormalizer {
    private TextNormalizer() {}

    static String normalize(String input, boolean collapseRepeats) {
        if (input == null) return "";
        String s = input.toLowerCase(Locale.ROOT);
        // invisible / bidi characters
        s = s.replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF\\u00AD]", "");
        // decompose (also turns Arabic presentation forms and accented letters into base letters + marks)
        s = Normalizer.normalize(s, Normalizer.Form.NFKD);
        // remove all combining marks (Arabic tashkeel, hamza marks, Latin accents)
        s = s.replaceAll("\\p{M}+", "");
        // tatweel
        s = s.replace("ـ", "");
        // Arabic letter unification
        s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
             .replace('ى', 'ي').replace('ئ', 'ي').replace('ة', 'ه').replace('ؤ', 'و')
             .replace('گ', 'ك').replace('ک', 'ك').replace('ی', 'ي').replace('ڤ', 'ف').replace('پ', 'ب');
        // leetspeak only inside tokens that also contain letters (so plain numbers stay numbers)
        s = leetTokens(s);
        // drop everything that is not a letter or digit (spaces, dots, dashes, emojis...)
        s = s.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
        if (collapseRepeats) s = s.replaceAll("(.)\\1+", "$1");
        return s;
    }

    private static String leetTokens(String s) {
        String[] tokens = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String tok : tokens) {
            boolean hasLetter = tok.codePoints().anyMatch(Character::isLetter);
            if (hasLetter) {
                tok = tok.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a')
                         .replace('5', 's').replace('7', 't').replace('@', 'a').replace('$', 's')
                         .replace('!', 'i');
            }
            out.append(tok).append(' ');
        }
        return out.toString();
    }

    /**
     * Returns the configured word that matched, if any.
     * {@code words}: matched anywhere (even with spaces/symbols inserted).
     * {@code wholeWords}: matched only as a standalone word.
     */
    static Optional<String> findBlockedWord(String message, List<String> words, List<String> wholeWords,
                                            boolean collapseRepeats) {
        String n = normalize(message, collapseRepeats);
        if (!n.isEmpty()) {
            for (String w : words) {
                String t = normalize(w, collapseRepeats);
                if (!t.isBlank() && n.contains(t)) return Optional.of(w);
            }
        }
        if (!wholeWords.isEmpty()) {
            List<String> tokens = new ArrayList<>();
            for (String raw : message.split("[\\s\\p{Punct}،؛؟]+")) {
                String t = normalize(raw, collapseRepeats);
                if (!t.isBlank()) tokens.add(t);
            }
            for (String w : wholeWords) {
                String t = normalize(w, collapseRepeats);
                if (!t.isBlank() && tokens.contains(t)) return Optional.of(w);
            }
        }
        return Optional.empty();
    }
}
