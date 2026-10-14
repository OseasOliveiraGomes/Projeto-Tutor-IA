package br.com.sylph.service;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Respostas locais para mensagens compostas inteiramente por expressões sociais conhecidas. */
final class SocialInteraction {
    private enum Intent { GREETING, WELLBEING, THANKS, FAREWELL }

    private static final Pattern PHRASE = Pattern.compile(
        "(?:(?<greeting>bom dia|boa tarde|boa noite|ola|opa|oi)"
        + "|(?<wellbeing>tudo bem|como vai)"
        + "|(?<thanks>muito obrigado|muito obrigada|obrigado|obrigada|valeu)"
        + "|(?<farewell>ate mais|tchau))(?= |$)");
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{P}\\p{Z}\\s]+", Pattern.UNICODE_CHARACTER_CLASS);

    private SocialInteraction() { }

    static Optional<String> replyTo(String message) {
        String normalized = ACCENTS.matcher(Normalizer.normalize(message, Normalizer.Form.NFD))
            .replaceAll("").toLowerCase(Locale.ROOT);
        normalized = SEPARATORS.matcher(normalized).replaceAll(" ").trim();
        if (normalized.isEmpty()) return Optional.empty();

        var intents = EnumSet.noneOf(Intent.class);
        var matcher = PHRASE.matcher(normalized);
        int position = 0;
        while (position < normalized.length()) {
            matcher.region(position, normalized.length());
            // Não basta encontrar uma saudação no início: todo o texto deve ser reconhecido.
            if (!matcher.lookingAt()) return Optional.empty();
            if (matcher.group("greeting") != null) intents.add(Intent.GREETING);
            else if (matcher.group("wellbeing") != null) intents.add(Intent.WELLBEING);
            else if (matcher.group("thanks") != null) intents.add(Intent.THANKS);
            else intents.add(Intent.FAREWELL);
            position = matcher.end();
            if (position < normalized.length()) position++; // único espaço normalizado
        }

        if (intents.contains(Intent.FAREWELL)) {
            String farewell = "Até mais! Estou à disposição para ajudar com dúvidas sobre o Estágio Obrigatório.";
            return Optional.of(intents.contains(Intent.THANKS) ? "Por nada! " + farewell : farewell);
        }
        if (intents.contains(Intent.THANKS))
            return Optional.of("Por nada! Estou à disposição para ajudar com dúvidas sobre o Estágio Obrigatório.");
        if (intents.contains(Intent.WELLBEING))
            return Optional.of("Tudo certo por aqui! Sou a Sylph, tutora virtual para dúvidas sobre o Estágio Obrigatório. Como posso ajudar?");
        return Optional.of("Olá! Sou a Sylph, tutora virtual de apoio a estudantes com dúvidas sobre o Estágio Obrigatório. Como posso ajudar?");
    }
}
