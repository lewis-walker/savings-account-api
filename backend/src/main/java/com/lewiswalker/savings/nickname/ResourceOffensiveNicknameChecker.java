package com.lewiswalker.savings.nickname;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Checks a nickname against a blocked list held in a resource file.
 *
 * <p>Matching normalises first — lower-cased, and anything that is not a letter or
 * digit removed — so {@code B a d.W0rd} does not walk past a list containing
 * {@code badw0rd}. Leetspeak folding (0 to o, 1 to l) catches the cheapest evasions.
 *
 * <p>Matching is on substrings, which is the right trade for a bank: a false positive
 * costs the customer a second attempt at a nickname, a false negative puts a slur on a
 * statement. It does mean the Scunthorpe problem is live — a legitimate nickname can be
 * refused because a blocked word appears inside it. Accepted deliberately.
 *
 * <p>TODO: in a real system this list is not a file baked into the artifact. It belongs
 * behind an interface like this one, sourced from a moderation service or a table an
 * operations team can edit without a deployment, and the outcome wants recording for
 * review. The interface is the seam; the file is a stand-in.
 */
@Component
public class ResourceOffensiveNicknameChecker implements OffensiveNicknameChecker {

    private final Set<String> blocked;

    public ResourceOffensiveNicknameChecker(
            @org.springframework.beans.factory.annotation.Value("classpath:offensive-nicknames.txt") Resource resource) {
        this.blocked = load(resource);
    }

    @Override
    public void check(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return;
        }
        String normalised = normalise(nickname);
        for (String word : blocked) {
            if (normalised.contains(word)) {
                throw new OffensiveNicknameException();
            }
        }
    }

    static String normalise(String value) {
        String lowered = value.toLowerCase(Locale.ROOT)
                .replace('0', 'o')
                .replace('1', 'l')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('@', 'a')
                .replace('$', 's');
        return lowered.replaceAll("[^a-z]", "");
    }

    private static Set<String> load(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(ResourceOffensiveNicknameChecker::normalise)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            // Failing to start is correct. Silently running with an empty blocked list
            // would mean the check quietly stops working and nobody finds out.
            throw new UncheckedIOException("could not load the offensive nickname list", e);
        }
    }
}
