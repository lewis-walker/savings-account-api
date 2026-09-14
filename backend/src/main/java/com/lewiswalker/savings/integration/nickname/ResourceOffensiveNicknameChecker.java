package com.lewiswalker.savings.integration.nickname;

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
 * Checks a nickname against a blocked list in a resource file.
 *
 * <p>Normalised before matching - lower-cased, leetspeak folded, then non-letters
 * stripped, in that order - and matched on substrings, which accepts the Scunthorpe
 * problem as the better trade for a bank.
 *
 * <p>TODO: a real list comes from a moderation service or a table operations can edit,
 * not a file in the artifact. The interface is what a real one implements.
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
